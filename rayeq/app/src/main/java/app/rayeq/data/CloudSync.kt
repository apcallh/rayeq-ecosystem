package app.rayeq.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SyncBlob(
    val favourites: List<Title> = emptyList(),
    val bookmarks: Map<String, Bookmark> = emptyMap(),
    val stickyPicks: Map<String, StickyPick> = emptyMap(),
    val listPos: Map<String, ListPos> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val settingsAt: Long = 0L
)

object CloudSync {
    private const val PREFS = "rayeq_cloud_sync"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var debounceJob: Job? = null

    data class DirtyProgress(
        val key: String,
        val posMs: Long,
        val durMs: Long,
        val updatedAt: Long,
        val title: String
    )

    private val dirtyProgress = ConcurrentHashMap<String, DirtyProgress>()

    fun init(context: Context) {
        appContext = context.applicationContext
        // دفع مواضع المشاهدة المتغيرة كل 30 ثانية
        scope.launch {
            while (isActive) {
                delay(30_000L)
                if (dirtyProgress.isNotEmpty()) {
                    pushDirtyProgressNow()
                }
            }
        }
    }

    /**
     * دمج كائنين للمزامنة (نقي ومختبر بوحدات JUnit):
     * - المفضلة والقوائم بالاتحاد.
     * - لكل عنصر يفوز الأحدث updated أو at.
     * - الإعدادات حسب settingsAt.
     */
    fun mergeBlobs(local: SyncBlob, remote: SyncBlob): SyncBlob {
        val mergedFavs = (local.favourites + remote.favourites)
            .distinctBy { it.key }

        val mergedBookmarks = mutableMapOf<String, Bookmark>()
        for (k in (local.bookmarks.keys + remote.bookmarks.keys)) {
            val l = local.bookmarks[k]
            val r = remote.bookmarks[k]
            mergedBookmarks[k] = when {
                l != null && r != null -> if (l.updated >= r.updated) l else r
                l != null -> l
                else -> r!!
            }
        }

        val mergedSticky = mutableMapOf<String, StickyPick>()
        for (k in (local.stickyPicks.keys + remote.stickyPicks.keys)) {
            val l = local.stickyPicks[k]
            val r = remote.stickyPicks[k]
            mergedSticky[k] = when {
                l != null && r != null -> if (l.at >= r.at) l else r
                l != null -> l
                else -> r!!
            }
        }

        val mergedListPos = mutableMapOf<String, ListPos>()
        for (k in (local.listPos.keys + remote.listPos.keys)) {
            val l = local.listPos[k]
            val r = remote.listPos[k]
            mergedListPos[k] = when {
                l != null && r != null -> if (l.at >= r.at) l else r
                l != null -> l
                else -> r!!
            }
        }

        val winSettings = if (remote.settingsAt > local.settingsAt) remote.settings else local.settings
        val winSettingsAt = maxOf(local.settingsAt, remote.settingsAt)

        return SyncBlob(
            favourites = mergedFavs,
            bookmarks = mergedBookmarks,
            stickyPicks = mergedSticky,
            listPos = mergedListPos,
            settings = winSettings,
            settingsAt = winSettingsAt
        )
    }

    fun currentLocalBlob(): SyncBlob {
        val st = Library.store.value
        val cfg = Prefs.settings.value
        return SyncBlob(
            favourites = st.favourites,
            bookmarks = st.bookmarks,
            stickyPicks = st.stickyPicks,
            listPos = st.listPos,
            settings = cfg,
            settingsAt = cfg.settingsAt
        )
    }

    private fun applyMergedBlobToLocal(merged: SyncBlob) {
        Library.update { cur ->
            cur.copy(
                favourites = merged.favourites,
                bookmarks = merged.bookmarks,
                stickyPicks = merged.stickyPicks,
                listPos = merged.listPos
            )
        }
        Prefs.applyMerged(merged.settings)
    }

    fun schedulePush() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(15_000L) // debounce بين 10 و30 ثانية
            syncBlobWithServer()
        }
    }

    fun syncAllNow() {
        scope.launch {
            syncBlobWithServer()
            pushDirtyProgressNow()
            pullProgressDelta()
        }
    }

    suspend fun syncBlobWithServer() {
        val sp = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        var baseRev = sp.getInt("sync_rev", 0)

        // GET أولي للدمج
        val (getCode, getBody) = Net.getWithCode("/v1/sync")
        if (getCode == 200 && getBody != null) {
            try {
                val obj = JSONObject(getBody)
                baseRev = obj.optInt("rev", 0)
                if (!obj.isNull("data")) {
                    val dataStr = obj.getJSONObject("data").toString()
                    val remoteBlob = json.decodeFromString<SyncBlob>(dataStr)
                    val merged = mergeBlobs(currentLocalBlob(), remoteBlob)
                    applyMergedBlobToLocal(merged)
                }
            } catch (_: Exception) {}
        }

        // PUT مع إعادة المحاولة عند 409 حتى 3 مرات متتالية
        for (attempt in 1..3) {
            val payloadBlob = currentLocalBlob()
            val blobJsonObj = JSONObject(json.encodeToString(payloadBlob))
            val reqObj = JSONObject()
                .put("data", blobJsonObj)
                .put("baseRev", baseRev)

            val (putCode, putResp) = Net.putJsonWithBody("/v1/sync", reqObj)
            if (putCode == 200 && putResp != null) {
                val resObj = JSONObject(putResp)
                val newRev = resObj.optInt("rev", baseRev + 1)
                sp.edit()
                    .putInt("sync_rev", newRev)
                    .putLong("last_sync_at", System.currentTimeMillis())
                    .apply()
                return
            } else if (putCode == 409 && putResp != null) {
                try {
                    val conflictObj = JSONObject(putResp)
                    baseRev = conflictObj.optInt("rev", baseRev)
                    if (!conflictObj.isNull("data")) {
                        val remoteBlob = json.decodeFromString<SyncBlob>(
                            conflictObj.getJSONObject("data").toString()
                        )
                        val merged = mergeBlobs(currentLocalBlob(), remoteBlob)
                        applyMergedBlobToLocal(merged)
                    }
                } catch (_: Exception) {
                    return
                }
            } else {
                return
            }
        }
    }

    fun markProgressDirty(
        key: String,
        posMs: Long,
        durMs: Long,
        updatedAt: Long,
        titleName: String
    ) {
        dirtyProgress[key] = DirtyProgress(key, posMs, durMs, updatedAt, titleName)
    }

    suspend fun pushDirtyProgressNow() {
        if (dirtyProgress.isEmpty()) return
        val batch = dirtyProgress.values.take(200)
        val arr = JSONArray()
        for (item in batch) {
            arr.put(
                JSONObject()
                    .put("key", item.key)
                    .put("posMs", item.posMs)
                    .put("durMs", item.durMs)
                    .put("updatedAt", item.updatedAt)
                    .put("title", item.title.take(300))
            )
        }
        val (code, _) = Net.putJsonWithBody("/v1/sync/progress", JSONObject().put("items", arr))
        if (code in 200..299) {
            batch.forEach { dirtyProgress.remove(it.key) }
        }
    }

    suspend fun pullProgressDelta() {
        val sp = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        var since = sp.getLong("progress_since", 0L)
        while (true) {
            val (code, body) = Net.getWithCode("/v1/sync/progress?since=$since")
            if (code != 200 || body == null) break
            try {
                val obj = JSONObject(body)
                val items = obj.optJSONArray("items") ?: JSONArray()
                val srvTime = obj.optLong("serverTime", since)
                val more = obj.optBoolean("more", false)
                val next = if (obj.isNull("next")) srvTime else obj.optLong("next", srvTime)

                if (items.length() > 0) {
                    Library.update { cur ->
                        val nextPos = cur.positions.toMutableMap()
                        val nextDur = cur.durations.toMutableMap()
                        val nextAt = cur.posAt.toMutableMap()
                        val nextWatched = cur.watched.toMutableSet()

                        for (i in 0 until items.length()) {
                            val it = items.getJSONObject(i)
                            val rawKey = it.optString("key")
                            val pMs = it.optLong("posMs", 0L)
                            val dMs = it.optLong("durMs", 0L)
                            val uAt = it.optLong("updatedAt", 0L)

                            val localKey = mapSyncKeyToLocalEpKey(rawKey) ?: continue
                            val curAt = nextAt[localKey] ?: 0L
                            if (uAt > curAt) {
                                nextPos[localKey] = pMs
                                if (dMs > 0L) nextDur[localKey] = dMs
                                nextAt[localKey] = uAt
                                if (Library.isCompleted(pMs, dMs)) {
                                    nextWatched.add(localKey)
                                }
                            }
                        }
                        cur.copy(
                            positions = nextPos,
                            durations = nextDur,
                            posAt = nextAt,
                            watched = nextWatched
                        )
                    }
                }

                sp.edit().putLong("progress_since", srvTime).apply()
                if (more && next > since) {
                    since = next
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    fun mapSyncKeyToLocalEpKey(syncKey: String): String? {
        // tmdb:tv:1399:s1e3 -> tv:1399:1:3
        // tmdb:movie:550 -> movie:550
        val tvMatch = Regex("^tmdb:tv:(\d+):s(\d+)e(\d+)$").find(syncKey)
        if (tvMatch != null) {
            val (id, s, e) = tvMatch.destructured
            return "tv:$id:$s:$e"
        }
        val movieMatch = Regex("^tmdb:movie:(\d+)$").find(syncKey)
        if (movieMatch != null) {
            return "movie:${movieMatch.groupValues[1]}"
        }
        return null
    }

    fun exportBackupJson(): String {
        return json.encodeToString(currentLocalBlob())
    }

    fun importBackupJson(rawJson: String): Boolean {
        return try {
            val imported = json.decodeFromString<SyncBlob>(rawJson)
            val merged = mergeBlobs(currentLocalBlob(), imported)
            applyMergedBlobToLocal(merged)
            schedulePush()
            true
        } catch (_: Exception) {
            false
        }
    }
}
