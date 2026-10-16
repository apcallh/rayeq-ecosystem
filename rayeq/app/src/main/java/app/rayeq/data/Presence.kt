package app.rayeq.data

import app.rayeq.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object Presence {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentState: String = "browsing" // browsing | watching | background
    private val watchSecondsAccum = AtomicInteger(0)
    private val screensAccum = AtomicInteger(0)

    fun init() {
        scope.launch {
            while (isActive) {
                delay(60_000L)
                if (currentState == "watching" || currentState == "background") {
                    watchSecondsAccum.addAndGet(60)
                }
                flushNow()
            }
        }
    }

    fun setState(state: String) {
        currentState = state
    }

    fun onTitleScreenOpened() {
        screensAccum.incrementAndGet()
        scope.launch { flushNow() }
    }

    fun addWatchedSeconds(sec: Int) {
        if (sec > 0) watchSecondsAccum.addAndGet(sec)
    }

    suspend fun flushNow() {
        val secDelta = watchSecondsAccum.getAndSet(0).coerceIn(0, 900)
        val scrDelta = screensAccum.getAndSet(0).coerceIn(0, 100)
        // لا تُرسل أسماء الأعمال أو الحلقات في presence أبداً (§2.9)
        val body = JSONObject()
            .put("state", currentState)
            .put("seconds_watched_delta", secDelta)
            .put("screens_delta", scrDelta)
            .put("app_version", BuildConfig.VERSION_NAME)

        val code = Net.postJson("/v1/presence", body)
        if (code !in 200..299) {
            // الفروقات التي لم تُرسل تُجمع للمحاولة التالية، والفشل صامت
            watchSecondsAccum.addAndGet(secDelta)
            screensAccum.addAndGet(scrDelta)
        }
    }
}
