package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore
import java.lang.ref.WeakReference

class CloudStreamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        app.rayeq.Rayeq.init(this)
    }

    companion object {
        @Volatile
        private var instance: CloudStreamApp? = null

        val context: Context?
            get() = instance?.applicationContext

        fun getActivity(): Activity? = CommonActivity.activity

        fun <T> setKey(path: String, value: T) {
            context?.let { DataStore.setKey(it, path, value) }
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.let { DataStore.setKey(it, folder, path, value) }
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T? = null): T? {
            return context?.let { DataStore.getKey(it, path, defVal) } ?: defVal
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T? = null): T? {
            return context?.let { DataStore.getKey(it, folder, path, defVal) } ?: defVal
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.let { DataStore.getKey(it, path, valueType) }
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.let { DataStore.setKey(it, path, value) }
        }

        fun getKeys(folder: String): List<String> {
            return context?.let { DataStore.getKeys(it, folder) } ?: emptyList()
        }

        fun removeKey(path: String) {
            context?.let { DataStore.removeKey(it, path) }
        }

        fun removeKey(folder: String, path: String) {
            context?.let { DataStore.removeKey(it, folder, path) }
        }

        fun removeKeys(folder: String): Int {
            return context?.let { DataStore.removeKeys(it, folder) } ?: 0
        }
    }
}

open class AcraApplication {
    companion object {
        val context: Context?
            get() = CloudStreamApp.context

        fun <T> setKey(path: String, value: T) = CloudStreamApp.setKey(path, value)
        fun <T> setKey(folder: String, path: String, value: T) = CloudStreamApp.setKey(folder, path, value)
        inline fun <reified T : Any> getKey(path: String, defVal: T? = null): T? = CloudStreamApp.getKey(path, defVal)
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T? = null): T? = CloudStreamApp.getKey(folder, path, defVal)
        fun removeKey(path: String) = CloudStreamApp.removeKey(path)
        fun removeKey(folder: String, path: String) = CloudStreamApp.removeKey(folder, path)
        fun removeKeys(folder: String): Int = CloudStreamApp.removeKeys(folder)
    }
}

class MainActivity {
    companion object {
        val afterPluginsLoadedEvent = com.lagradost.cloudstream3.utils.Event<Boolean>()
        val mainPluginsLoadedEvent = com.lagradost.cloudstream3.utils.Event<Boolean>()
        val afterRepositoryLoadedEvent = com.lagradost.cloudstream3.utils.Event<Boolean>()
        val bookmarksUpdatedEvent = com.lagradost.cloudstream3.utils.Event<Boolean>()
        val reloadHomeEvent = com.lagradost.cloudstream3.utils.Event<Boolean>()
        val reloadLibraryEvent = com.lagradost.cloudstream3.utils.Event<Boolean>()
        val reloadAccountEvent = com.lagradost.cloudstream3.utils.Event<Boolean>()
    }
}

object CommonActivity {
    private var activityRef: WeakReference<Activity>? = null

    val activity: Activity?
        get() = activityRef?.get()

    fun setActivityInstance(act: Activity?) {
        activityRef = if (act != null) WeakReference(act) else null
    }

    fun showToast(message: String?, duration: Int? = null) {}
    fun showToast(act: Activity?, message: String?, duration: Int? = null) {}
    fun showToast(messageRes: Int, duration: Int? = null) {}
    fun showToast(act: Activity?, messageRes: Int, duration: Int? = null) {}
}
