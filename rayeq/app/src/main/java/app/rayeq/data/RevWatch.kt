package app.rayeq.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import app.rayeq.BuildConfig
import app.rayeq.auth.Auth
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

object RevWatch {
    private const val PREFS = "rayeq_rev_prefs"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startedActivities = AtomicInteger(0)
    private var appContext: Context? = null
    @Volatile private var loopJob: Job? = null

    fun init(app: Application) {
        appContext = app.applicationContext
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities.incrementAndGet() == 1) {
                    SourceConfig.syncIfStale()
                    scope.launch { Auth.heartbeatNow() }
                    startLoop()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities.decrementAndGet() <= 0) {
                    startedActivities.set(0)
                    stopLoop()
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (startedActivities.get() > 0) {
                        kick()
                    }
                }
            })
        } catch (_: Exception) {}
    }

    fun kick() {
        stopLoop()
        if (startedActivities.get() > 0) {
            startLoop()
        }
    }

    private fun startLoop() {
        if (!BuildConfig.AUTH_ENABLED) return
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            val sp = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var currentToken = sp?.getString("rev_token", "") ?: ""
            var firstIteration = true

            while (isActive && startedActivities.get() > 0) {
                if (Auth.token().isNullOrBlank()) {
                    delay(3000L)
                    continue
                }
                val waitSec = if (firstIteration || currentToken.isEmpty()) 0 else 20
                firstIteration = false
                val encodedSince = URLEncoder.encode(currentToken, "UTF-8")
                val path = "/v1/rev?wait=$waitSec&since=$encodedSince"
                val t0 = System.currentTimeMillis()
                val (code, body) = Net.getWithCode(path, longPoll = waitSec > 0)
                val elapsed = System.currentTimeMillis() - t0

                when (code) {
                    200 -> {
                        if (body != null) {
                            try {
                                val obj = JSONObject(body)
                                Auth.applyRev(obj)
                                val newRev = obj.optString("rev", "")
                                if (newRev.isNotEmpty() && newRev != currentToken) {
                                    currentToken = newRev
                                    sp?.edit()?.putString("rev_token", newRev)?.apply()
                                    SourceConfig.syncNow()
                                    AppInfo.refresh()
                                } else if (waitSec > 0 && elapsed < 2500L) {
                                    // رد فوري بنفس التوكن (proxy قطع الانتظار) -> انتظر 3 ثوانٍ
                                    delay(3000L)
                                }
                            } catch (_: Exception) {
                                delay(3000L)
                            }
                        }
                    }
                    304 -> {
                        // لا تغيير عند wait=0
                    }
                    401 -> {
                        Auth.markKicked()
                        delay(3000L)
                    }
                    404 -> {
                        delay(60_000L)
                    }
                    else -> {
                        delay(3000L)
                    }
                }
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }
}
