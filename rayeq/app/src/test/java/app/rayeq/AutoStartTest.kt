package app.rayeq

import app.rayeq.data.AutoStart
import app.rayeq.data.StickyServer
import org.junit.Assert.*
import org.junit.Test

class AutoStartTest {

    @Test
    fun testArabicArrivingAt9sBeatsImmediateForeign() {
        // سيناريو §18.1: أجنبي فوري عند 200ms مقابل عربي يصل بعد 9 ثوانٍ (9000ms < 12000ms)
        val arrivals = listOf(
            AutoStart.Arrival("en1", "ForeignSrc", "FastHost 1080p", "https://en/1", atMs = 200L, isArabic = false, quality = 1080),
            AutoStart.Arrival("ar1", "ArabSrc", "ArabHost 1080p", "https://ar/1", atMs = 9_000L, isArabic = true, quality = 1080)
        )
        val decision = AutoStart.firstToPlay(arrivals, arabicDoneAt = 10_500L)
        assertNotNull(decision)
        assertEquals("ar1", decision!!.chosen.id)
    }

    @Test
    fun testAllArabicDoneEmptyStartsForeignImmediately() {
        // إذا انتهت كل المصادر العربية بلا روابط عند 2500ms يبدأ الأجنبي فوراً دون انتظار 12s
        val arrivals = listOf(
            AutoStart.Arrival("en1", "ForeignSrc", "FastHost 1080p", "https://en/1", atMs = 300L, isArabic = false, quality = 1080)
        )
        val decision = AutoStart.firstToPlay(arrivals, arabicDoneAt = 2_500L)
        assertNotNull(decision)
        assertEquals("en1", decision!!.chosen.id)
        assertEquals(3_500L, decision.startedAtMs)
    }

    @Test
    fun testImmediate4kArabicStartsWithoutCoalesceWait() {
        val arrivals = listOf(
            AutoStart.Arrival("ar4k", "ArabSrc", "4K Server", "https://ar/4k", atMs = 400L, isArabic = true, quality = 2160)
        )
        val decision = AutoStart.firstToPlay(arrivals)
        assertNotNull(decision)
        assertEquals("ar4k", decision!!.chosen.id)
        assertEquals(400L, decision.startedAtMs)
    }

    @Test
    fun testUpgradeAndArabicStealRules() {
        // الترقية مسموحة خلال 20s داخل نفس المجموعة اللغوية وحتى مرتين
        assertTrue(
            AutoStart.shouldUpgradeQuality(
                elapsedMs = 8_000L,
                upgradesDone = 0,
                isManualSelection = false,
                currentIsSticky = false,
                currentIsArabic = true,
                currentQuality = 720,
                candidateIsArabic = true,
                candidateQuality = 1080
            )
        )
        // ممنوع الخروج من المجموعة العربية للترقية إلى أجنبي 4K
        assertFalse(
            AutoStart.shouldUpgradeQuality(
                elapsedMs = 5_000L,
                upgradesDone = 0,
                isManualSelection = false,
                currentIsSticky = false,
                currentIsArabic = true,
                currentQuality = 720,
                candidateIsArabic = false,
                candidateQuality = 2160
            )
        )
        // سرقة التشغيل لصالح سيرفر عربي وصل خلال 45 ثانية بعد بدء أجنبي تلقائياً
        assertTrue(
            AutoStart.shouldStealForArabic(
                elapsedMs = 32_000L,
                isManualSelection = false,
                currentIsSticky = false,
                currentIsArabic = false,
                candidateIsArabic = true
            )
        )
    }
}
