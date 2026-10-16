package app.rayeq.data

import kotlinx.serialization.Serializable

@Serializable
data class StickyPick(
    val source: String,
    val host: String,
    val quality: Int,
    val rawName: String,
    val at: Long
)

object StickyServer {
    const val EXACT_GRACE_MS = 1200L
    const val WAIT_MS = 8000L
    const val LATE_SWITCH_MS = 30000L

    /**
     * تطبيع اسم السيرفر (§9.4):
     * يحذف علامات الحلقة والموسم وأرقام الجودة وكلماتها والأرقام المنفردة المساوية لرقم الحلقة الحالية.
     */
    fun normName(raw: String?, currentEpisode: Int? = null): String {
        if (raw.isNullOrBlank()) return ""
        var s = ArabicDigits.toAsciiDigits(raw).lowercase()
        // حذف S01E05
        s = s.replace(Regex("\\bs\\d{1,2}\\s*e\\d{1,4}\\b"), " ")
        // حذف علامات الحلقة والموسم
        s = s.replace(Regex("(الحلقة|حلقة|episode|ep|e)\\s*\\d{1,4}\\b"), " ")
        s = s.replace(Regex("(الموسم|موسم|season)\\s*\\d{1,2}\\b"), " ")
        // حذف أرقام الجودة وكلماتها
        s = s.replace(Regex("(?<!\\d)(4320|2160|1440|1080|720|576|480|360|240)[pi]?\\b"), " ")
        s = s.replace(Regex("\\b(4k|2k|uhd|qhd|fhd|full\\s*hd|hd|sd|hq|lq)\\b"), " ")
        s = s.replace(Regex("(جودة|فائقة|الترا|ألترا|عالية|مرتفعة|متوسطة|منخفضة|ضعيفة|سيئة)"), " ")
        // حذف الرقم المنفرد الذي يساوي رقم الحلقة الحالية (مثل "Host 05" في الحلقة 5)
        if (currentEpisode != null && currentEpisode > 0) {
            s = s.replace(Regex("(?<!\\d)0*${currentEpisode}(?!\\d)"), " ")
        }
        return s.replace(Regex("[^a-z0-9\u0621-\u064A]+"), "").trim()
    }

    /**
     * labelQuality = الخام، ثم الاسم، ثم الرابط. وليست القيمة المقاسة أبداً.
     */
    fun labelQuality(rawQuality: Int, linkName: String?, url: String?): Int {
        val r = LinkQuality.fromRaw(rawQuality)
        if (r > 0) return r
        val t = LinkQuality.fromText(linkName)
        if (t > 0) return t
        return LinkQuality.fromUrl(url)
    }

    fun createPick(
        source: String,
        linkName: String,
        rawQuality: Int,
        url: String,
        currentEpisode: Int? = null,
        nowMs: Long = System.currentTimeMillis()
    ): StickyPick {
        return StickyPick(
            source = source.trim(),
            host = normName(linkName, currentEpisode),
            quality = labelQuality(rawQuality, linkName, url),
            rawName = linkName,
            at = nowMs
        )
    }

    /**
     * مستويات المطابقة (§9.4):
     * 4 = نفس المزوّد + نفس الاسم + نفس الجودة (أو أحد الطرفين مجهول)
     * 3 = نفس المزوّد + نفس الاسم بجودة أخرى
     * 2 = نفس المزوّد + نفس الجودة، والاسم مختلف
     * 1 = نفس المزوّد فقط (لا يعطي أولوية)
     * 0 = مختلف
     */
    fun matchLevel(
        pick: StickyPick?,
        source: String,
        linkName: String,
        rawQuality: Int,
        url: String,
        currentEpisode: Int? = null
    ): Int {
        if (pick == null) return 0
        if (!pick.source.equals(source.trim(), ignoreCase = true)) return 0
        val candHost = normName(linkName, currentEpisode)
        val candQ = labelQuality(rawQuality, linkName, url)
        val sameHost = pick.host.isNotEmpty() && pick.host == candHost
        val sameOrUnknownQ = pick.quality == 0 || candQ == 0 || pick.quality == candQ
        val exactSameQ = pick.quality > 0 && pick.quality == candQ

        return when {
            sameHost && sameOrUnknownQ -> 4
            sameHost -> 3
            exactSameQ -> 2
            else -> 1
        }
    }

    fun shouldLateSwitch(
        elapsedMs: Long,
        isManualInThisEpisode: Boolean,
        currentStickyLevel: Int,
        incomingStickyLevel: Int
    ): Boolean {
        if (isManualInThisEpisode) return false
        if (elapsedMs > LATE_SWITCH_MS) return false
        if (incomingStickyLevel < 2) return false
        return incomingStickyLevel > currentStickyLevel
    }
}
