package app.rayeq.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class RemoteAppConfig(
    val latestVersion: String? = null,
    val latestCode: Int? = null,
    val downloadUrl: String? = null,
    val notes: String = "",
    val minCode: Int? = null,
    val announcement: String = "",
    val announcementId: Int = 0,
    val maintenance: Boolean = false,
    val maintenanceMessage: String = "",
    val supportContact: String = "",
    val fetchedAt: Long = 0L
)

object AppInfo {
    private const val PREFS = "rayeq_app_info"
    private const val MAINT_CACHE_TTL_MS = 120_000L // الصيانة المخزنة لا يُعمل بها إلا إذا كان عمرها أقل من دقيقتين
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    private val _config = MutableStateFlow(RemoteAppConfig())
    val config: StateFlow<RemoteAppConfig> = _config

    fun init(context: Context) {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedAt = sp.getLong("fetched_at", 0L)
        val isFreshEnough = System.currentTimeMillis() - cachedAt < MAINT_CACHE_TTL_MS
        _config.value = RemoteAppConfig(
            latestVersion = sp.getString("latest_version", null),
            latestCode = sp.getInt("latest_code", -1).takeIf { it > 0 },
            downloadUrl = sp.getString("download_url", null),
            notes = sp.getString("notes", "") ?: "",
            minCode = sp.getInt("min_code", -1).takeIf { it > 0 },
            announcement = sp.getString("announcement", "") ?: "",
            announcementId = sp.getInt("announcement_id", 0),
            maintenance = if (isFreshEnough) sp.getBoolean("maintenance", false) else false,
            maintenanceMessage = sp.getString("maintenance_msg", "") ?: "",
            supportContact = sp.getString("support_contact", "") ?: "",
            fetchedAt = cachedAt
        )
        refresh()
    }

    fun refresh() {
        scope.launch {
            // يُعاد طلبه بدون مصادقة إذا فشل
            val raw = Net.get("/v1/app", useAuth = true) ?: Net.get("/v1/app", useAuth = false) ?: return@launch
            try {
                val obj = JSONObject(raw)
                val now = System.currentTimeMillis()
                val maint = obj.opt("maintenance") == true
                val next = RemoteAppConfig(
                    latestVersion = if (obj.isNull("latestVersion")) null else obj.optString("latestVersion"),
                    latestCode = if (obj.isNull("latestCode")) null else obj.optInt("latestCode"),
                    downloadUrl = if (obj.isNull("downloadUrl")) null else obj.optString("downloadUrl"),
                    notes = obj.optString("notes", ""),
                    minCode = if (obj.isNull("minCode")) null else obj.optInt("minCode"),
                    announcement = obj.optString("announcement", ""),
                    announcementId = obj.optInt("announcementId", 0),
                    maintenance = maint,
                    maintenanceMessage = obj.optString("maintenanceMessage", ""),
                    supportContact = obj.optString("supportContact", ""),
                    fetchedAt = now
                )
                _config.value = next
                appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                    ?.putString("latest_version", next.latestVersion)
                    ?.putInt("latest_code", next.latestCode ?: -1)
                    ?.putString("download_url", next.downloadUrl)
                    ?.putString("notes", next.notes)
                    ?.putInt("min_code", next.minCode ?: -1)
                    ?.putString("announcement", next.announcement)
                    ?.putInt("announcement_id", next.announcementId)
                    ?.putBoolean("maintenance", next.maintenance)
                    ?.putString("maintenance_msg", next.maintenanceMessage)
                    ?.putString("support_contact", next.supportContact)
                    ?.putLong("fetched_at", now)
                    ?.apply()
            } catch (_: Exception) {}
        }
    }
}
