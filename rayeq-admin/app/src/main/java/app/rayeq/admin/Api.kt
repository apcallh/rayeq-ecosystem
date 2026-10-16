package app.rayeq.admin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class Device(
    val id: Long,
    val model: String,
    val name: String,
    val type: String,
    val approved: Boolean,
    val signedIn: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val online: Boolean
)

data class User(
    val id: Long,
    val email: String,
    val name: String,
    val status: String,
    val locked: Boolean,
    val lockedUntil: Long?,
    val effectiveStatus: String,
    val pending: Boolean,
    val accessUntil: Long?,
    val createdAt: Long,
    val deviceModel: String,
    val deviceType: String,
    val lastSeen: Long?,
    val online: Boolean,
    val state: String?,
    val appVersion: String?,
    val watchSecondsTotal: Long,
    val watchSecondsToday: Long,
    val watchSeconds7d: Long,
    val browseCount: Int,
    val customSources: Int,
    val devices: List<Device>
)

data class DayUsage(val day: String, val seconds: Long, val screens: Int)

data class UserDetail(val user: User, val days: List<DayUsage>)

data class Report(
    val version: Int,
    val ok: Int,
    val fail: Int,
    val lastOk: Long?,
    val lastFail: Long?,
    val error: String?
)

data class Plugin(
    val internalName: String,
    val name: String,
    val version: Int,
    val installedVersion: Int?,
    val status: Int,
    val pluginStatus: String,
    val enabled: Boolean,
    val language: String,
    val tvTypes: List<String>,
    val category: String,
    val live: Boolean,
    val tool: Boolean,
    val iconUrl: String?,
    val description: String,
    val authors: List<String>,
    val fileSize: Long?,
    val url: String,
    val report: Report?
)

data class Repo(
    val url: String,
    val name: String,
    val description: String,
    val iconUrl: String?,
    val addedAt: Long,
    val lastFetched: Long?,
    val error: String?,
    val ok: Boolean,
    val pluginCount: Int,
    val installedCount: Int,
    val plugins: List<Plugin>
)

data class ServerItem(
    val repo: String,
    val internalName: String,
    val name: String,
    val version: Int,
    val enabled: Boolean,
    val position: Int,
    val label: String,
    val lang: String,
    val arabic: Boolean,
    val langSource: String,
    val kind: String,
    val live: Boolean,
    val tool: Boolean,
    val category: String,
    val tvTypes: List<String>,
    val iconUrl: String?,
    val report: Report?
)

data class UserServer(
    val internalName: String,
    val name: String,
    val repo: String,
    val iconUrl: String?,
    val position: Int,
    val lang: String,
    val arabic: Boolean,
    val live: Boolean,
    val kind: String,
    val tool: Boolean,
    val tvTypes: List<String>,
    val category: String,
    val globalEnabled: Boolean,
    val userEnabled: Boolean?,
    val hiddenByRule: Boolean,
    val effective: Boolean
)

data class UserSources(
    val hideNonArabic: Boolean,
    val hideLive: Boolean,
    val servers: List<UserServer>,
    val custom: Int
)

data class Catalog(val repos: List<Repo>, val servers: List<ServerItem>)

data class Inspect(
    val url: String,
    val name: String,
    val description: String,
    val iconUrl: String?,
    val plugins: List<Plugin>,
    val listErrors: List<String>
)

data class Stats(
    val now: Long,
    val usersTotal: Int,
    val usersPending: Int,
    val usersActive: Int,
    val usersBlocked: Int,
    val usersLocked: Int,
    val usersExpired: Int,
    val usersExpiringSoon: Int,
    val usersNewWeek: Int,
    val devicesTotal: Int,
    val devicesOnline: Int,
    val devicesPending: Int,
    val phones: Int,
    val tablets: Int,
    val tvs: Int,
    val activeDay: Int,
    val reposCount: Int,
    val serversCount: Int,
    val serversEnabled: Int,
    val onlineNow: Int,
    val watchingNow: Int,
    val watchSecondsToday: Long
)

data class AppCfg(
    val latestVersion: String,
    val latestCode: Int?,
    val downloadUrl: String,
    val notes: String,
    val minCode: Int?,
    val announcement: String,
    val announcementId: Int,
    val maintenance: Boolean,
    val maintenanceMessage: String,
    val supportContact: String
)

data class LogEntry(val at: Long, val action: String, val detail: String)

data class SubsCfg(val opensubtitlesKeySet: Boolean, val opensubtitlesKeyHint: String?)

data class IntegrationsCfg(val tmdb: Boolean, val tvdb: Boolean, val opensubtitles: Boolean)

data class ApiResult<T>(
    val ok: Boolean,
    val code: Int,
    val data: T? = null,
    val error: String? = null,
    val retryAfterSec: Int? = null
)

object Api {
    private const val PREFS = "rayeq_admin_prefs"
    private var prefs: android.content.SharedPreferences? = null

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn

    private val _themeMode = MutableStateFlow("system") // system | dark | light
    val themeMode: StateFlow<String> = _themeMode

    fun init(ctx: Context) {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = sp
        _themeMode.value = sp.getString("theme_mode", "system") ?: "system"
        val tok = sp.getString("token", null)
        val exp = sp.getLong("expires_at", 0L)
        _signedIn.value = !tok.isNullOrBlank() && System.currentTimeMillis() < exp
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs?.edit()?.putString("theme_mode", mode)?.apply()
    }

    fun logout() {
        prefs?.edit()?.remove("token")?.remove("expires_at")?.apply()
        _signedIn.value = false
    }

    fun errText(code: String?): String = when (code) {
        "invalid_credentials" -> "اسم المستخدم أو كلمة المرور غير صحيحة"
        "unauthorized" -> "انتهت الجلسة، سجّل الدخول من جديد"
        "too_many_attempts" -> "محاولات كثيرة، حاول لاحقاً"
        "bad_url" -> "الرابط غير صالح"
        "not_a_repo", "not_json" -> "ليس مستودعاً صالحاً"
        "fetch_failed", "too_large" -> "تعذّر الوصول للمستودع"
        "repo_exists" -> "المستودع مضاف مسبقاً"
        "bad duration" -> "صيغة المدة غير صالحة"
        "bad maintenance" -> "قيمة وضع الصيانة غير صالحة"
        "admin not configured" -> "لم يتم إعداد أسرار المدير على الخادم"
        else -> if (code?.startsWith("http_") == true) "خطأ من المصدر (${code.removePrefix("http_")})"
        else code ?: "حدث خطأ في الاتصال بالخادم"
    }

    suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutMs: Int = 25000
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val base = BuildConfig.SERVER_URL.trimEnd('/')
        val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            val tok = prefs?.getString("token", null)
            if (!tok.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $tok")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""

            if (status == 401 && path != "/admin/login") {
                logout()
            }

            val trimmed = text.trim()
            val jsonObj = when {
                trimmed.startsWith("[") -> JSONObject().put("items", JSONArray(trimmed))
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> JSONObject()
            }

            if (status in 200..299) {
                ApiResult(ok = true, code = status, data = jsonObj)
            } else {
                val errCode = jsonObj.optString("error", "http_$status")
                val retrySec = if (jsonObj.has("retryAfterSec")) jsonObj.optInt("retryAfterSec") else null
                ApiResult(
                    ok = false,
                    code = status,
                    error = errText(errCode),
                    retryAfterSec = retrySec
                )
            }
        } catch (e: Exception) {
            ApiResult(ok = false, code = -1, error = "تعذّر الاتصال بالخادم")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun login(username: String, password: String): ApiResult<Unit> {
        val res = call(
            "POST",
            "/admin/login",
            JSONObject().put("username", username).put("password", password)
        )
        if (!res.ok || res.data == null) {
            return ApiResult(
                ok = false,
                code = res.code,
                error = res.error,
                retryAfterSec = res.retryAfterSec
            )
        }
        val token = res.data.optString("token", "")
        val exp = res.data.optLong("expiresAt", System.currentTimeMillis() + 7 * 86400_000L)
        prefs?.edit()?.putString("token", token)?.putLong("expires_at", exp)?.apply()
        _signedIn.value = true
        return ApiResult(ok = true, code = 200, data = Unit)
    }

    fun parseReport(o: JSONObject?): Report? {
        if (o == null) return null
        return Report(
            version = o.optInt("version", 0),
            ok = o.optInt("ok", 0),
            fail = o.optInt("fail", 0),
            lastOk = if (o.isNull("lastOk")) null else o.optLong("lastOk"),
            lastFail = if (o.isNull("lastFail")) null else o.optLong("lastFail"),
            error = if (o.isNull("error")) null else o.optString("error")
        )
    }

    fun parseUser(o: JSONObject): User {
        val devsArr = o.optJSONArray("devices") ?: JSONArray()
        val devs = mutableListOf<Device>()
        for (i in 0 until devsArr.length()) {
            val d = devsArr.getJSONObject(i)
            devs.add(
                Device(
                    id = d.optLong("id"),
                    model = d.optString("model", ""),
                    name = d.optString("name", ""),
                    type = d.optString("type", "phone"),
                    approved = d.optBoolean("approved", false),
                    signedIn = d.optBoolean("signedIn", false),
                    firstSeen = d.optLong("firstSeen", 0L),
                    lastSeen = d.optLong("lastSeen", 0L),
                    online = d.optBoolean("online", false)
                )
            )
        }
        return User(
            id = o.optLong("id"),
            email = o.optString("email", ""),
            name = o.optString("name", ""),
            status = o.optString("status", "pending"),
            locked = o.optBoolean("locked", false),
            lockedUntil = if (o.isNull("lockedUntil")) null else o.optLong("lockedUntil"),
            effectiveStatus = o.optString("effectiveStatus", "pending"),
            pending = o.optBoolean("pending", false),
            accessUntil = if (o.isNull("accessUntil")) null else o.optLong("accessUntil"),
            createdAt = o.optLong("createdAt", 0L),
            deviceModel = o.optString("deviceModel", ""),
            deviceType = o.optString("deviceType", "phone"),
            lastSeen = if (o.isNull("lastSeen")) null else o.optLong("lastSeen"),
            online = o.optBoolean("online", false),
            state = if (o.isNull("state")) null else o.optString("state"),
            appVersion = if (o.isNull("appVersion")) null else o.optString("appVersion"),
            watchSecondsTotal = o.optLong("watchSecondsTotal", 0L),
            watchSecondsToday = o.optLong("watchSecondsToday", 0L),
            watchSeconds7d = o.optLong("watchSeconds7d", 0L),
            browseCount = o.optInt("browseCount", 0),
            customSources = o.optInt("customSources", 0),
            devices = devs
        )
    }

    fun parseServerItem(o: JSONObject): ServerItem {
        val typesArr = o.optJSONArray("tvTypes") ?: JSONArray()
        val types = (0 until typesArr.length()).map { typesArr.optString(it) }
        return ServerItem(
            repo = o.optString("repo", ""),
            internalName = o.optString("internalName", ""),
            name = o.optString("name", ""),
            version = o.optInt("version", 0),
            enabled = o.optBoolean("enabled", true),
            position = o.optInt("position", 0),
            label = o.optString("label", ""),
            lang = o.optString("lang", "other"),
            arabic = o.optBoolean("arabic", false),
            langSource = o.optString("langSource", "plugin"),
            kind = o.optString("kind", "auto"),
            live = o.optBoolean("live", false),
            tool = o.optBoolean("tool", false),
            category = o.optString("category", "عام"),
            tvTypes = types,
            iconUrl = if (o.isNull("iconUrl")) null else o.optString("iconUrl"),
            report = parseReport(o.optJSONObject("report"))
        )
    }

    fun parsePlugin(o: JSONObject): Plugin {
        val typesArr = o.optJSONArray("tvTypes") ?: JSONArray()
        val types = (0 until typesArr.length()).map { typesArr.optString(it) }
        val authArr = o.optJSONArray("authors") ?: JSONArray()
        val authors = (0 until authArr.length()).map { authArr.optString(it) }
        return Plugin(
            internalName = o.optString("internalName", ""),
            name = o.optString("name", ""),
            version = o.optInt("version", 1),
            installedVersion = if (o.isNull("installedVersion")) null else o.optInt("installedVersion"),
            status = o.optInt("status", 1),
            pluginStatus = o.optString("pluginStatus", "available"),
            enabled = o.optBoolean("enabled", false),
            language = o.optString("language", ""),
            tvTypes = types,
            category = o.optString("category", "عام"),
            live = o.optBoolean("live", false),
            tool = o.optBoolean("tool", false),
            iconUrl = if (o.isNull("iconUrl")) null else o.optString("iconUrl"),
            description = o.optString("description", ""),
            authors = authors,
            fileSize = if (o.isNull("fileSize")) null else o.optLong("fileSize"),
            url = o.optString("url", ""),
            report = parseReport(o.optJSONObject("report"))
        )
    }

    fun parseUserSources(o: JSONObject): UserSources {
        val rules = o.optJSONObject("rules") ?: JSONObject()
        val arr = o.optJSONArray("servers") ?: JSONArray()
        val srvs = mutableListOf<UserServer>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val tArr = s.optJSONArray("tvTypes") ?: JSONArray()
            val types = (0 until tArr.length()).map { tArr.optString(it) }
            srvs.add(
                UserServer(
                    internalName = s.optString("internalName", ""),
                    name = s.optString("name", ""),
                    repo = s.optString("repo", ""),
                    iconUrl = if (s.isNull("iconUrl")) null else s.optString("iconUrl"),
                    position = s.optInt("position", i + 1),
                    lang = s.optString("lang", "other"),
                    arabic = s.optBoolean("arabic", false),
                    live = s.optBoolean("live", false),
                    kind = s.optString("kind", "auto"),
                    tool = s.optBoolean("tool", false),
                    tvTypes = types,
                    category = s.optString("category", "عام"),
                    globalEnabled = s.optBoolean("globalEnabled", true),
                    userEnabled = if (s.isNull("userEnabled")) null else s.optBoolean("userEnabled"),
                    hiddenByRule = s.optBoolean("hiddenByRule", false),
                    effective = s.optBoolean("effective", true)
                )
            )
        }
        return UserSources(
            hideNonArabic = rules.optBoolean("hideNonArabic", false),
            hideLive = rules.optBoolean("hideLive", false),
            servers = srvs,
            custom = o.optInt("custom", 0)
        )
    }
}
