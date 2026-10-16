package app.rayeq.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object HomeSections {
    val DEFAULT_ORDER = listOf(
        "hero",
        "continue",
        "top10",
        "rows1",
        "because",
        "mylist",
        "genres",
        "rows2",
        "networks"
    )

    val LABELS = mapOf(
        "hero" to "البطل الرئيسي",
        "continue" to "أكمل المشاهدة",
        "top10" to "الأفضل ١٠",
        "rows1" to "الأقسام الرئيسية ١",
        "because" to "لأنك شاهدت…",
        "mylist" to "قائمتي",
        "genres" to "التصنيفات والأنواع",
        "rows2" to "الأقسام الرئيسية ٢",
        "networks" to "الشبكات والاستوديوهات"
    )

    fun visibleSections(order: List<String>, hidden: Set<String>): List<String> {
        val valid = order.filter { it in DEFAULT_ORDER }.distinct()
        val missing = DEFAULT_ORDER.filter { it !in valid }
        return (valid + missing).filter { it !in hidden }
    }
}

@Serializable
data class AppSettings(
    // 1. عام
    val startTab: Int = 0,
    val confirmExit: Boolean = true,
    val showAnnouncements: Boolean = true,
    val checkUpdates: Boolean = true,
    val dismissedAnnouncementId: Int = 0,
    val homeSection: String = "all", // all | tv | movie | anime

    // 2. المظهر
    val palette: String = "ink", // ink (حبر) | emerald | ruby | violet | sand | charcoal | custom
    val customAccentArgb: Long = 0xFF38BDF8,
    val backgroundStyle: String = "gradient", // solid | gradient | ambient
    val amoled: Boolean = false,
    val themeMode: String = "dark", // system | light | dark
    val heroStyle: String = "posters", // posters | cinematic | compact
    val heroAutoAdvance: Boolean = true,
    val cardSize: String = "medium", // small | medium | large
    val gridColumns: Int = 3,
    val showRatings: Boolean = true,
    val showPosterTitles: Boolean = false,
    val reduceMotion: Boolean = false,
    val homeOrder: List<String> = HomeSections.DEFAULT_ORDER,
    val homeHidden: Set<String> = emptySet(),

    // 3. المشغّل
    val autoNext: Boolean = true,
    val nextCountdownSec: Int = 10,
    val showSkipButtons: Boolean = true,
    val autoSkipSegments: Boolean = false,
    val autoPip: Boolean = true,
    val backgroundAudio: Boolean = false,
    val doubleTapSeek: Boolean = true,
    val seekStepSec: Int = 10,
    val swipeGestures: Boolean = true,
    val longPress2x: Boolean = true,
    val defaultSpeed: Float = 1.0f,
    val rememberSpeedPerTitle: Boolean = true,
    val defaultResizeMode: String = "fit", // fit | fill | stretch | zoom | 16:9 | 4:3
    val showRemainingTime: Boolean = false,
    val autoRotatePlayer: Boolean = true,
    val autoResume: Boolean = true,
    val autoSkipDeadServerSec: Int = 20,
    val showProviderNameNextToServer: Boolean = true,

    // 4. الترجمة والصوت
    val subtitlesEnabledByDefault: Boolean = true,
    val preferredSubLangs: String = "ar,en",
    val preferredAudioLangs: String = "ar,ja,en",
    val autoSearchOnlineSubs: Boolean = true,
    val autoSubsOnArabicServers: Boolean = false,
    val ignoreAssStyling: Boolean = false,
    val subFont: String = "app", // app | Tajawal | Almarai | Noto Naskh Arabic | Cairo | IBM Plex Sans Arabic | sans | serif | custom
    val subColorArgb: Long = 0xFFFFFFFF,
    val subOutlinePx: Float = 2f,
    val subOutlineColorArgb: Long = 0xFF000000,
    val subShadow: String = "soft", // none | soft | strong
    val subBoxColorArgb: Long = 0x00000000,
    val subBottomPaddingDp: Int = 28,
    val subSizeSp: Int = 20,
    val subBold: Boolean = true,

    // 5. الجودة والتخزين المؤقت
    val defaultQuality: Int = 0, // 0=تلقائي(أعلى) | 1080 | 720 | 480 | 360
    val dataSaver: Boolean = false,
    val forwardBufferSec: Int = 50,
    val backBufferSec: Int = 30,
    val videoCacheMb: Int = 250,

    // 6. التنزيلات
    val downloadLocation: String = "external", // external | internal
    val downloadParallelism: Int = 2,
    val downloadWifiOnly: Boolean = true,
    val downloadQuality: Int = 1080,

    // 7. البحث والسجل والإشعارات
    val saveSearchHistory: Boolean = true,
    val saveWatchHistory: Boolean = true,
    val notifyNewEpisodes: Boolean = true,
    val notifyIntervalHours: Int = 12,
    val notifyWatchingOnly: Boolean = true,

    // طابع التعديل للمزامنة
    val settingsAt: Long = 0L
)

object Prefs {
    private const val PREF_FILE = "rayeq_settings_store"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var appContext: Context? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    fun init(context: Context) {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val raw = sp.getString("settings_json", null)
        if (!raw.isNullOrBlank()) {
            try {
                _settings.value = json.decodeFromString<AppSettings>(raw)
            } catch (_: Exception) {}
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value).copy(settingsAt = System.currentTimeMillis())
        _settings.value = next
        save(next)
        CloudSync.schedulePush()
    }

    fun applyMerged(remote: AppSettings) {
        if (remote.settingsAt > _settings.value.settingsAt) {
            _settings.value = remote
            save(remote)
        }
    }

    private fun save(s: AppSettings) {
        val sp = appContext?.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE) ?: return
        sp.edit().putString("settings_json", json.encodeToString(s)).apply()
    }
}
