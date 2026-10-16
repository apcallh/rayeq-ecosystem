package app.rayeq.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Title(
    val id: Int,
    val type: String, // "movie" | "tv"
    val name: String, // الاسم الإنجليزي فقط للعرض (§2.4)
    val originalName: String = "",
    val arabicName: String = "",
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val hasTextlessPoster: Boolean = false,
    val year: Int? = null,
    val voteAverage: Double = 0.0,
    val runtimeMin: Int? = null,
    val contentRating: String? = null,
    val genres: List<String> = emptyList(),
    val isAnime: Boolean = false,
    val imdbId: String? = null
) {
    val isMovie: Boolean get() = type == "movie"
    val key: String get() = "$type:$id"
}

@Serializable
enum class BookmarkStatus(val labelAr: String) {
    WATCHING("أشاهد"),
    COMPLETED("أكملت"),
    PLAN("سأشاهد"),
    ON_HOLD("متوقف"),
    DROPPED("تركته"),
    REWATCHING("أعيد المشاهدة")
}

@Serializable
data class Bookmark(
    val title: Title,
    val status: BookmarkStatus,
    val added: Long,
    val updated: Long
)

@Serializable
data class Progress(
    val title: Title,
    val season: Int? = null,
    val episode: Int? = null,
    val absEpisode: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L
) {
    val fraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

@Serializable
data class HistoryItem(
    val title: Title,
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAt: Long
)

@Serializable
data class Store(
    val favourites: List<Title> = emptyList(),
    val bookmarks: Map<String, Bookmark> = emptyMap(),
    val history: List<Progress> = emptyList(),
    val log: List<HistoryItem> = emptyList(),
    val watched: Set<String> = emptySet(),
    val positions: Map<String, Long> = emptyMap(),
    val durations: Map<String, Long> = emptyMap(),
    val posAt: Map<String, Long> = emptyMap(),
    val serverPicks: Map<String, ServerPick> = emptyMap(),
    val stickyPicks: Map<String, StickyPick> = emptyMap(),
    val trackPicks: Map<String, TrackPick> = emptyMap(),
    val recentServers: List<String> = emptyList(),
    val learnedQuality: Map<String, Int> = emptyMap(),
    val listPos: Map<String, ListPos> = emptyMap(),
    val searches: List<String> = emptyList(),
    val lastSeenEpisode: Map<String, String> = emptyMap(),
    val speeds: Map<String, Float> = emptyMap()
)

object Library {
    private const val PREF_FILE = "rayeq_library_store"
    const val WATCHED_THRESHOLD = 0.92f

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var appContext: Context? = null

    private val _store = MutableStateFlow(Store())
    val store: StateFlow<Store> = _store

    fun epKey(titleKey: String, season: Int?, episode: Int?): String {
        return if (season == null || episode == null) titleKey else "$titleKey:$season:$episode"
    }

    fun syncProgressKey(title: Title, season: Int?, episode: Int?, absEpisode: Int? = null): String {
        return if (title.isMovie) {
            "tmdb:movie:${title.id}"
        } else if (absEpisode != null && season == null) {
            "abs:tv:${title.id}:e$absEpisode"
        } else {
            "tmdb:tv:${title.id}:s${season ?: 1}e${episode ?: 1}"
        }
    }

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return positionMs.toDouble() >= WATCHED_THRESHOLD * durationMs.toDouble()
    }

    fun shouldShowResumeButton(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs <= 5_000L) return false
        if (durationMs > 0L && isCompleted(positionMs, durationMs)) return false
        return true
    }

    /**
     * الاستئناف الذكي (§10.1): حلقة منتهية في «أكمل المشاهدة» تفتح الحلقة التالية، حتى لو كانت في الموسم التالي.
     */
    fun smartResumeTarget(
        progress: Progress,
        seasonEpisodeCounts: Map<Int, Int>
    ): Pair<Int, Int>? {
        if (progress.title.isMovie) return null
        val s = progress.season ?: 1
        val e = progress.episode ?: 1
        if (!isCompleted(progress.positionMs, progress.durationMs)) {
            return s to e
        }
        val maxEpInSeason = seasonEpisodeCounts[s] ?: e
        return if (e < maxEpInSeason) {
            s to (e + 1)
        } else {
            val nextSeason = seasonEpisodeCounts.keys.filter { it > s }.minOrNull()
            if (nextSeason != null) nextSeason to 1 else s to e
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val raw = sp.getString("store_json", null)
        if (!raw.isNullOrBlank()) {
            try {
                _store.value = json.decodeFromString<Store>(raw)
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun update(commitNow: Boolean = true, transform: (Store) -> Store) {
        val next = transform(_store.value)
        _store.value = next
        val sp = appContext?.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE) ?: return
        val editor = sp.edit().putString("store_json", json.encodeToString(next))
        if (commitNow) editor.commit() else editor.apply()
    }

    fun savePosition(
        title: Title,
        season: Int?,
        episode: Int?,
        absEpisode: Int? = null,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val k = epKey(title.key, season, episode)
        val done = isCompleted(positionMs, durationMs)
        update(commitNow = true) { cur ->
            val nextPositions = cur.positions + (k to positionMs.coerceAtLeast(0L))
            val nextDurations = if (durationMs > 0L) cur.durations + (k to durationMs) else cur.durations
            val nextPosAt = cur.posAt + (k to nowMs)
            val nextWatched = if (done) cur.watched + k else cur.watched

            val prog = Progress(
                title = title,
                season = season,
                episode = episode,
                absEpisode = absEpisode,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = nowMs
            )
            val nextHistory = listOf(prog) + cur.history.filterNot { it.title.key == title.key }
            val nextLog = if (Prefs.settings.value.saveWatchHistory) {
                (listOf(HistoryItem(title, season, episode, nowMs)) +
                    cur.log.filterNot { it.title.key == title.key && it.season == season && it.episode == episode })
                    .take(300)
            } else cur.log

            cur.copy(
                positions = nextPositions,
                durations = nextDurations,
                posAt = nextPosAt,
                watched = nextWatched,
                history = nextHistory.take(100),
                log = nextLog
            )
        }
        CloudSync.markProgressDirty(
            key = syncProgressKey(title, season, episode, absEpisode),
            posMs = positionMs,
            durMs = durationMs,
            updatedAt = nowMs,
            titleName = title.name
        )
    }

    fun toggleFavourite(title: Title) {
        update { cur ->
            val exists = cur.favourites.any { it.key == title.key }
            val next = if (exists) {
                cur.favourites.filterNot { it.key == title.key }
            } else {
                listOf(title) + cur.favourites
            }
            cur.copy(favourites = next)
        }
        CloudSync.schedulePush()
    }

    fun setBookmark(title: Title, status: BookmarkStatus?) {
        val now = System.currentTimeMillis()
        update { cur ->
            val next = cur.bookmarks.toMutableMap()
            if (status == null) {
                next.remove(title.key)
            } else {
                val prev = next[title.key]
                next[title.key] = Bookmark(
                    title = title,
                    status = status,
                    added = prev?.added ?: now,
                    updated = now
                )
            }
            cur.copy(bookmarks = next)
        }
        CloudSync.schedulePush()
    }

    fun saveStickyPick(titleKey: String, pick: StickyPick) {
        update { cur ->
            cur.copy(stickyPicks = cur.stickyPicks + (titleKey to pick))
        }
        CloudSync.schedulePush()
    }

    fun saveListPos(titleKey: String, pos: ListPos) {
        update(commitNow = false) { cur ->
            cur.copy(listPos = cur.listPos + (titleKey to pos))
        }
        CloudSync.schedulePush()
    }
}
