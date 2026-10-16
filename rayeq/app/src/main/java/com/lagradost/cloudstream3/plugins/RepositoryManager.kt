package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.DataStore.mapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Repository(
    val iconUrl: String? = null,
    val name: String = "",
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList()
)

data class SitePlugin(
    val url: String = "",
    val status: Int = 1,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val name: String = "",
    val internalName: String = "",
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val repositoryUrl: String? = null,
    val tvTypes: List<String>? = null,
    val language: String? = null,
    val iconUrl: String? = null,
    val fileSize: Long? = null,
    val fileHash: String? = null
)

data class PluginWrapper(
    val repository: RepositoryData,
    val plugin: SitePlugin
)

object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"
    private const val REPOSITORIES_KEY = "REPOSITORIES_LIST"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun parseRepoUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("cloudstreamrepo://", ignoreCase = true)) {
            "https://" + trimmed.substring("cloudstreamrepo://".length)
        } else {
            trimmed
        }
    }

    suspend fun parseRepository(url: String): Repository? = withContext(Dispatchers.IO) {
        try {
            val norm = parseRepoUrl(url)
            val req = Request.Builder().url(norm).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string()?.trimStart('﻿') ?: return@withContext null
                if (body.trimStart().startsWith("[")) {
                    Repository(name = norm, pluginLists = listOf(norm))
                } else {
                    mapper.readValue(body, Repository::class.java)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getRepoPlugins(repo: RepositoryData): List<PluginWrapper>? = withContext(Dispatchers.IO) {
        val parsed = parseRepository(repo.url) ?: return@withContext null
        val out = mutableListOf<PluginWrapper>()
        for (listUrl in parsed.pluginLists) {
            try {
                val req = Request.Builder().url(listUrl.trim()).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string()?.trimStart('﻿') ?: return@use
                    val arr = mapper.readValue(text, Array<SitePlugin>::class.java)
                    for (p in arr) {
                        out.add(PluginWrapper(repo, p))
                    }
                }
            } catch (_: Exception) {}
        }
        out
    }

    suspend fun downloadPluginToFile(
        context: Context,
        url: String,
        targetFile: File,
        expectedFileHash: String? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            targetFile.parentFile?.mkdirs()
            val safeUrl = url.trim().replace(" ", "%20")
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            val req = Request.Builder().url(safeUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (!expectedFileHash.isNullOrBlank()) {
                    val cleanExpected = expectedFileHash.removePrefix("0x").lowercase()
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    val hex = digest.joinToString("") { "%02x".format(it) }
                    if (hex != cleanExpected) {
                        return@withContext null
                    }
                }
                tmpFile.writeBytes(bytes)
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            targetFile.setReadOnly()
            targetFile
        } catch (_: Exception) {
            null
        }
    }

    fun getRepositories(): Array<RepositoryData> {
        return CloudStreamApp.getKey(REPOSITORIES_KEY) ?: emptyArray()
    }

    fun addRepository(repo: RepositoryData) {
        val cur = getRepositories().toMutableList()
        if (cur.none { it.url == repo.url }) {
            cur.add(repo)
            CloudStreamApp.setKey(REPOSITORIES_KEY, cur.toTypedArray())
        }
    }

    fun removeRepository(context: Context, repo: RepositoryData) {
        val cur = getRepositories().filter { it.url != repo.url }
        CloudStreamApp.setKey(REPOSITORIES_KEY, cur.toTypedArray())
        val folder = File(
            context.filesDir,
            "$ONLINE_PLUGINS_FOLDER/${PluginManager.sanitize(repo.url)}.${repo.url.hashCode()}"
        )
        if (folder.exists()) {
            folder.deleteRecursively()
        }
    }
}
