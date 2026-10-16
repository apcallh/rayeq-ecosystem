package app.rayeq

import app.rayeq.data.LinkQuality
import app.rayeq.data.LiveDetect
import app.rayeq.data.RealQuality
import app.rayeq.data.ServerLang
import com.lagradost.cloudstream3.Qualities
import org.junit.Assert.*
import org.junit.Test

class ServerQualityTest {

    @Test
    fun testServerLangPriorityAndCodes() {
        assertTrue(ServerLang.isArabicCode("ar"))
        assertTrue(ServerLang.isArabicCode("ar-SA"))
        assertTrue(ServerLang.isArabicCode("ara"))
        assertTrue(ServerLang.isArabicCode("العربية"))
        assertFalse(ServerLang.isArabicCode("en"))

        // علم المدير الصريح يغلب كل شيء
        assertTrue(ServerLang.resolve(adminOverride = true, pluginJsonLang = "en"))
        assertFalse(ServerLang.resolve(adminOverride = false, pluginJsonLang = "ar"))

        // حقل language من plugins.json ثم الخادم ثم المستودع (>= 60%)
        assertTrue(ServerLang.resolve(adminOverride = null, pluginJsonLang = "ar"))
        assertTrue(ServerLang.resolve(repoMajorityArabic = ServerLang.repoIsArabic(listOf("ar", "ar", "en"))))
        assertFalse(ServerLang.resolve(repoMajorityArabic = ServerLang.repoIsArabic(listOf("ar", "en", "en"))))
    }

    @Test
    fun testLiveDetectMixedTypesAndTools() {
        // الأنواع المختلطة مثل [TvSeries, Live, Movie] تُكشف كبث مباشر (§6.8.2 & §7.6)
        assertTrue(LiveDetect.isLive(pluginJsonTypes = listOf("TvSeries", "Live", "Movie")))
        assertTrue(LiveDetect.isLive(pluginJsonTypes = listOf("Movie, Others, live")))

        // منصة عامة تحتوي Anime/Music/Documentary ليست بثاً مباشراً
        assertFalse(
            LiveDetect.isLive(
                pluginJsonTypes = listOf("Movie", "TvSeries", "Live", "Anime", "Music", "Documentary")
            )
        )

        // استبعاد الأدوات مثل Rate / Extension Rating / تقييم الإضافات
        assertTrue(LiveDetect.isNotContent(name = "Extension Rating", internalName = "Rate"))
        assertFalse(LiveDetect.isLive(name = "Extension Rating", internalName = "Rate", pluginJsonTypes = listOf("Live")))
    }

    @Test
    fun testLinkQualityParsingAndUnknown400Trap() {
        // Qualities.Unknown = 400 يجب أن يُعامل كمجهول (0)
        assertEquals(0, LinkQuality.fromRaw(Qualities.Unknown))
        assertEquals(0, LinkQuality.fromRaw(360))
        assertEquals(1080, LinkQuality.fromRaw(1080))

        // الأرقام العربية والكلمات العربية والرقم المجرد
        assertEquals(1080, LinkQuality.fromText("سيرفر ١٠٨٠p"))
        assertEquals(2160, LinkQuality.fromText("جودة فائقة"))
        assertEquals(1080, LinkQuality.fromText("جودة عالية"))
        assertEquals(720, LinkQuality.fromText("جودة متوسطة"))
        assertEquals(480, LinkQuality.fromText("جودة منخفضة"))
        assertEquals(1080, LinkQuality.fromText("Server 1080"))

        // من الرابط وHLS master وحجم الفيديو (1920x800 = 1080p)
        assertEquals(720, LinkQuality.fromUrl("https://cdn.example.com/v/_720.mp4?token=1080"))
        assertEquals(1080, LinkQuality.fromVideo(1920, 800))
        val hlsMaster = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
            1080.m3u8
        """.trimIndent()
        assertEquals(1080, LinkQuality.fromHlsMaster(hlsMaster))
        assertEquals("تلقائي", LinkQuality.badge(0))
        assertEquals("1080p", LinkQuality.badge(1080))
    }

    @Test
    fun testRealQualityUpscaleDetection() {
        // 1080p H.264 يحتاج >= 900 kbps للفيديو؛ إذا كان البت-ريت الكلي 550 kbps ينزل إلى 720p فعلي
        val lowBitrate1080 = RealQuality.assess(nominalHeight = 1080, totalKbps = 550.0)
        assertTrue(lowBitrate1080.downgraded)
        assertEquals(720, lowBitrate1080.effectiveTier)
        assertEquals("720p فعلي", lowBitrate1080.badge)

        // 1080p HEVC (×0.6 = 540 kbps) مع بت-ريت كلي 800 kbps يمر كـ 1080p حقيقي
        val hevc1080 = RealQuality.assess(nominalHeight = 1080, totalKbps = 800.0, isEfficientCodec = true)
        assertFalse(hevc1080.downgraded)
        assertEquals(1080, hevc1080.effectiveTier)
    }
}
