package com.lagradost.cloudstream3

import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList

enum class TvType {
    Movie,
    TvSeries,
    Anime,
    AnimeMovie,
    OVA,
    Cartoon,
    AsianDrama,
    Documentary,
    Music,
    Live,
    Torrent,
    Others
}

enum class DubStatus {
    Subbed,
    Dubbed,
    None
}

object Qualities {
    const val Unknown = 400
    const val P144 = 144
    const val P240 = 240
    const val P360 = 360
    const val P480 = 480
    const val P720 = 720
    const val P1080 = 1080
    const val P1440 = 1440
    const val P2160 = 2160
}

enum class ExtractorLinkType {
    VIDEO,
    M3U8,
    DASH
}

data class ExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String,
    val quality: Int = Qualities.Unknown,
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val extractorData: String? = null,
    val type: ExtractorLinkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
)

data class SubtitleFile(
    val lang: String,
    val url: String,
    val isZipUrl: Boolean = false
)

interface SearchResponse {
    var name: String
    var url: String
    var apiName: String
    var type: TvType?
    var posterUrl: String?
    var year: Int?
    var id: Int?
    var posterHeaders: Map<String, String>?
}

data class MovieSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Movie,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null
) : SearchResponse

data class TvSeriesSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.TvSeries,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    val episodes: Int? = null
) : SearchResponse

data class AnimeSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Anime,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    var dubStatus: EnumSet<DubStatus>? = null,
    var episodes: MutableMap<DubStatus, Int> = mutableMapOf()
) : SearchResponse

data class LiveSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Live,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null
) : SearchResponse

data class Episode(
    var data: String,
    var name: String? = null,
    var season: Int? = null,
    var episode: Int? = null,
    var posterUrl: String? = null,
    var rating: Int? = null,
    var description: String? = null,
    var date: Long? = null
)

interface LoadResponse {
    var name: String
    var url: String
    var apiName: String
    var type: TvType
    var posterUrl: String?
    var year: Int?
    var plot: String?
    var rating: Int?
    var tags: List<String>?
    var duration: Int?
    var posterHeaders: Map<String, String>?
    var tmdbId: Int?
    var imdbId: String?
}

data class MovieLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType = TvType.Movie,
    var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var tmdbId: Int? = null,
    override var imdbId: String? = null
) : LoadResponse

data class TvSeriesLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType = TvType.TvSeries,
    var episodes: List<Episode>,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var tmdbId: Int? = null,
    override var imdbId: String? = null
) : LoadResponse

data class AnimeLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType = TvType.Anime,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var tmdbId: Int? = null,
    override var imdbId: String? = null,
    var episodes: MutableMap<DubStatus, List<Episode>> = mutableMapOf()
) : LoadResponse

data class LiveStreamLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType = TvType.Live,
    var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var tmdbId: Int? = null,
    override var imdbId: String? = null
) : LoadResponse

data class MainPageRequest(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)

data class HomePageList(
    val name: String,
    val list: List<SearchResponse>,
    val isHorizontalImages: Boolean = false
)

data class HomePageResponse(
    val items: List<HomePageList>,
    val hasNext: Boolean = false
)

class ErrorLoadingException(message: String? = null) : Exception(message)

abstract class MainAPI {
    open var name: String = "NONE"
    open var mainUrl: String = "NONE"
    open var lang: String = "en"
    open val hasMainPage: Boolean = false
    open val hasQuickSearch: Boolean = false
    open val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)
    open val mainPage: List<MainPageRequest> = emptyList()
    var sourcePlugin: String? = null

    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null
    open suspend fun search(query: String): List<SearchResponse>? = null
    open suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)
    open suspend fun load(url: String): LoadResponse? = null
    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
}

abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean
    var sourcePlugin: String? = null

    open suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {}
}

val extractorApis: MutableList<ExtractorApi> = CopyOnWriteArrayList()

abstract class BasePlugin {
    var filename: String? = null
    abstract fun load()

    fun registerMainAPI(element: MainAPI) {
        element.sourcePlugin = this.filename
        APIHolder.addPluginMapping(element)
    }

    fun registerExtractorAPI(element: ExtractorApi) {
        element.sourcePlugin = this.filename
        extractorApis.add(element)
    }
}

object APIHolder {
    val allProviders: MutableList<MainAPI> = CopyOnWriteArrayList()
    var apis: List<MainAPI> = emptyList()

    @Synchronized
    fun addPluginMapping(api: MainAPI) {
        allProviders.removeAll { it.name == api.name && it.sourcePlugin == api.sourcePlugin }
        allProviders.add(api)
    }

    @Synchronized
    fun removePluginMapping(pluginPath: String) {
        allProviders.removeAll { it.sourcePlugin == pluginPath }
        extractorApis.removeAll { it.sourcePlugin == pluginPath }
    }

    @Synchronized
    fun initAll() {
        apis = allProviders.toList()
    }

    fun getApiFromNameNull(name: String?): MainAPI? {
        if (name == null) return null
        return apis.firstOrNull { it.name == name } ?: allProviders.firstOrNull { it.name == name }
    }
}
