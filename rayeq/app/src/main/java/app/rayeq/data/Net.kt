package app.rayeq.data

import app.rayeq.BuildConfig
import app.rayeq.auth.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Net {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val reqBuilder = chain.request().newBuilder()
                .header("X-Rayeq-Client", "android/${BuildConfig.VERSION_NAME}")
            Auth.token()?.let { tok ->
                reqBuilder.header("Authorization", "Bearer $tok")
            }
            chain.proceed(reqBuilder.build())
        }
        .build()

    val longClient: OkHttpClient = client.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private fun fullUrl(path: String): String {
        val base = BuildConfig.SERVER_URL.trimEnd('/')
        return if (path.startsWith("http")) path else "$base$path"
    }

    suspend fun get(path: String, useAuth: Boolean = true): String? = withContext(Dispatchers.IO) {
        try {
            val rb = Request.Builder().url(fullUrl(path))
            if (!useAuth) rb.removeHeader("Authorization")
            client.newCall(rb.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getWithCode(
        path: String,
        longPoll: Boolean = false,
        useAuth: Boolean = true
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            val rb = Request.Builder().url(fullUrl(path))
            if (!useAuth) rb.removeHeader("Authorization")
            val c = if (longPoll) longClient else client
            c.newCall(rb.build()).execute().use { resp ->
                resp.code to resp.body?.string()
            }
        } catch (_: Exception) {
            -1 to null
        }
    }

    suspend fun postJson(path: String, body: JSONObject): Int = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(fullUrl(path))
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { it.code }
        } catch (_: Exception) {
            -1
        }
    }

    suspend fun postJsonWithBody(path: String, body: JSONObject): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(fullUrl(path))
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { it.code to it.body?.string() }
        } catch (_: Exception) {
            -1 to null
        }
    }

    suspend fun putJsonWithBody(path: String, body: JSONObject): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(fullUrl(path))
                .put(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { it.code to it.body?.string() }
        } catch (_: Exception) {
            -1 to null
        }
    }
}
