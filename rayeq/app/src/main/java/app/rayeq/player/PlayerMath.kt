package app.rayeq.player

import kotlin.math.roundToInt

object PlayerMath {
    const val MIN_PIP_RATIO = 0.41841003f // 1 / 2.39
    const val MAX_PIP_RATIO = 2.39f
    const val MAX_LOUDNESS_GAIN_MB = 1500 // +15 dB = 1500 mB
    const val MIN_ZOOM = 1.0f
    const val MAX_ZOOM = 4.0f
    const val SUB_HISTORY_MAX = 400

    fun formatTimeMs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }

    fun formatClockDisplay(
        positionMs: Long,
        durationMs: Long,
        showRemaining: Boolean
    ): String {
        if (durationMs <= 0L) return formatTimeMs(positionMs)
        return if (showRemaining) {
            val rem = (durationMs - positionMs).coerceAtLeast(0L)
            "-${formatTimeMs(rem)} / ${formatTimeMs(durationMs)}"
        } else {
            "${formatTimeMs(positionMs)} / ${formatTimeMs(durationMs)}"
        }
    }

    /**
     * حساب نسبة PiP من الفيديو المفكوك مع مراعاة pixelWidthHeightRatio وضمن حدود Android (§11.2)
     */
    fun pipAspectRatio(
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float = 1.0f
    ): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return 16 to 9
        val rawRatio = (videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat()
        val clamped = rawRatio.coerceIn(MIN_PIP_RATIO, MAX_PIP_RATIO)
        val num = (clamped * 1000f).roundToInt().coerceAtLeast(1)
        return num to 1000
    }

    /**
     * فوق 100% يُستخدم LoudnessEnhancer حتى +15 dB (1500 mB)
     */
    fun loudnessGainMillibels(volumePercent: Int): Int {
        if (volumePercent <= 100) return 0
        val extra = (volumePercent - 100).coerceIn(0, 100)
        return ((extra / 100.0f) * MAX_LOUDNESS_GAIN_MB).roundToInt()
    }

    fun clampZoom(scale: Float): Float = scale.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * ترقيم تسميات المسارات الصوتية المكررة («العربية»، «العربية ٢»، «العربية ٣»)
     */
    fun numberDuplicateTrackLabels(labels: List<String>): List<String> {
        val counts = mutableMapOf<String, Int>()
        val totals = labels.groupingBy { it.trim() }.eachCount()
        return labels.map { raw ->
            val clean = raw.trim().ifEmpty { "مسار صوتي" }
            val total = totals[clean] ?: 1
            if (total <= 1) {
                clean
            } else {
                val n = (counts[clean] ?: 0) + 1
                counts[clean] = n
                if (n == 1) clean else "$clean ${app.rayeq.data.ArabicDigits.toEasternArabic(n.toString())}"
            }
        }
    }

    fun channelLayoutLabel(channelCount: Int): String = when (channelCount) {
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> if (channelCount > 0) "${channelCount}ch" else ""
    }
}
