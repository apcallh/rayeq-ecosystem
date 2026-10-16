package app.rayeq.data

import kotlin.math.max
import kotlin.math.roundToInt

object ArabicDigits {
    fun toAsciiDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun toEasternArabic(input: String): String {
        val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '0'..'9') sb.append(digits[ch - '0']) else sb.append(ch)
        }
        return sb.toString()
    }
}

object ServerLang {
    fun isArabicCode(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val s = code.trim().lowercase()
        return s == "ar" ||
            s.startsWith("ar-") ||
            s.startsWith("ar_") ||
            s == "ara" ||
            s == "arabic" ||
            s.contains("العربية")
    }

    fun repoIsArabic(declaredLanguages: List<String>): Boolean {
        val nonEmpty = declaredLanguages.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return false
        val arCount = nonEmpty.count { isArabicCode(it) }
        return arCount.toDouble() / nonEmpty.size.toDouble() >= 0.60
    }

    fun urlHintsArabic(url: String?, extraKeywords: List<String> = emptyList()): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        val defaults = listOf("arabic", "arab-", "-arab", "عربي")
        return (defaults + extraKeywords.map { it.lowercase() })
            .filter { it.isNotBlank() }
            .any { lower.contains(it) }
    }

    /**
     * ترتيب حسم لغة السيرفر (§7.5):
     * 1. علم المدير الصريح (langSource == "admin")
     * 2. حقل language من plugins.json المحفوظ محلياً
     * 3. lang أو arabic المستنتج من الخادم في /v1/sources
     * 4. MainAPI.lang وقت التشغيل
     * 5. المستودع (>= 60% عربية أو كلمات دالة في الرابط)
     */
    fun resolve(
        adminOverride: Boolean? = null,
        pluginJsonLang: String? = null,
        serverLang: String? = null,
        serverArabic: Boolean? = null,
        runtimeLang: String? = null,
        repoMajorityArabic: Boolean = false,
        repoUrl: String? = null,
        pluginUrl: String? = null,
        customKeywords: List<String> = emptyList()
    ): Boolean {
        if (adminOverride != null) return adminOverride
        if (!pluginJsonLang.isNullOrBlank()) return isArabicCode(pluginJsonLang)
        if (serverArabic != null) return serverArabic
        if (!serverLang.isNullOrBlank()) return isArabicCode(serverLang)
        if (!runtimeLang.isNullOrBlank()) return isArabicCode(runtimeLang)
        if (repoMajorityArabic) return true
        if (urlHintsArabic(repoUrl, customKeywords) || urlHintsArabic(pluginUrl, customKeywords)) {
            return true
        }
        return false
    }
}

object LiveDetect {
    val EXCLUDE = setOf(
        "anime",
        "animemovie",
        "ova",
        "asiandrama",
        "drama",
        "cartoon",
        "music",
        "documentary",
        "torrent"
    )

    fun normalizeTypes(vararg sources: Collection<String>?): Set<String> {
        val out = mutableSetOf<String>()
        for (src in sources) {
            if (src == null) continue
            for (raw in src) {
                for (part in raw.split(",")) {
                    val t = part.trim().lowercase()
                    if (t.isNotEmpty()) out.add(t)
                }
            }
        }
        return out
    }

    fun isNotContent(
        name: String?,
        internalName: String?,
        types: Set<String> = emptySet(),
        kind: String? = null
    ): Boolean {
        if (kind == "live") return false
        val iname = internalName?.trim().orEmpty()
        val nm = name?.trim().orEmpty()
        if (iname.equals("Rate", ignoreCase = true) ||
            nm.equals("Extension Rating", ignoreCase = true) ||
            nm.contains("تقييم الإضافات")
        ) {
            return true
        }
        return types.isNotEmpty() && types.all { it == "others" }
    }

    fun isLiveTypes(types: Set<String>): Boolean {
        if ("live" !in types) return false
        return types.none { it in EXCLUDE }
    }

    fun isLive(
        kindOverride: String? = null,
        serverLiveFlag: Boolean? = null,
        runtimeTypes: Collection<String>? = null,
        pluginJsonTypes: Collection<String>? = null,
        serverTypes: Collection<String>? = null,
        name: String? = null,
        internalName: String? = null
    ): Boolean {
        val merged = normalizeTypes(runtimeTypes, pluginJsonTypes, serverTypes)
        if (isNotContent(name, internalName, merged, kindOverride)) return false
        if (kindOverride == "live") return true
        if (kindOverride == "content") return false
        if (serverLiveFlag != null) return serverLiveFlag
        return isLiveTypes(merged)
    }
}

object LinkQuality {
    private val TIERS = listOf(4320, 2160, 1440, 1080, 720, 480, 360, 240)
    private val EXPLICIT_RE = Regex("(?<!\\d)(4320|2160|1440|1080|720|576|480|360|240)[pi]\\b", RegexOption.IGNORE_CASE)
    private val DIM_RE = Regex("\\b(\\d{3,4})\\s*[x×]\\s*(\\d{3,4})\\b", RegexOption.IGNORE_CASE)
    private val BARE_RE = Regex("(?<!\\d)(2160|1440|1080|720|480|360)(?!\\d)")
    private val URL_PATH_RE = Regex("(?:/|_|-|\\.)(2160|1440|1080|720|480|360)[pi]?(?:/|_|\\.|-|$)", RegexOption.IGNORE_CASE)
    private val URL_QUERY_RE = Regex("(?:^|[?&])(?:q|quality)=(2160|1440|1080|720|480|360)[pi]?(?:&|$)", RegexOption.IGNORE_CASE)
    private val HLS_RES_RE = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * فخ CloudStream: Qualities.Unknown = 400، وكثير من الإضافات تضع 360 أو 400 افتراضياً.
     * لذلك الخام الموثوق هو 401..4320 فقط، وما دونه 0 (مجهول).
     */
    fun fromRaw(q: Int): Int = if (q in 401..4320) q else 0

    fun fromText(rawText: String?): Int {
        if (rawText.isNullOrBlank()) return 0
        val s = ArabicDigits.toAsciiDigits(rawText).lowercase()

        EXPLICIT_RE.find(s)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return if (it == 576) 480 else it
        }
        DIM_RE.find(s)?.let { m ->
            val w = m.groupValues[1].toIntOrNull() ?: 0
            val h = m.groupValues[2].toIntOrNull() ?: 0
            if (w > 0 && h > 0) return fromVideo(w, h)
        }
        if (Regex("\\b(4k|uhd)\\b").containsMatchIn(s)) return 2160
        if (Regex("\\b(2k|qhd)\\b").containsMatchIn(s)) return 1440
        if (Regex("\\b(fhd|full\\s*hd)\\b").containsMatchIn(s)) return 1080
        if (Regex("\\bhd\\b").containsMatchIn(s)) return 720
        if (Regex("\\bsd\\b").containsMatchIn(s)) return 480

        // الكلمات العربية
        if (s.contains("فائقة") || s.contains("الترا") || s.contains("ألترا")) return 2160
        if (s.contains("عالية") || s.contains("مرتفعة")) return 1080
        if (s.contains("متوسطة")) return 720
        if (s.contains("منخفضة") || s.contains("ضعيفة") || s.contains("سيئة")) return 480

        // الرقم المجرد
        BARE_RE.find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 0
    }

    fun fromUrl(url: String?): Int {
        if (url.isNullOrBlank()) return 0
        val clean = ArabicDigits.toAsciiDigits(url)
        val pathPart = clean.substringBefore('?')
        val queryPart = clean.substringAfter('?', "")
        URL_PATH_RE.find(pathPart)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (queryPart.isNotEmpty()) {
            URL_QUERY_RE.find("?$queryPart")?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return 0
    }

    fun fromVideo(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val effectiveH = if (width >= height) {
            max(height, (width * 9.0 / 16.0).roundToInt())
        } else {
            minOf(width, height)
        }
        return when {
            effectiveH >= 1800 -> 2160
            effectiveH >= 1260 -> 1440
            effectiveH >= 900 -> 1080
            effectiveH >= 600 -> 720
            effectiveH >= 420 -> 480
            effectiveH >= 300 -> 360
            else -> 240
        }
    }

    fun fromHlsMaster(playlistText: String?): Int {
        if (playlistText.isNullOrBlank()) return 0
        var best = 0
        for (m in HLS_RES_RE.findAll(playlistText)) {
            val w = m.groupValues[1].toIntOrNull() ?: 0
            val h = m.groupValues[2].toIntOrNull() ?: 0
            val tier = fromVideo(w, h)
            if (tier > best) best = tier
        }
        return best
    }

    fun effective(raw: Int, name: String?, url: String?, learned: Int = 0): Int {
        val r = fromRaw(raw)
        if (r > 0) return r
        val t = fromText(name)
        if (t > 0) return t
        if (learned > 0) return learned
        return fromUrl(url)
    }

    fun badge(quality: Int, isRealDowngraded: Boolean = false): String {
        if (quality <= 0) return "تلقائي"
        return if (isRealDowngraded) "${quality}p فعلي" else "${quality}p"
    }

    fun stripQuality(name: String): String {
        val ascii = ArabicDigits.toAsciiDigits(name).lowercase()
        return ascii
            .replace(EXPLICIT_RE, " ")
            .replace(BARE_RE, " ")
            .replace(Regex("\\b(4k|2k|uhd|qhd|fhd|full\\s*hd|hd|sd|hq|lq)\\b"), " ")
            .replace(Regex("(فائقة|الترا|ألترا|عالية|مرتفعة|متوسطة|منخفضة|ضعيفة|سيئة|جودة)"), " ")
            .replace(Regex("[^a-z0-9\u0621-\u064A]+"), " ")
            .trim()
    }

    fun serverKey(source: String, linkName: String): String {
        return "${source.trim()}|${stripQuality(linkName)}"
    }
}

object RealQuality {
    private val ORDERED_TIERS = listOf(2160, 1440, 1080, 720, 480, 360)

    fun baseMinKbps(tier: Int): Double = when {
        tier >= 2160 -> 3500.0
        tier >= 1440 -> 2000.0
        tier >= 1080 -> 900.0
        tier >= 720 -> 450.0
        tier >= 480 -> 220.0
        else -> 100.0
    }

    data class Assessment(
        val nominalTier: Int,
        val effectiveTier: Int,
        val videoKbps: Double,
        val downgraded: Boolean,
        val badge: String
    )

    fun assess(
        nominalHeight: Int,
        totalKbps: Double,
        isEfficientCodec: Boolean = false, // HEVC / AV1 / VP9
        fps: Float = 24f,
        isAnime: Boolean = false
    ): Assessment {
        val nominalTier = LinkQuality.fromVideo(nominalHeight * 16 / 9, nominalHeight)
        if (nominalTier <= 0 || totalKbps <= 0.0) {
            return Assessment(
                nominalTier = nominalTier,
                effectiveTier = nominalTier,
                videoKbps = 0.0,
                downgraded = false,
                badge = LinkQuality.badge(nominalTier)
            )
        }
        val videoKbps = max(totalKbps - 160.0, totalKbps * 0.60)
        var factor = 1.0
        if (isEfficientCodec) factor *= 0.6
        if (fps >= 45f) factor *= 1.5
        if (isAnime) factor *= 0.7

        val startIdx = ORDERED_TIERS.indexOfFirst { it <= nominalTier }.coerceAtLeast(0)
        var chosenTier = ORDERED_TIERS.last()
        for (i in startIdx until ORDERED_TIERS.size) {
            val candidate = ORDERED_TIERS[i]
            val minReq = baseMinKbps(candidate) * factor
            if (videoKbps >= minReq || candidate == 360) {
                chosenTier = candidate
                break
            }
        }
        val downgraded = chosenTier < nominalTier
        return Assessment(
            nominalTier = nominalTier,
            effectiveTier = chosenTier,
            videoKbps = videoKbps,
            downgraded = downgraded,
            badge = LinkQuality.badge(chosenTier, isRealDowngraded = downgraded)
        )
    }
}

object AutoStart {
    const val COALESCE_MS = 1000L
    const val GRACE_MS = 1500L
    const val NON_ARABIC_WAIT_MS = 12000L
    const val UPGRADE_WINDOW_MS = 20000L
    const val MAX_UPGRADES = 2
    const val ARABIC_STEAL_WINDOW = 45000L

    data class Arrival(
        val id: String,
        val source: String,
        val linkName: String,
        val url: String,
        val atMs: Long,
        val isArabic: Boolean,
        val quality: Int,
        val adminRank: Int = 0
    )

    data class SimDecision(
        val chosen: Arrival,
        val startedAtMs: Long
    )

    /**
     * محاكاة نقية لاختيار أول سيرفر يبدأ تلقائياً (§9.5)
     */
    fun firstToPlay(
        arrivals: List<Arrival>,
        recent: List<String> = emptyList(),
        arabicDoneAt: Long? = null,
        stickyPick: StickyPick? = null,
        stickySourceDoneAt: Long? = null,
        currentEpisode: Int? = null
    ): SimDecision? {
        if (arrivals.isEmpty()) return null
        val sortedByTime = arrivals.sortedBy { it.atMs }

        fun rankOf(a: Arrival): Long {
            val stickyLvl = if (stickyPick != null) {
                StickyServer.matchLevel(stickyPick, a.source, a.linkName, a.quality, a.url, currentEpisode)
            } else 0
            val recIdx = recent.indexOf(a.source).let { if (it >= 0) it else 9 }
            return PlaybackMemory.key(
                isArabic = a.isArabic,
                quality = a.quality,
                memoryMatchTier = 3,
                adminRank = a.adminRank,
                recentRank = recIdx,
                stickyLevel = stickyLvl
            )
        }

        // Check if an exact sticky match (level 4) or partial sticky match (level 2..3) arrives within WAIT_MS
        if (stickyPick != null) {
            val stickyCutoff = minOf(StickyServer.WAIT_MS, stickySourceDoneAt ?: StickyServer.WAIT_MS)
            val stickyCandidates = sortedByTime.filter {
                it.atMs <= stickyCutoff &&
                    StickyServer.matchLevel(stickyPick, it.source, it.linkName, it.quality, it.url, currentEpisode) >= 2
            }
            if (stickyCandidates.isNotEmpty()) {
                val firstExact = stickyCandidates.firstOrNull {
                    StickyServer.matchLevel(stickyPick, it.source, it.linkName, it.quality, it.url, currentEpisode) == 4
                }
                if (firstExact != null) {
                    return SimDecision(firstExact, firstExact.atMs)
                }
                val firstPartial = stickyCandidates.first()
                val startAt = firstPartial.atMs + StickyServer.EXACT_GRACE_MS
                val availableByGrace = stickyCandidates.filter { it.atMs <= startAt }
                val bestSticky = availableByGrace.minBy { rankOf(it) }
                return SimDecision(bestSticky, startAt)
            }
        }

        // Evaluate candidate start times
        var deadline = Long.MAX_VALUE
        for (a in sortedByTime) {
            if (a.atMs > deadline) break
            val candidateDue = if (a.isArabic) {
                if (a.quality >= 2160) a.atMs else a.atMs + COALESCE_MS
            } else {
                val arDone = arabicDoneAt ?: NON_ARABIC_WAIT_MS
                val waitUntil = minOf(NON_ARABIC_WAIT_MS, maxOf(a.atMs, arDone))
                waitUntil + COALESCE_MS
            }
            if (candidateDue < deadline) {
                deadline = candidateDue
            }
        }

        if (stickyPick != null) {
            val gateRelease = minOf(StickyServer.WAIT_MS, stickySourceDoneAt ?: StickyServer.WAIT_MS)
            if (deadline < gateRelease) deadline = gateRelease
        }

        val pool = sortedByTime.filter { it.atMs <= deadline }
        val winner = pool.minByOrNull { rankOf(it) } ?: sortedByTime.first()
        return SimDecision(winner, deadline)
    }

    fun shouldUpgradeQuality(
        elapsedMs: Long,
        upgradesDone: Int,
        isManualSelection: Boolean,
        currentIsSticky: Boolean,
        currentIsArabic: Boolean,
        currentQuality: Int,
        candidateIsArabic: Boolean,
        candidateQuality: Int
    ): Boolean {
        if (isManualSelection || currentIsSticky) return false
        if (elapsedMs > UPGRADE_WINDOW_MS || upgradesDone >= MAX_UPGRADES) return false
        // لا خروج من المجموعة العربية
        if (currentIsArabic && !candidateIsArabic) return false
        val curRank = PlaybackMemory.qualityRank(currentQuality)
        val candRank = PlaybackMemory.qualityRank(candidateQuality)
        return candRank < curRank
    }

    fun shouldStealForArabic(
        elapsedMs: Long,
        isManualSelection: Boolean,
        currentIsSticky: Boolean,
        currentIsArabic: Boolean,
        candidateIsArabic: Boolean
    ): Boolean {
        if (isManualSelection || currentIsSticky) return false
        if (currentIsArabic || !candidateIsArabic) return false
        return elapsedMs <= ARABIC_STEAL_WINDOW
    }
}
