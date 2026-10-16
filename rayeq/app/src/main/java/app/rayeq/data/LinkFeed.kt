package app.rayeq.data

import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object LinkFeed {
    const val LINK_TTL_MS = 15 * 60_000L   // 15 دقيقة للروابط المكتملة
    const val EMPTY_TTL_MS = 90_000L       // 90 ثانية للنتيجة الفارغة
    const val PAGE_TTL_MS = 45 * 60_000L   // 45 دقيقة للصفحة المطابقة

    fun reusable(
        completedAtMs: Long,
        hasLinks: Boolean,
        isRunning: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (isRunning) return true
        if (completedAtMs <= 0L) return false
        val age = nowMs - completedAtMs
        return if (hasLinks) age < LINK_TTL_MS else age < EMPTY_TTL_MS
    }

    class Entry {
        val links = CopyOnWriteArrayList<ExtractorLink>()
        val subs = CopyOnWriteArrayList<SubtitleFile>()
        @Volatile var isRunning: Boolean = true
        @Volatile var completedAtMs: Long = 0L
        private val linkListeners = CopyOnWriteArrayList<(ExtractorLink) -> Unit>()
        private val subListeners = CopyOnWriteArrayList<(SubtitleFile) -> Unit>()

        fun emitLink(link: ExtractorLink) {
            if (links.none { it.url == link.url }) {
                links.add(link)
                linkListeners.forEach { it(link) }
            }
        }

        fun emitSub(sub: SubtitleFile) {
            if (subs.none { it.url == sub.url }) {
                subs.add(sub)
                subListeners.forEach { it(sub) }
            }
        }

        fun subscribe(
            onLink: (ExtractorLink) -> Unit,
            onSub: (SubtitleFile) -> Unit
        ) {
            links.forEach(onLink)
            subs.forEach(onSub)
            if (isRunning) {
                linkListeners.add(onLink)
                subListeners.add(onSub)
            }
        }

        fun markDone(nowMs: Long = System.currentTimeMillis()) {
            isRunning = false
            completedAtMs = nowMs
            linkListeners.clear()
            subListeners.clear()
        }
    }

    private val feeds = ConcurrentHashMap<String, Entry>()
    private val pageCache = ConcurrentHashMap<String, Pair<String, Long>>()

    fun feedKey(provider: String, titleKey: String, season: Int?, episode: Int?): String =
        "$provider|$titleKey|${season ?: 0}|${episode ?: 0}"

    fun getValidFeed(key: String, nowMs: Long = System.currentTimeMillis()): Entry? {
        val e = feeds[key] ?: return null
        if (reusable(e.completedAtMs, e.links.isNotEmpty(), e.isRunning, nowMs)) {
            return e
        }
        feeds.remove(key)
        return null
    }

    fun putFeed(key: String, entry: Entry) {
        feeds[key] = entry
    }

    fun getCachedPageUrl(provider: String, titleKey: String, season: Int?, nowMs: Long = System.currentTimeMillis()): String? {
        val k = "$provider|$titleKey|${season ?: 0}"
        val pair = pageCache[k] ?: return null
        if (nowMs - pair.second < PAGE_TTL_MS) return pair.first
        pageCache.remove(k)
        return null
    }

    fun putCachedPageUrl(provider: String, titleKey: String, season: Int?, url: String, nowMs: Long = System.currentTimeMillis()) {
        pageCache["$provider|$titleKey|${season ?: 0}"] = url to nowMs
    }
}
