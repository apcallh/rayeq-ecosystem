package app.rayeq.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class TmdbEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String?,
    val runtimeMin: Int?,
    val airDate: String?
)

data class TmdbSeasonInfo(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val airYear: Int?
)

data class TmdbDetails(
    val title: Title,
    val searchNames: List<String>,
    val seasons: List<TmdbSeasonInfo>,
    val similar: List<Title>,
    val cast: List<Pair<Int, String>>,
    val trailerYoutubeKey: String?
)

object Tmdb {
    private const val IMG_W500 = "https://image.tmdb.org/t/p/w500"
    private const val IMG_W780 = "https://image.tmdb.org/t/p/w780"
    private val memCache = ConcurrentHashMap<String, String>()
    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "tmdb_disk_cache").apply { mkdirs() }
    }

    private suspend fun getCachedOrFetch(pathWithQuery: String, fresh: Boolean = false): JSONObject? =
        withContext(Dispatchers.IO) {
            if (!fresh) {
                memCache[pathWithQuery]?.let {
                    return@withContext try { JSONObject(it) } catch (_: Exception) { null }
                }
            }
            val diskFile = cacheDir?.let { File(it, "${pathWithQuery.hashCode()}.json") }
            val netText = Net.get("/v1/tmdb/$pathWithQuery")
            if (netText != null) {
                memCache[pathWithQuery] = netText
                try { diskFile?.writeText(netText) } catch (_: Exception) {}
                return@withContext try { JSONObject(netText) } catch (_: Exception) { null }
            }
            // إرجاع النسخة القديمة عند الفشل (stale fallback)
            if (diskFile != null && diskFile.exists()) {
                return@withContext try {
                    val stale = diskFile.readText()
                    memCache[pathWithQuery] = stale
                    JSONObject(stale)
                } catch (_: Exception) { null }
            }
            null
        }

    fun parseTitleItem(enObj: JSONObject, arObj: JSONObject? = null, forcedType: String? = null): Title {
        val id = enObj.optInt("id")
        val type = forcedType
            ?: enObj.optString("media_type").takeIf { it == "movie" || it == "tv" }
            ?: if (enObj.has("first_air_date") || enObj.has("name")) "tv" else "movie"

        // القاعدة الذهبية رقم 4: أسماء الأعمال تُعرض بالإنجليزية فقط (en-US)، والنبذة والملصقات عربية إن وُجدت
        val enName = enObj.optString("title").ifBlank { enObj.optString("name", "Untitled") }
        val origName = enObj.optString("original_title").ifBlank { enObj.optString("original_name", enName) }
        val arName = arObj?.optString("title")?.ifBlank { arObj.optString("name", "") } ?: ""

        val arOverview = arObj?.optString("overview")?.trim().orEmpty()
        val enOverview = enObj.optString("overview").trim()
        val overview = arOverview.ifBlank { enOverview }

        val posterPath = arObj?.optString("poster_path")?.takeIf { it.startsWith("/") }
            ?: enObj.optString("poster_path").takeIf { it.startsWith("/") }
        val backdropPath = enObj.optString("backdrop_path").takeIf { it.startsWith("/") }

        val dateStr = enObj.optString("release_date").ifBlank { enObj.optString("first_air_date", "") }
        val year = dateStr.take(4).toIntOrNull()

        val genreIds = enObj.optJSONArray("genre_ids")
        var hasAnimationGenre = false
        if (genreIds != null) {
            for (i in 0 until genreIds.length()) {
                if (genreIds.optInt(i) == 16) hasAnimationGenre = true
            }
        }
        val origLang = enObj.optString("original_language", "")
        val isAnime = hasAnimationGenre && origLang == "ja"

        return Title(
            id = id,
            type = type,
            name = enName,
            originalName = origName,
            arabicName = arName,
            overview = overview,
            posterUrl = posterPath?.let { "$IMG_W500$it" },
            backdropUrl = backdropPath?.let { "$IMG_W780$it" },
            year = year,
            voteAverage = enObj.optDouble("vote_average", 0.0),
            isAnime = isAnime
        )
    }

    /**
     * القوائم تُقرأ بـen-US للأسماء وبـar للملصقات والنبذة ثم تُدمجان (§15)
     */
    suspend fun fetchList(path: String, extraQuery: String = "", forcedType: String? = null): List<Title> =
        coroutineScope {
            val sep = if (extraQuery.isBlank()) "" else "&$extraQuery"
            val enDef = async { getCachedOrFetch("$path?language=en-US$sep") }
            val arDef = async { getCachedOrFetch("$path?language=ar$sep") }
            val enJson = enDef.await() ?: return@coroutineScope emptyList()
            val arJson = arDef.await()

            val enArr = enJson.optJSONArray("results") ?: JSONArray()
            val arArr = arJson?.optJSONArray("results") ?: JSONArray()
            val arById = mutableMapOf<Int, JSONObject>()
            for (i in 0 until arArr.length()) {
                val o = arArr.getJSONObject(i)
                arById[o.optInt("id")] = o
            }

            val out = mutableListOf<Title>()
            for (i in 0 until enArr.length()) {
                val enItem = enArr.getJSONObject(i)
                val mt = enItem.optString("media_type")
                if (mt == "person") continue
                val id = enItem.optInt("id")
                out.add(parseTitleItem(enItem, arById[id], forcedType))
            }
            out
        }

    suspend fun fetchDetails(type: String, id: Int): TmdbDetails? = coroutineScope {
        val enDef = async {
            getCachedOrFetch(
                "$type/$id?language=en-US&append_to_response=external_ids,alternative_titles,translations,images,videos,similar,recommendations,credits"
            )
        }
        val arDef = async {
            getCachedOrFetch("$type/$id?language=ar")
        }
        val en = enDef.await() ?: return@coroutineScope null
        val ar = arDef.await()

        val baseTitle = parseTitleItem(en, ar, type)
        val imdbId = en.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }

        val genresArr = ar?.optJSONArray("genres") ?: en.optJSONArray("genres") ?: JSONArray()
        val genres = (0 until genresArr.length()).mapNotNull {
            genresArr.getJSONObject(it).optString("name").takeIf { s -> s.isNotBlank() }
        }
        val isAnime = baseTitle.isAnime ||
            ( (en.optJSONArray("genres")?.let { g ->
                (0 until g.length()).any { g.getJSONObject(it).optInt("id") == 16 }
            } == true) && en.optString("original_language") == "ja" )

        // ترتيب أسماء البحث المخفية: الإنجليزي، الأصلي، العربي، البدائل
        val searchNames = mutableListOf<String>()
        if (baseTitle.name.isNotBlank()) searchNames.add(baseTitle.name)
        if (baseTitle.originalName.isNotBlank()) searchNames.add(baseTitle.originalName)
        if (baseTitle.arabicName.isNotBlank()) searchNames.add(baseTitle.arabicName)
        val altArr = en.optJSONObject("alternative_titles")?.let {
            it.optJSONArray("titles") ?: it.optJSONArray("results")
        } ?: JSONArray()
        for (i in 0 until minOf(altArr.length(), 6)) {
            val t = altArr.getJSONObject(i).optString("title")
            if (t.isNotBlank()) searchNames.add(t)
        }

        // المواسم العادية (الموسم 0 Specials مخفي في الواجهة — §8.4)
        val seasonsArr = en.optJSONArray("seasons") ?: JSONArray()
        val seasons = mutableListOf<TmdbSeasonInfo>()
        for (i in 0 until seasonsArr.length()) {
            val s = seasonsArr.getJSONObject(i)
            val sNum = s.optInt("season_number", 0)
            if (sNum <= 0) continue
            val epCnt = s.optInt("episode_count", 0)
            val airY = s.optString("air_date").take(4).toIntOrNull()
            seasons.add(
                TmdbSeasonInfo(
                    seasonNumber = sNum,
                    name = "الموسم ${sNum.toString()}",
                    episodeCount = epCnt,
                    airYear = airY
                )
            )
        }

        val simArr = en.optJSONObject("recommendations")?.optJSONArray("results")
            ?: en.optJSONObject("similar")?.optJSONArray("results")
            ?: JSONArray()
        val similar = (0 until minOf(simArr.length(), 15)).map {
            parseTitleItem(simArr.getJSONObject(it), null, null)
        }

        val castArr = en.optJSONObject("credits")?.optJSONArray("cast") ?: JSONArray()
        val cast = (0 until minOf(castArr.length(), 15)).map {
            val c = castArr.getJSONObject(it)
            c.optInt("id") to c.optString("name")
        }

        val vidsArr = en.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
        var trailerKey: String? = null
        for (i in 0 until vidsArr.length()) {
            val v = vidsArr.getJSONObject(i)
            if (v.optString("site").equals("YouTube", ignoreCase = true) &&
                v.optString("type").equals("Trailer", ignoreCase = true)
            ) {
                trailerKey = v.optString("key")
                break
            }
        }

        TmdbDetails(
            title = baseTitle.copy(
                imdbId = imdbId,
                genres = genres,
                isAnime = isAnime,
                runtimeMin = en.optInt("runtime", 0).takeIf { it > 0 }
            ),
            searchNames = searchNames.distinct(),
            seasons = seasons,
            similar = similar,
            cast = cast,
            trailerYoutubeKey = trailerKey
        )
    }

    /**
     * جلب حلقات الموسم مع إصلاح خطأ «لا توجد حلقات» التاريخي (§14.6):
     * - لا تخزّن نتيجة فارغة أبداً.
     * - أعد المحاولة 3 مرات بجلب جديد.
     * - اعرض قائمة بديلة «الحلقة 1…N» من عدد حلقات الموسم إذا استمر الفشل.
     */
    suspend fun seasonEpisodes(
        tvId: Int,
        seasonNumber: Int,
        expectedEpisodeCount: Int = 0
    ): List<TmdbEpisode> {
        val path = "tv/$tvId/season/$seasonNumber?language=ar"
        for (attempt in 1..3) {
            val json = getCachedOrFetch(path, fresh = attempt > 1)
            val epsArr = json?.optJSONArray("episodes")
            if (epsArr != null && epsArr.length() > 0) {
                val list = mutableListOf<TmdbEpisode>()
                for (i in 0 until epsArr.length()) {
                    val e = epsArr.getJSONObject(i)
                    val epNum = e.optInt("episode_number", i + 1)
                    val still = e.optString("still_path").takeIf { it.startsWith("/") }
                    list.add(
                        TmdbEpisode(
                            seasonNumber = seasonNumber,
                            episodeNumber = epNum,
                            name = e.optString("name").ifBlank { "الحلقة $epNum" },
                            overview = e.optString("overview", ""),
                            stillUrl = still?.let { "$IMG_W500$it" },
                            runtimeMin = e.optInt("runtime", 0).takeIf { it > 0 },
                            airDate = e.optString("air_date").takeIf { it.isNotBlank() }
                        )
                    )
                }
                return list
            }
            // مسح أي نتيجة فارغة من الذاكرة قبل إعادة المحاولة
            memCache.remove(path)
            cacheDir?.let { File(it, "${path.hashCode()}.json").delete() }
            if (attempt < 3) delay(400L * attempt)
        }

        // قائمة بديلة «الحلقة 1…N» من عدد حلقات الموسم
        if (expectedEpisodeCount > 0) {
            return (1..expectedEpisodeCount).map { epNum ->
                TmdbEpisode(
                    seasonNumber = seasonNumber,
                    episodeNumber = epNum,
                    name = "الحلقة $epNum",
                    overview = "",
                    stillUrl = null,
                    runtimeMin = null,
                    airDate = null
                )
            }
        }
        return emptyList()
    }

    suspend fun searchWithSmartFirst(query: String, type: String = "multi"): List<Title> = coroutineScope {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // البحث الذكي أولاً بحد 6 ثوانٍ، ثم نتائج TMDB العادية (§14.5)
        val smartDef = async {
            kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                Net.get("/v1/smart-search?q=$encoded&type=$type")
            }
        }
        val tmdbDef = async {
            fetchList("search/$type", "query=$encoded")
        }

        val smartItems = mutableListOf<Title>()
        val smartRaw = smartDef.await()
        if (smartRaw != null) {
            try {
                val arr = JSONObject(smartRaw).optJSONArray("results") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    smartItems.add(
                        Title(
                            id = o.optInt("id"),
                            type = o.optString("media_type", "tv"),
                            name = o.optString("title", "Untitled"),
                            originalName = o.optString("original_title", ""),
                            overview = o.optString("overview", ""),
                            posterUrl = o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "$IMG_W500$it" },
                            backdropUrl = o.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "$IMG_W780$it" },
                            year = if (o.isNull("year")) null else o.optInt("year"),
                            voteAverage = o.optDouble("vote_average", 0.0)
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        val regularItems = tmdbDef.await()
        (smartItems + regularItems).distinctBy { it.key }
    }
}
