package app.rayeq.data

import org.json.JSONObject

data class SkipSegment(
    val type: String, // intro | recap | outro | preview
    val startMs: Long,
    val endMs: Long?,
    val source: String
) {
    val labelAr: String
        get() = when (type) {
            "intro" -> "تخطّي المقدمة"
            "recap" -> "تخطّي الملخص"
            "outro" -> "تخطّي الخاتمة"
            "preview" -> "تخطّي العرض القادم"
            else -> "تخطّي"
        }

    fun isActiveAt(positionMs: Long, durationMs: Long): Boolean {
        val end = endMs ?: durationMs
        return positionMs in startMs until end
    }
}

object Skip {
    /**
     * يجلب مقاطع التخطي الحقيقية فقط من /v1/skip (§11.5) — لا تخمين زمني (+85s) أبداً.
     */
    suspend fun fetchSegments(
        tmdbId: Int,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        absEpisode: Int?,
        durationMs: Long,
        animeMalId: Int? = null
    ): List<SkipSegment> {
        val params = mutableListOf(
            "tmdb=$tmdbId",
            "duration_ms=$durationMs"
        )
        if (!imdbId.isNullOrBlank()) params.add("imdb=$imdbId")
        if (season != null && season > 0) params.add("season=$season")
        if (episode != null && episode > 0) params.add("episode=$episode")
        if (absEpisode != null && absEpisode > 0) params.add("abs=$absEpisode")
        if (animeMalId != null && animeMalId > 0) params.add("anime_mal=$animeMalId")

        val raw = Net.get("/v1/skip?${params.joinToString("&")}") ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("segments") ?: return emptyList()
            val out = mutableListOf<SkipSegment>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val st = s.optLong("start_ms", -1L)
                if (st < 0L) continue
                val et = if (s.isNull("end_ms")) null else s.optLong("end_ms")
                out.add(
                    SkipSegment(
                        type = s.optString("type", "intro"),
                        startMs = st,
                        endMs = et,
                        source = s.optString("source", "")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
