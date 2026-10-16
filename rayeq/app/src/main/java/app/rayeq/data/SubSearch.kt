package app.rayeq.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class OnlineSubtitleItem(
    val id: String,
    val provider: String, // opensubtitles | subdl | subsource
    val lang: String,
    val releaseName: String,
    val downloadUrl: String
)

object SubSearch {
    private var securePrefs: SharedPreferences? = null
    @Volatile var openSubtitlesConfiguredOnServer: Boolean = false

    fun init(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            securePrefs = EncryptedSharedPreferences.create(
                context,
                "rayeq_secure_sub_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            securePrefs = context.getSharedPreferences("rayeq_secure_sub_keys_fallback", Context.MODE_PRIVATE)
        }
    }

    fun setSubdlKey(key: String) {
        securePrefs?.edit()?.putString("subdl_api_key", key.trim())?.apply()
    }

    fun getSubdlKey(): String = securePrefs?.getString("subdl_api_key", "") ?: ""

    fun setSubSourceKey(key: String) {
        securePrefs?.edit()?.putString("subsource_api_key", key.trim())?.apply()
    }

    fun getSubSourceKey(): String = securePrefs?.getString("subsource_api_key", "") ?: ""

    fun setOpenSubtitlesUserToken(token: String, baseHost: String?) {
        securePrefs?.edit()
            ?.putString("os_user_token", token)
            ?.putString("os_base_host", baseHost ?: "")
            ?.apply()
    }

    suspend fun refreshServerSubConfig() {
        val raw = Net.get("/v1/subs/config") ?: return
        try {
            val obj = JSONObject(raw)
            openSubtitlesConfiguredOnServer =
                obj.optJSONObject("opensubtitles")?.optBoolean("configured", false) == true
        } catch (_: Exception) {}
    }

    suspend fun searchAll(
        tmdbId: Int,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        languages: String = "ar,en"
    ): List<OnlineSubtitleItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<OnlineSubtitleItem>()

        // 1. OpenSubtitles عبر وكيل الخادم إذا كان مفعلاً
        if (openSubtitlesConfiguredOnServer) {
            val q = buildString {
                append("tmdb_id=$tmdbId&languages=$languages")
                if (season != null) append("&season_number=$season")
                if (episode != null) append("&episode_number=$episode")
            }
            val raw = Net.get("/v1/subs/os/subtitles?$q")
            if (raw != null) {
                try {
                    val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val attrs = item.optJSONObject("attributes") ?: continue
                        val lang = attrs.optString("language", "ar")
                        val rel = attrs.optString("release", "OpenSubtitles")
                        val files = attrs.optJSONArray("files") ?: continue
                        if (files.length() > 0) {
                            val fileId = files.getJSONObject(0).optInt("file_id", 0)
                            if (fileId > 0) {
                                results.add(
                                    OnlineSubtitleItem(
                                        id = fileId.toString(),
                                        provider = "opensubtitles",
                                        lang = lang,
                                        releaseName = rel,
                                        downloadUrl = "os://$fileId"
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. SubDL بمفتاح المستخدم الشخصي
        val subdlKey = getSubdlKey()
        if (subdlKey.isNotBlank()) {
            try {
                val url = buildString {
                    append("https://api.subdl.com/api/v1/subtitles?api_key=$subdlKey&tmdb_id=$tmdbId&languages=$languages")
                    if (season != null) append("&season_number=$season")
                    if (episode != null) append("&episode_number=$episode")
                }
                val req = Request.Builder().url(url).build()
                Net.client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        val subs = body?.let { JSONObject(it).optJSONArray("subtitles") } ?: JSONArray()
                        for (i in 0 until subs.length()) {
                            val s = subs.getJSONObject(i)
                            val dl = s.optString("url")
                            if (dl.isNotBlank()) {
                                results.add(
                                    OnlineSubtitleItem(
                                        id = "subdl_$i",
                                        provider = "subdl",
                                        lang = s.optString("lang", "ar"),
                                        releaseName = s.optString("release_name", "SubDL"),
                                        downloadUrl = if (dl.startsWith("http")) dl else "https://dl.subdl.com$dl"
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // العربية أولاً
        results.sortedBy { !ServerLang.isArabicCode(it.lang) }
    }
}
