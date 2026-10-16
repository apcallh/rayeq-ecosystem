package app.rayeq.auth

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import app.rayeq.BuildConfig
import app.rayeq.data.CloudSync
import app.rayeq.data.Net
import app.rayeq.data.RevWatch
import app.rayeq.data.SourceConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

enum class Gate {
    SIGNED_OUT,
    PENDING,
    APPROVED,
    BLOCKED,
    LOCKED,
    EXPIRED,
    KICKED
}

data class AuthState(
    val gate: Gate = Gate.SIGNED_OUT,
    val busy: Boolean = false,
    val error: String? = null,
    val accessUntil: Long? = null,
    val email: String? = null,
    val lockedUntil: Long? = null,
    val checkedAt: Long = 0L,
    val name: String? = null,
    val photo: String? = null
)

object Auth {
    private const val PREFS = "rayeq_auth"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state

    fun parseGateStatus(statusStr: String?): Gate = when (statusStr?.lowercase()) {
        "approved" -> Gate.APPROVED
        "pending" -> Gate.PENDING
        "blocked" -> Gate.BLOCKED
        "locked" -> Gate.LOCKED
        "expired" -> Gate.EXPIRED
        "kicked" -> Gate.KICKED
        else -> Gate.PENDING
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!BuildConfig.AUTH_ENABLED) {
            _state.value = AuthState(gate = Gate.APPROVED, checkedAt = System.currentTimeMillis())
            return
        }
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tok = sp.getString("token", null)
        val savedGate = sp.getString("gate", null)
        val until = sp.getLong("until", -1L).takeIf { it > 0L }
        val email = sp.getString("email", null)
        val name = sp.getString("name", null)
        val photo = sp.getString("photo", null)

        val initialGate = if (tok.isNullOrBlank()) {
            Gate.SIGNED_OUT
        } else {
            parseGateStatus(savedGate)
        }
        _state.value = AuthState(
            gate = initialGate,
            accessUntil = until,
            email = email,
            name = name,
            photo = photo
        )

        // Heartbeat كل 3 دقائق وفورياً عند البدء
        scope.launch {
            while (isActive) {
                if (!token().isNullOrBlank()) {
                    heartbeatNow()
                }
                delay(3 * 60_000L)
            }
        }
    }

    fun token(): String? {
        if (!BuildConfig.AUTH_ENABLED) return null
        return appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("token", null)
    }

    fun detectDeviceType(context: Context): String {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return "tv"
        if (context.resources.configuration.smallestScreenWidthDp >= 600) return "tablet"
        return "phone"
    }

    fun signInWithGoogle(activity: Activity) {
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val googleIdOption = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                    .build()
                val req = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val credManager = CredentialManager.create(activity)
                val result = credManager.getCredential(activity, req)
                val googleCred = GoogleIdTokenCredential.createFrom(result.credential.data)
                val idToken = googleCred.idToken
                val displayName = googleCred.displayName
                val profilePic = googleCred.profilePictureUri?.toString()
                val email = googleCred.id

                val devModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val devName = try {
                    Settings.Global.getString(activity.contentResolver, "device_name") ?: devModel
                } catch (_: Exception) {
                    devModel
                }
                val devType = detectDeviceType(activity)

                val body = JSONObject()
                    .put("idToken", idToken)
                    .put("deviceModel", devModel)
                    .put("deviceName", devName)
                    .put("deviceType", devType)

                val (code, respStr) = Net.postJsonWithBody("/v1/auth/google", body)
                if (code in 200..299 && respStr != null) {
                    val obj = JSONObject(respStr)
                    val tok = obj.optString("token", "")
                    val st = parseGateStatus(obj.optString("status"))
                    val until = if (obj.isNull("accessUntil")) null else obj.optLong("accessUntil")
                    val lockedUntil = if (obj.isNull("lockedUntil")) null else obj.optLong("lockedUntil")

                    val sp = activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    sp.edit()
                        .putString("token", tok)
                        .putString("gate", st.name.lowercase())
                        .putLong("until", until ?: -1L)
                        .putString("email", email)
                        .putString("name", displayName)
                        .putString("photo", profilePic)
                        .apply()

                    val prevGate = _state.value.gate
                    _state.value = AuthState(
                        gate = st,
                        busy = false,
                        accessUntil = until,
                        lockedUntil = lockedUntil,
                        checkedAt = System.currentTimeMillis(),
                        email = email,
                        name = displayName,
                        photo = profilePic
                    )
                    onGateTransition(prevGate, st)
                } else {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = "تعذّر تسجيل الدخول (${code})"
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "تم إلغاء تسجيل الدخول أو تعذّر الوصول لحساب Google"
                )
            }
        }
    }

    suspend fun heartbeatNow() {
        if (!BuildConfig.AUTH_ENABLED) return
        val ctx = appContext ?: return
        if (token().isNullOrBlank()) return
        val body = JSONObject().put("deviceType", detectDeviceType(ctx))
        val (code, respStr) = Net.postJsonWithBody("/v1/heartbeat", body)
        if (code == 401) {
            markKicked()
            return
        }
        if (code in 200..299 && respStr != null) {
            try {
                val obj = JSONObject(respStr)
                applyStatusObject(obj)
            } catch (_: Exception) {}
        }
    }

    fun applyRev(obj: JSONObject) {
        applyStatusObject(obj)
    }

    private fun applyStatusObject(obj: JSONObject) {
        val nextGate = parseGateStatus(obj.optString("status"))
        val until = if (obj.isNull("accessUntil")) null else obj.optLong("accessUntil").takeIf { it > 0L }
        val lockedUntil = if (obj.isNull("lockedUntil")) null else obj.optLong("lockedUntil").takeIf { it > 0L }
        val prevGate = _state.value.gate

        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("gate", nextGate.name.lowercase())
            ?.putLong("until", until ?: -1L)
            ?.apply()

        _state.value = _state.value.copy(
            gate = nextGate,
            accessUntil = until,
            lockedUntil = lockedUntil,
            checkedAt = System.currentTimeMillis()
        )
        onGateTransition(prevGate, nextGate)
    }

    fun markKicked() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("token")
            ?.putString("gate", "kicked")
            ?.apply()
        _state.value = _state.value.copy(
            gate = Gate.KICKED,
            checkedAt = System.currentTimeMillis()
        )
    }

    fun signOut() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        _state.value = AuthState(gate = Gate.SIGNED_OUT)
    }

    private fun onGateTransition(prev: Gate, next: Gate) {
        if (next == Gate.APPROVED && prev != Gate.APPROVED) {
            SourceConfig.syncNow()
            RevWatch.kick()
            CloudSync.syncAllNow()
        }
    }
}
