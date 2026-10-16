package app.rayeq.data

data class EpCandidate(
    val data: String,
    val season: Int?,
    val episode: Int?,
    val name: String? = null
)

object EpisodeMatch {
    private val EP_NAME_RE = Regex(
        "(?:الحلقة|حلقة|episode|ep|e)\\s*0*(\\d{1,4})\\b",
        RegexOption.IGNORE_CASE
    )
    private val BARE_NUM_RE = Regex("^\\s*0*(\\d{1,4})\\s*$")

    fun extractEpisodeNumberFromName(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        val clean = ArabicDigits.toAsciiDigits(name)
        EP_NAME_RE.find(clean)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        BARE_NUM_RE.find(clean)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun toAbsolute(season: Int, episode: Int, seasonEpisodeCounts: Map<Int, Int>): Int {
        if (season <= 1) return episode
        var sum = 0
        for ((s, cnt) in seasonEpisodeCounts) {
            if (s in 1 until season && cnt > 0) sum += cnt
        }
        return sum + episode
    }

    fun fromAbsolute(absEpisode: Int, seasonEpisodeCounts: List<Pair<Int, Int>>): Pair<Int, Int> {
        var rem = absEpisode.coerceAtLeast(1)
        val sorted = seasonEpisodeCounts.filter { it.first > 0 && it.second > 0 }.sortedBy { it.first }
        for ((s, count) in sorted) {
            if (rem <= count) return s to rem
            rem -= count
        }
        val last = sorted.lastOrNull() ?: return 1 to absEpisode
        return last.first to rem
    }

    /**
     * اختيار الحلقة (§8.4 البند 4):
     * 1. صفحة متعددة المواسم: موسم + حلقة بالضبط.
     * 2. صفحة بموسم واحد هو الموسم المطلوب: رقم الحلقة.
     * 3. صفحة بموسم واحد ترقيمها يتجاوز عدد حلقات موسم TMDB ويصل إلى الرقم المطلق: الرقم المطلق.
     *    أما الصفحة القصيرة لكل موسم فلا تُقرأ مطلقاً أبداً.
     */
    fun choose(
        episodes: List<EpCandidate>,
        targetSeason: Int,
        targetEpisode: Int,
        targetAbsEpisode: Int?,
        pageMatchedSeason: Int? = null,
        tmdbSeasonCount: Int = 1,
        tmdbSeasonEpisodes: Int? = null
    ): EpCandidate? {
        if (episodes.isEmpty()) return null

        val normalized = episodes.map { ep ->
            val resolvedEp = ep.episode ?: extractEpisodeNumberFromName(ep.name)
            ep.copy(episode = resolvedEp)
        }

        val distinctSeasons = normalized.mapNotNull { it.season }.filter { it > 0 }.toSet()

        // 1. صفحة متعددة المواسم
        if (distinctSeasons.size > 1) {
            return normalized.firstOrNull {
                (it.season ?: 1) == targetSeason && it.episode == targetEpisode
            }
        }

        val singleSeasonNum = distinctSeasons.firstOrNull()
        val maxEpInPage = normalized.mapNotNull { it.episode }.maxOrNull() ?: normalized.size

        // 2. صفحة بموسم واحد هو الموسم المطلوب
        val isTargetSeasonPage =
            (singleSeasonNum != null && singleSeasonNum == targetSeason) ||
            (pageMatchedSeason != null && pageMatchedSeason == targetSeason) ||
            targetSeason == 1 ||
            tmdbSeasonCount <= 1

        if (isTargetSeasonPage) {
            normalized.firstOrNull { it.episode == targetEpisode }?.let { return it }
        }

        // 3. صفحة بموسم واحد ترقيمها يتجاوز عدد حلقات موسم TMDB ويصل إلى الرقم المطلق
        if (targetAbsEpisode != null && targetAbsEpisode > targetEpisode) {
            val tmdbCap = tmdbSeasonEpisodes ?: 0
            if (maxEpInPage > tmdbCap && maxEpInPage >= targetAbsEpisode) {
                normalized.firstOrNull { it.episode == targetAbsEpisode }?.let { return it }
            }
        }

        return null
    }
}
