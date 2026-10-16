package app.rayeq.data

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class EpisodeCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!Prefs.settings.value.notifyNewEpisodes) return Result.success()
        return Result.success()
    }
}

object Notify {
    fun schedule(context: Context) {
        val hours = Prefs.settings.value.notifyIntervalHours.coerceAtLeast(1).toLong()
        val req = PeriodicWorkRequestBuilder<EpisodeCheckWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "rayeq_episode_notify",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }
}
