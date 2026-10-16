package app.rayeq.data

import kotlinx.serialization.Serializable

@Serializable
data class ListPos(
    val season: Int = 1,
    val range: Int = -1,
    val absRange: Int = -1,
    val tab: Int = 0,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val at: Long = 0L
)

object ListMemory {
    const val PAGE_SIZE = 40
    const val SAVE_DEBOUNCE_MS = 700L

    fun rangeForEpisodeIndex(zeroBasedIndex: Int): Int {
        if (zeroBasedIndex < 0) return 0
        return zeroBasedIndex / PAGE_SIZE
    }

    /**
     * استعادة الموسم (§10.2):
     * المحفوظ، إلا إذا شاهد المستخدم بعد الحفظ حلقة من موسم آخر فيُفتح ذلك الموسم.
     * وإذا لم يوجد محفوظ: آخر موسم شوهد، وإلا الأول.
     */
    fun restoreSeason(
        saved: ListPos?,
        lastWatchedSeason: Int?,
        lastWatchedAt: Long,
        availableSeasons: List<Int>
    ): Int {
        val defaultSeason = availableSeasons.firstOrNull { it > 0 } ?: 1
        if (saved != null && saved.season in availableSeasons) {
            if (lastWatchedSeason != null &&
                lastWatchedSeason in availableSeasons &&
                lastWatchedAt > saved.at
            ) {
                return lastWatchedSeason
            }
            return saved.season
        }
        if (lastWatchedSeason != null && lastWatchedSeason in availableSeasons) {
            return lastWatchedSeason
        }
        return defaultSeason
    }

    fun restoreRange(
        saved: ListPos?,
        targetSeason: Int,
        lastWatchedSeason: Int?,
        lastWatchedEpisodeZeroIdx: Int?,
        lastWatchedAt: Long,
        totalEpisodesInSeason: Int
    ): Int {
        val maxRange = ((totalEpisodesInSeason - 1).coerceAtLeast(0)) / PAGE_SIZE
        val watchedRange = if (lastWatchedSeason == targetSeason && lastWatchedEpisodeZeroIdx != null) {
            rangeForEpisodeIndex(lastWatchedEpisodeZeroIdx).coerceIn(0, maxRange)
        } else null

        if (saved != null && saved.season == targetSeason && saved.range in 0..maxRange) {
            if (watchedRange != null && lastWatchedAt > saved.at) {
                return watchedRange
            }
            return saved.range
        }
        return watchedRange ?: 0
    }

    fun restoreAbsRange(
        saved: ListPos?,
        lastWatchedAbsZeroIdx: Int?,
        lastWatchedAt: Long,
        totalEpisodes: Int
    ): Int {
        val maxRange = ((totalEpisodes - 1).coerceAtLeast(0)) / PAGE_SIZE
        val watchedRange = lastWatchedAbsZeroIdx?.let { rangeForEpisodeIndex(it).coerceIn(0, maxRange) }
        if (saved != null && saved.absRange in 0..maxRange) {
            if (watchedRange != null && lastWatchedAt > saved.at) {
                return watchedRange
            }
            return saved.absRange
        }
        return watchedRange ?: 0
    }

    /**
     * موضع التمرير يُستعاد فقط إذا فُتحت نفس القائمة بالضبط، وبعد أن تحتوي القائمة على الفهرس المحفوظ.
     */
    fun canRestoreScroll(
        saved: ListPos?,
        currentSeason: Int,
        currentRange: Int,
        currentTab: Int,
        loadedItemCount: Int
    ): Boolean {
        if (saved == null) return false
        if (saved.season != currentSeason || saved.TabOrDefault() != currentTab) return false
        if (saved.range >= 0 && saved.range != currentRange) return false
        return saved.scrollIndex in 0 until loadedItemCount
    }

    private fun ListPos.TabOrDefault(): Int = tab
}
