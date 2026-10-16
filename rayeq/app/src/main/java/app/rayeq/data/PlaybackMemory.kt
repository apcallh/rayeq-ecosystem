package app.rayeq.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerPick(
    val source: String,
    val linkName: String,
    val quality: Int,
    val at: Long,
    val fails: Int = 0
)

@Serializable
data class TrackPick(
    val audioLang: String? = null,
    val audioLabel: String? = null,
    val subLang: String? = null,
    val subLabel: String? = null,
    val subsOff: Boolean = false,
    val at: Long = 0L
)

object PlaybackMemory {
    const val MAX_FAILS = 2
    const val MAX_RECENT = 8

    /**
     * 2160→0, 1440→1, 1080→2, 720→3, 480→4, 360→5, أقل→6, مجهول(0)→7
     */
    fun qualityRank(q: Int): Int = when {
        q <= 0 -> 7
        q >= 2160 -> 0
        q >= 1440 -> 1
        q >= 1080 -> 2
        q >= 720 -> 3
        q >= 480 -> 4
        q >= 360 -> 5
        else -> 6
    }

    fun memoryTier(pick: ServerPick?, source: String, linkName: String, quality: Int): Int {
        if (pick == null || pick.fails >= MAX_FAILS) return 3
        if (!pick.source.equals(source, ignoreCase = true)) return 3
        val cleanA = LinkQuality.stripQuality(pick.linkName)
        val cleanB = LinkQuality.stripQuality(linkName)
        if (cleanA.isNotEmpty() && cleanA == cleanB) return 0
        if (pick.quality > 0 && pick.quality == quality) return 1
        return 2
    }

    /**
     * مفتاح الترتيب (§9.3)، والأصغر يُشغَّل أولاً:
     * 0. السيرفر المثبّت يدوياً StickyServer.rank (مستوى >= 2)
     * 1. مجموعة اللغة: عربي = 1_000_000_000، غيره = 2_000_000_000
     * 2. الجودة: qualityRank (0..7) * 100_000_000
     * 3. التذكر التلقائي (داخل نفس الجودة فقط): (0..3) * 10_000_000
     * 4. ترتيب المدير: (0..9999) * 100
     * 5. حداثة المصدر في recentServers: (0..9)
     */
    fun key(
        isArabic: Boolean,
        quality: Int,
        memoryMatchTier: Int = 3,
        adminRank: Int = 9999,
        recentRank: Int = 9,
        stickyLevel: Int = 0
    ): Long {
        if (stickyLevel >= 2) {
            // المفضّل يتقدم على كل شيء: مستوى 4 → 100_000، مستوى 3 → 200_000، مستوى 2 → 300_000
            val stickyBucket = (5 - stickyLevel.coerceIn(2, 4)) * 100_000L
            val qRank = qualityRank(quality) * 1_000L
            return stickyBucket + qRank + adminRank.coerceIn(0, 999)
        }
        val langGroup = if (isArabic) 1_000_000_000L else 2_000_000_000L
        val qGroup = qualityRank(quality).toLong() * 100_000_000L
        val memGroup = memoryMatchTier.coerceIn(0, 3).toLong() * 10_000_000L
        val admGroup = adminRank.coerceIn(0, 9999).toLong() * 100L
        val recGroup = recentRank.coerceIn(0, 9).toLong()
        return langGroup + qGroup + memGroup + admGroup + recGroup
    }

    /**
     * إدراج رابط متأخر في الجزء الذي لم يُجرَّب من القائمة بشكل مستقر بعد العناصر المتساوية.
     */
    fun <T> insertAt(
        list: List<T>,
        newItem: T,
        triedUrls: Set<String>,
        urlOf: (T) -> String,
        keyOf: (T) -> Long
    ): Int {
        val newKey = keyOf(newItem)
        val firstUntriedIdx = list.indexOfFirst { urlOf(it) !in triedUrls }
        if (firstUntriedIdx < 0) return list.size
        for (i in firstUntriedIdx until list.size) {
            if (urlOf(list[i]) in triedUrls) continue
            if (newKey < keyOf(list[i])) {
                return i
            }
        }
        return list.size
    }

    fun onSuccess(
        currentPicks: Map<String, ServerPick>,
        recent: List<String>,
        titleKey: String,
        source: String,
        linkName: String,
        quality: Int,
        nowMs: Long
    ): Pair<Map<String, ServerPick>, List<String>> {
        val updatedPicks = currentPicks.toMutableMap()
        updatedPicks[titleKey] = ServerPick(
            source = source,
            linkName = linkName,
            quality = quality,
            at = nowMs,
            fails = 0
        )
        val updatedRecent = (listOf(source) + recent.filterNot { it.equals(source, ignoreCase = true) })
            .take(MAX_RECENT)
        return updatedPicks to updatedRecent
    }

    fun onFailure(
        currentPicks: Map<String, ServerPick>,
        titleKey: String,
        failedSource: String
    ): Map<String, ServerPick> {
        val existing = currentPicks[titleKey] ?: return currentPicks
        if (!existing.source.equals(failedSource, ignoreCase = true)) return currentPicks
        val nextFails = existing.fails + 1
        val updated = currentPicks.toMutableMap()
        if (nextFails >= MAX_FAILS) {
            updated.remove(titleKey)
        } else {
            updated[titleKey] = existing.copy(fails = nextFails)
        }
        return updated
    }
}
