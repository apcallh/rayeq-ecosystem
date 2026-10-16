package app.rayeq.data

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.*

data class LiveItem(
    val providerName: String,
    val name: String,
    val url: String,
    val posterUrl: String?,
    val posterHeaders: Map<String, String>?
) {
    val fallbackInitial: String
        get() {
            val clean = name.trim().removePrefix("قناة").trim().removePrefix("ال")
            return clean.firstOrNull()?.toString() ?: "م"
        }
}

data class LiveProviderSection(
    val providerName: String,
    val isArabic: Boolean,
    val rows: List<Pair<String, List<LiveItem>>>
)

object Live {
    fun liveSources(): List<MainAPI> {
        val all = Engine.sources().filter { SourceConfig.isLive(it) && !SourceConfig.isTool(it) }
        // العربية أولاً، ثم ترتيب المدير (§12)
        return all.sortedWith(
            compareBy<MainAPI>(
                { !SourceConfig.isArabic(it) },
                { SourceConfig.rank(it) }
            )
        )
    }

    private fun filterLiveItems(providerName: String, raw: List<SearchResponse>): List<LiveItem> {
        val anyTyped = raw.any { it.type != null }
        val filtered = if (anyTyped) raw.filter { it.type == TvType.Live } else raw
        return filtered.map {
            LiveItem(
                providerName = providerName,
                name = it.name,
                url = it.url,
                posterUrl = it.posterUrl,
                posterHeaders = it.posterHeaders
            )
        }
    }

    suspend fun loadSections(): List<LiveProviderSection> = coroutineScope {
        liveSources().map { api ->
            async(Dispatchers.IO) {
                val rows = mutableListOf<Pair<String, List<LiveItem>>>()
                try {
                    for (mp in api.mainPage) {
                        val resp = withTimeoutOrNull(12_000L) {
                            api.getMainPage(1, mp)
                        } ?: continue
                        for (homeList in resp.items) {
                            val items = filterLiveItems(api.name, homeList.list)
                            if (items.isNotEmpty()) {
                                rows.add(homeList.name to items)
                            }
                        }
                    }
                } catch (_: Exception) {}
                LiveProviderSection(
                    providerName = api.name,
                    isArabic = SourceConfig.isArabic(api),
                    rows = rows
                )
            }
        }.awaitAll().filter { it.rows.isNotEmpty() }
    }

    suspend fun searchLive(query: String): List<LiveProviderSection> = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        liveSources().map { api ->
            async(Dispatchers.IO) {
                val list = try {
                    withTimeoutOrNull(12_000L) { api.search(q) }.orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                val items = filterLiveItems(api.name, list)
                    .filter { it.name.contains(q, ignoreCase = true) || list.isNotEmpty() }
                LiveProviderSection(
                    providerName = api.name,
                    isArabic = SourceConfig.isArabic(api),
                    rows = if (items.isNotEmpty()) listOf("نتائج البحث" to items) else emptyList()
                )
            }
        }.awaitAll().filter { it.rows.isNotEmpty() }
    }
}
