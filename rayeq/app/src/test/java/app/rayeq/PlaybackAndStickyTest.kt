package app.rayeq

import app.rayeq.data.PlaybackMemory
import app.rayeq.data.StickyServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackAndStickyTest {

    @Test
    fun testPlaybackMemorySortKeyOrder() {
        // المفضّل (stickyLevel >= 2) فوق كل شيء، ثم العربي، ثم الجودة، ثم الذاكرة، ثم المدير، ثم المجهول آخراً
        val stickyKey = PlaybackMemory.key(isArabic = false, quality = 720, stickyLevel = 4)
        val ar1080 = PlaybackMemory.key(isArabic = true, quality = 1080)
        val ar720Mem = PlaybackMemory.key(isArabic = true, quality = 720, memoryMatchTier = 0)
        val ar720NoMem = PlaybackMemory.key(isArabic = true, quality = 720, memoryMatchTier = 3)
        val ar480 = PlaybackMemory.key(isArabic = true, quality = 480)
        val arUnknown = PlaybackMemory.key(isArabic = true, quality = 0)
        val en2160 = PlaybackMemory.key(isArabic = false, quality = 2160)

        assertTrue(stickyKey < ar1080)
        assertTrue(ar1080 < ar720Mem) // الجودة تسبق التذكر التلقائي!
        assertTrue(ar720Mem < ar720NoMem)
        assertTrue(ar720NoMem < ar480)
        assertTrue(ar480 < arUnknown) // المجهول تحت 480p
        assertTrue(arUnknown < en2160) // العربي قبل الأجنبي
    }

    @Test
    fun testStickyServerNormNameAndMatchLevels() {
        // normName يحذف علامات الحلقة والموسم والجودة والرقم المنفرد المساوي للحلقة
        val n1 = StickyServer.normName("VidStream - الحلقة ٥ - جودة عالية 1080p", currentEpisode = 5)
        val n2 = StickyServer.normName("VidStream Episode 06 FHD", currentEpisode = 6)
        assertEquals(n1, n2)

        val pick = StickyServer.createPick(
            source = "ArabSeed",
            linkName = "VidStream الحلقة 5 1080p",
            rawQuality = 400,
            url = "https://example.com/v.mp4",
            currentEpisode = 5,
            nowMs = 1000L
        )
        assertEquals(1080, pick.quality)

        // الحلقة التالية (6): نفس المزود + نفس الاسم + نفس الجودة = المستوى 4
        assertEquals(
            4,
            StickyServer.matchLevel(pick, "ArabSeed", "VidStream الحلقة 6 1080p", 400, "https://x/6.mp4", currentEpisode = 6)
        )
        // نفس المزود + نفس الاسم + جودة مختلفة = المستوى 3
        assertEquals(
            3,
            StickyServer.matchLevel(pick, "ArabSeed", "VidStream الحلقة 6 720p", 720, "https://x/6.mp4", currentEpisode = 6)
        )
        // نفس المزود + اسم مختلف + نفس الجودة = المستوى 2
        assertEquals(
            2,
            StickyServer.matchLevel(pick, "ArabSeed", "MyCloud 1080p", 1080, "https://x/6.mp4", currentEpisode = 6)
        )
        // نفس المزود فقط بجودة واسم مختلفين = المستوى 1 (لا يعطي أولوية)
        assertEquals(
            1,
            StickyServer.matchLevel(pick, "ArabSeed", "MyCloud 480p", 480, "https://x/6.mp4", currentEpisode = 6)
        )
    }
}
