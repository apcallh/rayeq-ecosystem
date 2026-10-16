package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.BasePlugin
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.utils.DataStore.mapper
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int
)

object PluginManager {
    const val PLUGINS_KEY = "PLUGINS_KEY"
    const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE
    const val PLUGIN_VERSION_ALWAYS_UPDATE = -1
    const val PROVIDER_STATUS_DOWN = 0

    private val lock = Mutex()
    val plugins: MutableMap<String, BasePlugin> = ConcurrentHashMap()
    val classLoaders: MutableMap<String, PathClassLoader> = ConcurrentHashMap()
    val urlPlugins: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val failedToLoad: MutableMap<String, String> = ConcurrentHashMap()

    fun sanitize(input: String): String {
        return input.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
    }

    fun getPluginPath(context: Context, internalName: String, repositoryUrl: String): File {
        val repoFolder = "${sanitize(repositoryUrl)}.${repositoryUrl.hashCode()}"
        val fileName = "${sanitize(internalName)}.${internalName.hashCode()}.cs3"
        return File(
            File(context.filesDir, RepositoryManager.ONLINE_PLUGINS_FOLDER),
            "$repoFolder/$fileName"
        )
    }

    suspend fun getPluginsOnline(): Array<PluginData> = lock.withLock {
        CloudStreamApp.getKey(PLUGINS_KEY) ?: emptyArray()
    }

    private suspend fun setPluginsOnline(data: Array<PluginData>) = lock.withLock {
        CloudStreamApp.setKey(PLUGINS_KEY, data)
    }

    private suspend fun updatePluginData(data: PluginData) {
        val current = getPluginsOnline().filter { it.filePath != data.filePath }.toMutableList()
        current.add(data)
        setPluginsOnline(current.toTypedArray())
    }

    suspend fun loadPlugin(
        context: Context,
        file: File,
        data: PluginData
    ): Boolean = withContext(Dispatchers.IO) {
        val filePath = file.absolutePath
        try {
            if (!file.exists()) {
                failedToLoad[filePath] = "File missing"
                return@withContext false
            }
            // مطلوب في Android 14+ لأن تحميل dex قابل للكتابة ممنوع
            file.setReadOnly()

            unloadPlugin(filePath)

            val loader = PathClassLoader(filePath, context.classLoader)
            val manifestStream = loader.getResourceAsStream("manifest.json")
            if (manifestStream == null) {
                failedToLoad[filePath] = "No manifest"
                return@withContext false
            }

            val manifest: Plugin.Manifest = InputStreamReader(manifestStream).use { reader ->
                mapper.readValue(reader, Plugin.Manifest::class.java)
            }

            val pluginClassName = manifest.pluginClassName
            if (pluginClassName.isNullOrBlank()) {
                failedToLoad[filePath] = "Empty pluginClassName"
                return@withContext false
            }

            val pluginInstance = loader.loadClass(pluginClassName)
                .getDeclaredConstructor()
                .newInstance() as BasePlugin

            pluginInstance.filename = filePath
            updatePluginData(data.copy(version = manifest.version))

            if (pluginInstance is Plugin && manifest.requiresResources) {
                val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                addAssetPath.invoke(assets, filePath)
                @Suppress("DEPRECATION")
                pluginInstance.resources = Resources(
                    assets,
                    context.resources.displayMetrics,
                    context.resources.configuration
                )
            }

            plugins[filePath] = pluginInstance
            classLoaders[filePath] = loader
            data.url?.let { urlPlugins.add(it) }

            if (pluginInstance is Plugin) {
                pluginInstance.load(context)
            } else {
                pluginInstance.load()
            }

            failedToLoad.remove(filePath)
            true
        } catch (t: Throwable) {
            failedToLoad[filePath] = "${t.javaClass.simpleName}: ${t.message ?: "load_error"}".take(150)
            false
        }
    }

    fun unloadPlugin(filePath: String) {
        plugins.remove(filePath)
        classLoaders.remove(filePath)
        APIHolder.removePluginMapping(filePath)
    }

    suspend fun loadSinglePlugin(context: Context, internalName: String): Boolean {
        val match = getPluginsOnline().firstOrNull { it.internalName == internalName } ?: return false
        return loadPlugin(context, File(match.filePath), match)
    }

    suspend fun loadAllOnlinePlugins(context: Context) = coroutineScope {
        val saved = getPluginsOnline()
        saved.map { pd ->
            async(Dispatchers.IO) {
                loadPlugin(context, File(pd.filePath), pd)
            }
        }.awaitAll()
        APIHolder.initAll()
        MainActivity.afterPluginsLoadedEvent(false)
    }

    suspend fun deletePlugin(file: File): Boolean {
        val path = file.absolutePath
        unloadPlugin(path)
        failedToLoad.remove(path)
        val remaining = getPluginsOnline().filter { it.filePath != path }.toTypedArray()
        setPluginsOnline(remaining)
        return if (file.exists()) file.delete() else true
    }
}
