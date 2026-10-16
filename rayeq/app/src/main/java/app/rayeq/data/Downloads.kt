package app.rayeq.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class DownloadTask(
    val id: String,
    val title: Title,
    val season: Int? = null,
    val episode: Int? = null,
    val url: String,
    val isHls: Boolean = false,
    val localPath: String,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String = "queued" // queued | downloading | paused | completed | failed
)

object Downloads {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    fun targetDir(context: Context): File {
        val external = Prefs.settings.value.downloadLocation == "external"
        val base = if (external) context.getExternalFilesDir("downloads") ?: context.filesDir else context.filesDir
        return File(base, "rayeq_downloads").apply { mkdirs() }
    }

    fun enqueue(context: Context, title: Title, season: Int?, episode: Int?, url: String, isHls: Boolean) {
        val id = Library.epKey(title.key, season, episode)
        val ext = if (isHls) "m3u8" else "mp4"
        val file = File(targetDir(context), "${id.replace(':', '_')}.$ext")
        val task = DownloadTask(
            id = id,
            title = title,
            season = season,
            episode = episode,
            url = url,
            isHls = isHls,
            localPath = file.absolutePath
        )
        _tasks.value = listOf(task) + _tasks.value.filterNot { it.id == id }
    }

    fun remove(id: String) {
        val t = _tasks.value.find { it.id == id }
        t?.let { File(it.localPath).delete() }
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }
}

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
