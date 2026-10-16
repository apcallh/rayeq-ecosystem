package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object DataStore {
    val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    fun getSharedPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)

    fun getFolderName(folder: String, path: String): String = "${folder}/${path}"

    fun <T> setKey(context: Context, path: String, value: T) {
        try {
            val editor = getSharedPrefs(context).edit()
            editor.putString(path, mapper.writeValueAsString(value))
            editor.apply()
        } catch (_: Exception) {}
    }

    fun <T> setKey(context: Context, folder: String, path: String, value: T) {
        setKey(context, getFolderName(folder, path), value)
    }

    fun <T : Any> getKey(context: Context, path: String, valueType: Class<T>): T? {
        return try {
            val json = getSharedPrefs(context).getString(path, null) ?: return null
            mapper.readValue(json, valueType)
        } catch (_: Exception) {
            null
        }
    }

    inline fun <reified T : Any> getKey(context: Context, path: String, defVal: T? = null): T? {
        return try {
            val json = getSharedPrefs(context).getString(path, null) ?: return defVal
            mapper.readValue(json, T::class.java)
        } catch (_: Exception) {
            defVal
        }
    }

    inline fun <reified T : Any> getKey(
        context: Context,
        folder: String,
        path: String,
        defVal: T? = null
    ): T? = getKey(context, getFolderName(folder, path), defVal)

    fun getKeys(context: Context, folder: String): List<String> {
        return getSharedPrefs(context).all.keys.filter { it.startsWith("$folder/") }
    }

    fun removeKey(context: Context, path: String) {
        getSharedPrefs(context).edit().remove(path).apply()
    }

    fun removeKey(context: Context, folder: String, path: String) {
        removeKey(context, getFolderName(folder, path))
    }

    fun removeKeys(context: Context, folder: String): Int {
        val keys = getKeys(context, folder)
        val editor = getSharedPrefs(context).edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
        return keys.size
    }
}

class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()

    @Synchronized
    operator fun plusAssign(observer: (T) -> Unit) {
        observers.add(observer)
    }

    @Synchronized
    operator fun minusAssign(observer: (T) -> Unit) {
        observers.remove(observer)
    }

    @Synchronized
    operator fun invoke(value: T) {
        observers.toList().forEach { it.invoke(value) }
    }
}

class EmptyEvent {
    private val observers = mutableSetOf<() -> Unit>()

    @Synchronized
    operator fun plusAssign(observer: () -> Unit) {
        observers.add(observer)
    }

    @Synchronized
    operator fun minusAssign(observer: () -> Unit) {
        observers.remove(observer)
    }

    @Synchronized
    operator fun invoke() {
        observers.toList().forEach { it.invoke() }
    }
}
