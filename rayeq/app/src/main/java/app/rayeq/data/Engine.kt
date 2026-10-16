package app.rayeq.data

import android.util.Log
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

data class PlayRequest(
    val title: Title,
    val season: Int? = null,
    val episode: Int? = null,
    val absEpisode: Int? = null,
    val names: List<String> = emptyList(),
    val seasonYears: List<Int> = emptyList(),
    val seasonCount: Int = 1,
    val seasonEpisodes: Int? = null,
    val localPath: String? = null,
    val live: LiveItem? = null
)

data class Server(
    val n: Int,
    val link: ExtractorLink,
    val source: String
)

object Engine {
    private const val TAG = "RayeqEngine"
    const val SEARCH_TIMEOUT_MS = 12_000L
    const val LOAD_TIMEOUT_MS = 18_000L
    const val LINKS_TIMEOUT_MS = 45_000L
    const val PREFETCH_BEFORE_END_MS = 180_000L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // مجمّعان منفصلان: العربية بعرض 16 والأجنبية بعرض 6 (§8.2)
    private val arabicPool = Semaphore(16)
    private val foreignPool = Semaphore(6)

    // قوالب JSON للمصادر المعتمدة على المعرّف (ID sources)
    private val idTemplates = ConcurrentHashMap<String, String>()

    private val SITE_NOISE_RE = Regex(
        "\\b(مسلسل|فيلم|انمي|أنمي|مترجم|مدبلج|اون\\s*لاين|أونلاين|اونلاين|كامل|بجودة|عالية|حصريا|حصرياً|مشاهدة|تحميل|الموسم|موسم|الحلقة|حلقة|season|episode|ep|hd|fhd|4k|1080p|720p|bluray|web-dl|webrip|tv\\s*series|movie)\\b",
        RegexOption.IGNORE_CASE
    )
    private val SEASON_IN_NAME_RE = Regex(
        "(?:الموسم|موسم|season|\\bs)\\s*0*(\\d{1,2})\\b",
        RegexOption.IGNORE_CASE
    )
    private val EPISODE_IN_NAME_RE = Regex(
        "(?:الحلقة|حلقة|episode|\\bep|\\be)\\s*0*(\\d{1,4})\\b",
        RegexOption.IGNORE_CASE
    )
    private val LIVE_ACTION_RE = Regex(
        "(live\\s*-?\\s*action|لايف\\s*اكشن|لايف\\s*أكشن|واقعي)",
        RegexOption.IGNORE_CASE
    )

    fun cleanTitle(raw: String): String {
        val ascii = ArabicDigits.toAsciiDigits(raw).lowercase()
        return ascii
            .replace(SEASON_IN_NAME_RE, " ")
            .replace(EPISODE_IN_NAME_RE, " ")
            .replace(SITE_NOISE_RE, " ")
            .replace(Regex("\\b(19\\d{2}|20\\d{2})\\b"), " ")
            .replace(Regex("[^a-z0-9\u0621-\u064A]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun splitDualScript(raw: String): List<String> {
        val cleaned = cleanTitle(raw)
        if (cleaned.isEmpty()) return emptyList()
        val arPart = cleaned.replace(Regex("[a-z]+"), " ").replace(Regex("\\s+"), " ").trim()
        val latPart = cleaned.replace(Regex("[\u0621-\u064A]+"), " ").replace(Regex("\\s+"), " ").trim()
        return listOf(cleaned, arPart, latPart).filter { it.length >= 2 }.distinct()
    }

    fun nameScore(candidateRaw: String, queryNames: List<String>): Int {
        val candParts = splitDualScript(candidateRaw)
        if (candParts.isEmpty()) return 0
        var best = 0
        for (qRaw in queryNames) {
            for (q in splitDualScript(qRaw)) {
                for (c in candParts) {
                    if (c == q) return 100
                    val r = FuzzySearch.ratio(c, q)
                    // tokenSort يُستخدم فقط إذا كان فرق الطول 3 أحرف أو أقل، ولا يُستخدم partial match أبداً
                    val ts = if (abs(c.length - q.length) <= 3) {
                        FuzzySearch.tokenSortRatio(c, q)
                    } else 0
                    val m = max(r, ts)
                    if (m > best) best = m
                }
            }
        }
        return best
    }

    fun nameSaysType(rawName: String): String? {
        val s = ArabicDigits.toAsciiDigits(rawName).lowercase()
        if (SEASON_IN_NAME_RE.containsMatchIn(s) ||
            EPISODE_IN_NAME_RE.containsMatchIn(s) ||
            Regex("\\b(مسلسل|انمي|أنمي|tv\\s*series|series|season)\\b").containsMatchIn(s)
        ) {
            return "tv"
        }
        if (Regex("\\b(فيلم|فلم|movie|film)\\b").containsMatchIn(s)) {
            return "movie"
        }
        return null
    }

    /**
     * تقييم صارم لمطابقة العنوان (§8.4):
     * العتبة: 88 إذا كانت السنتان معروفتين، و92 إذا لم تُعرف إحداهما.
     */
    fun scoreTitle(
        resultName: String,
        resultYear: Int?,
        resultTvType: TvType?,
        req: PlayRequest
    ): Pair<Int, Boolean> {
        val base = nameScore(resultName, req.names.ifEmpty { listOf(req.title.name) })
        var score = base
        val asciiName = ArabicDigits.toAsciiDigits(resultName).lowercase()

        // 1. السنة
        val validYears = if (req.title.isMovie) {
            listOfNotNull(req.title.year)
        } else {
            (req.seasonYears + listOfNotNull(req.title.year)).distinct()
        }
        val bothYearsKnown = resultYear != null && resultYear > 1900 && validYears.isNotEmpty()
        if (bothYearsKnown && resultYear != null) {
            val minDiff = validYears.minOf { abs(it - resultYear) }
            score += when (minDiff) {
                0 -> 6
                1 -> 1
                else -> -40
            }
        }

        // 2. النوع (ما يقوله الاسم مقدم على حقل النوع في الموقع)
        val inferredType = nameSaysType(resultName) ?: when (resultTvType) {
            TvType.Movie, TvType.AnimeMovie -> "movie"
            TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.AsianDrama, TvType.Cartoon -> "tv"
            else -> null
        }
        val targetType = if (req.title.isMovie) "movie" else "tv"
        if (inferredType != null && inferredType != targetType) {
            score -= 35
        }

        // 3. أنمي مقابل نسخة واقعية (live-action)
        if (req.title.isAnime) {
            val taggedAnime = resultTvType == TvType.Anime ||
                resultTvType == TvType.AnimeMovie ||
                resultTvType == TvType.OVA ||
                asciiName.contains("انمي") ||
                asciiName.contains("أنمي") ||
                asciiName.contains("anime")
            if (taggedAnime) score += 4
            if (LIVE_ACTION_RE.containsMatchIn(asciiName)) score -= 40
            if (!taggedAnime && resultYear != null && req.title.year != null && resultYear - req.title.year > 3) {
                score -= 25
            }
            if (!taggedAnime && asciiName.trimStart().startsWith("مسلسل")) {
                score -= 10
            }
        }

        // 4. الموسم والحلقات في الاسم
        val mentionedSeason = SEASON_IN_NAME_RE.find(asciiName)?.groupValues?.get(1)?.toIntOrNull()
        val hasEpisodeInName = EPISODE_IN_NAME_RE.containsMatchIn(asciiName)
        if (req.title.isMovie) {
            if (mentionedSeason != null || hasEpisodeInName) {
                score -= 20
            }
        } else {
            val targetS = req.season ?: 1
            if (mentionedSeason != null && mentionedSeason != targetS) {
                score -= 50
            }
            val mentionedEp = EPISODE_IN_NAME_RE.find(asciiName)?.groupValues?.get(1)?.toIntOrNull()
            if (mentionedEp != null && req.episode != null && mentionedEp != req.episode && mentionedEp != req.absEpisode) {
                score -= 50
            }
        }

        val threshold = if (bothYearsKnown) 88 else 92
        return score to (score >= threshold)
    }

    fun matchIdSourceUrl(rawUrl: String, req: PlayRequest): Boolean? {
        val t = rawUrl.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        return try {
            val obj = JSONObject(t)
            val hasTypeField = obj.has("type") || obj.has("mediaType") || obj.has("media_type")
            val tmdbVal = when {
                obj.has("tmdbId") -> obj.optInt("tmdbId", -1)
                obj.has("tmdb_id") -> obj.optInt("tmdb_id", -1)
                obj.has("tmdb") -> obj.optInt("tmdb", -1)
                hasTypeField && obj.has("id") -> obj.optInt("id", -1)
                else -> -1
            }
            val imdbVal = obj.optString("imdbId", obj.optString("imdb_id", obj.optString("imdb", "")))
            if (tmdbVal <= 0 && imdbVal.isBlank()) return null

            val tmdbMatches = tmdbVal > 0 && tmdbVal == req.title.id
            val imdbMatches = imdbVal.isNotBlank() && req.title.imdbId != null && imdbVal.equals(req.title.imdbId, ignoreCase = true)
            tmdbMatches || imdbMatches
        } catch (_: Exception) {
            null
        }
    }

    /**
     * ترتيب البحث (§8.2): العربية أولاً، ثم المتذكَّرة أو الحديثة، ثم ترتيب المدير.
     */
    fun <T> searchOrder(
        providers: List<T>,
        nameOf: (T) -> String,
        isArabicOf: (T) -> Boolean,
        adminRankOf: (T) -> Int,
        rememberedSource: String? = null,
        recentSources: List<String> = emptyList()
    ): List<T> {
        return providers.sortedWith(
            compareBy<T>(
                { !isArabicOf(it) },
                {
                    val nm = nameOf(it)
                    when {
                        rememberedSource != null && nm.equals(rememberedSource, ignoreCase = true) -> 0
                        recentSources.any { r -> r.equals(nm, ignoreCase = true) } ->
                            1 + recentSources.indexOfFirst { r -> r.equals(nm, ignoreCase = true) }
                        else -> 100
                    }
                },
                { adminRankOf(it) }
            )
        )
    }

    /**
     * ترتيب السيرفر المثبّت يدوياً (§8.2): مزوّد السيرفر المثبّت أولاً، ثم searchOrder.
     */
    fun <T> stickyOrder(
        providers: List<T>,
        stickySource: String?,
        nameOf: (T) -> String,
        isArabicOf: (T) -> Boolean,
        adminRankOf: (T) -> Int,
        rememberedSource: String? = null,
        recentSources: List<String> = emptyList()
    ): List<T> {
        val base = searchOrder(providers, nameOf, isArabicOf, adminRankOf, rememberedSource, recentSources)
        if (stickySource.isNullOrBlank()) return base
        val sticky = base.filter { nameOf(it).equals(stickySource, ignoreCase = true) }
        val rest = base.filterNot { nameOf(it).equals(stickySource, ignoreCase = true) }
        return sticky + rest
    }

    fun sources(): List<MainAPI> = APIHolder.apis.filter { SourceConfig.allowed(it) }

    fun mediaSources(): List<MainAPI> = sources().filter { api ->
        !SourceConfig.isLive(api) && !SourceConfig.isTool(api)
    }

    fun findServers(
        req: PlayRequest,
        stickySource: String? = null,
        rememberedSource: String? = null,
        recentSources: List<String> = emptyList(),
        onServer: (Server) -> Unit,
        onSub: (SubtitleFile) -> Unit,
        onArabicPending: (Int) -> Unit,
        onSourceDone: (String, Int) -> Unit
    ): Job = scope.launch {
        SourceConfig.awaitReady(45_000L)
        val allMedia = mediaSources()
        val ordered = stickyOrder(
            providers = allMedia,
            stickySource = stickySource,
            nameOf = { it.name },
            isArabicOf = { SourceConfig.isArabic(it) },
            adminRankOf = { SourceConfig.rank(it) },
            rememberedSource = rememberedSource,
            recentSources = recentSources
        )

        val arabicTotal = ordered.count { SourceConfig.isArabic(it) }
        val arabicPending = AtomicInteger(arabicTotal)
        onArabicPending(arabicPending.get())

        val serverCounter = AtomicInteger(0)

        val jobs = ordered.map { api ->
            val isStickyProvider = !stickySource.isNullOrBlank() && api.name.equals(stickySource, ignoreCase = true)
            val isAr = SourceConfig.isArabic(api)
            launch {
                var emittedCount = 0
                try {
                    val runBlock: suspend () -> Unit = {
                        emittedCount = querySingleProvider(api, req, serverCounter, onServer, onSub)
                    }
                    // مزوّد السيرفر المثبّت يُشغَّل قبل الجميع وخارج المجمّعات
                    if (isStickyProvider) {
                        runBlock()
                    } else if (isAr) {
                        arabicPool.withPermit { runBlock() }
                    } else {
                        foreignPool.withPermit { runBlock() }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Provider ${api.name} error: ${t.message}")
                } finally {
                    if (isAr) {
                        onArabicPending(arabicPending.decrementAndGet().coerceAtLeast(0))
                    }
                    onSourceDone(api.name, emittedCount)
                }
            }
        }
        jobs.joinAll()
    }

    private suspend fun querySingleProvider(
        api: MainAPI,
        req: PlayRequest,
        counter: AtomicInteger,
        onServer: (Server) -> Unit,
        onSub: (SubtitleFile) -> Unit
    ): Int {
        if (!SourceConfig.allowed(api)) return 0
        val fKey = LinkFeed.feedKey(api.name, req.title.key, req.season, req.episode)
        val existingFeed = LinkFeed.getValidFeed(fKey)
        if (existingFeed != null) {
            var count = 0
            existingFeed.subscribe(
                onLink = { link ->
                    count++
                    onServer(Server(counter.incrementAndGet(), link, api.name))
                },
                onSub = onSub
            )
            return count
        }

        val feed = LinkFeed.Entry()
        LinkFeed.putFeed(fKey, feed)
        var count = 0
        feed.subscribe(
            onLink = { link ->
                count++
                onServer(Server(counter.incrementAndGet(), link, api.name))
            },
            onSub = onSub
        )

        try {
            var pageUrl = LinkFeed.getCachedPageUrl(api.name, req.title.key, req.season)
            if (pageUrl == null) {
                val queries = req.names.ifEmpty { listOf(req.title.name) }.distinct().take(4)
                val allSearchResults = mutableListOf<SearchResponse>()

                // الاسم الأول يُبحث أولاً
                val firstRes = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    api.search(queries.first())
                }.orEmpty()
                allSearchResults.addAll(firstRes)

                // الأسماء من الثاني إلى الرابع تُبحث بالتوازي إذا لم نجد مطابقة قوية
                val hasStrongFirst = firstRes.any {
                    matchIdSourceUrl(it.url, req) == true ||
                        scoreTitle(it.name, it.year, it.type, req).second
                }
                if (!hasStrongFirst && queries.size > 1) {
                    coroutineScope {
                        queries.drop(1).map { q ->
                            async {
                                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { api.search(q) }.orEmpty()
                            }
                        }.awaitAll().forEach { allSearchResults.addAll(it) }
                    }
                }

                // حفظ قالب JSON لمصادر المعرّف إن وُجد
                allSearchResults.firstOrNull { it.url.trim().startsWith("{") }?.let {
                    idTemplates[api.name] = it.url.trim()
                }

                val matchedResult = allSearchResults
                    .mapNotNull { sr ->
                        val idMatch = matchIdSourceUrl(sr.url, req)
                        if (idMatch == true) {
                            sr to 150
                        } else if (idMatch == false) {
                            null
                        } else {
                            val (sc, ok) = scoreTitle(sr.name, sr.year, sr.type, req)
                            if (ok) sr to sc else null
                        }
                    }
                    .maxByOrNull { it.second }
                    ?.first

                pageUrl = matchedResult?.url
                if (pageUrl != null) {
                    LinkFeed.putCachedPageUrl(api.name, req.title.key, req.season, pageUrl)
                }
            }

            if (pageUrl == null) return 0

            val loadResp = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                api.load(pageUrl)
            } ?: return 0

            // التحقق من المعرفات بعد load() ورفض صفحة الأنمي الواقعية القصيرة (§8.4 البند 3)
            if (loadResp.tmdbId != null && loadResp.tmdbId != req.title.id) return 0
            if (!loadResp.imdbId.isNullOrBlank() &&
                !req.title.imdbId.isNullOrBlank() &&
                !loadResp.imdbId.equals(req.title.imdbId, ignoreCase = true)
            ) {
                return 0
            }

            val dataToLoad: String = when (loadResp) {
                is MovieLoadResponse -> if (req.title.isMovie) loadResp.dataUrl else return 0
                is LiveStreamLoadResponse -> loadResp.dataUrl
                is TvSeriesLoadResponse -> {
                    if (req.title.isMovie) return 0
                    val targetS = req.season ?: 1
                    val targetE = req.episode ?: 1
                    val seasonEps = loadResp.episodes.filter { (it.season ?: 1) == targetS }
                    if (req.title.isAnime &&
                        loadResp.type != TvType.Anime &&
                        req.seasonEpisodes != null &&
                        req.seasonEpisodes >= 12 &&
                        seasonEps.isNotEmpty() &&
                        seasonEps.size < (req.seasonEpisodes * 0.75).toInt()
                    ) {
                        return 0
                    }
                    val chosen = EpisodeMatch.choose(
                        episodes = loadResp.episodes.map {
                            EpCandidate(it.data, it.season, it.episode, it.name)
                        },
                        targetSeason = targetS,
                        targetEpisode = targetE,
                        targetAbsEpisode = req.absEpisode,
                        tmdbSeasonCount = req.seasonCount,
                        tmdbSeasonEpisodes = req.seasonEpisodes
                    ) ?: return 0
                    chosen.data
                }
                is AnimeLoadResponse -> {
                    if (req.title.isMovie) return 0
                    val allEps = loadResp.episodes[DubStatus.Subbed].orEmpty() +
                        loadResp.episodes[DubStatus.Dubbed].orEmpty() +
                        loadResp.episodes[DubStatus.None].orEmpty()
                    val chosen = EpisodeMatch.choose(
                        episodes = allEps.map {
                            EpCandidate(it.data, it.season, it.episode, it.name)
                        },
                        targetSeason = req.season ?: 1,
                        targetEpisode = req.episode ?: 1,
                        targetAbsEpisode = req.absEpisode,
                        tmdbSeasonCount = req.seasonCount,
                        tmdbSeasonEpisodes = req.seasonEpisodes
                    ) ?: return 0
                    chosen.data
                }
                else -> return 0
            }

            withTimeoutOrNull(LINKS_TIMEOUT_MS) {
                api.loadLinks(
                    data = dataToLoad,
                    isCasting = false,
                    subtitleCallback = { sub -> feed.emitSub(sub) },
                    callback = { link ->
                        if (SourceConfig.allowed(api)) {
                            feed.emitLink(link)
                        }
                    }
                )
            }
            return count
        } finally {
            feed.markDone()
        }
    }

    fun prefetch(
        nextReq: PlayRequest,
        recentSources: List<String> = emptyList(),
        stickySource: String? = null
    ) {
        if (!nextReq.localPath.isNullOrBlank()) return
        findServers(
            req = nextReq,
            stickySource = stickySource,
            recentSources = recentSources,
            onServer = {},
            onSub = {},
            onArabicPending = {},
            onSourceDone = { _, _ -> }
        )
    }
}
