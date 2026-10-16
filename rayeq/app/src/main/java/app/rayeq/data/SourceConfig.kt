package app.rayeq.data

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ServerRef(
    val n: Int,
    val priority: Int,
    val repo: String,
    val internalName: String,
    val version: Int,
    val lang: String,
    val langSource: String = "plugin",
    val arabic: Boolean,
    val kind: String = "auto",
    val live: Boolean = false,
    val tool: Boolean = false,
    val tvTypes: List<String> = emptyList(),
    val category: String = "عام"
)

@Serializable
data class StoredPluginMeta(
    val language: String = "",
    val tvTypes: List<String> = emptyList(),
    val repo: String = "",
    val repoArabic: Boolean = false
)

object SourceConfig {
    private const val PREFS = "rayeq_sources_prefs"
    private const val STALE_MS = 120_000L // دقيقتان

    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    @Volatile private var lastSyncAt: Long = 0L
    @Volatile private var activeSyncJob: Job? = null

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile private var allowedNow: Set<String>? = emptySet()
    private val order = ConcurrentHashMap<String, Int>()
    private val refs = ConcurrentHashMap<String, ServerRef>()
    private val adminLang = ConcurrentHashMap<String, Boolean>()
    private val pluginMetaMap = ConcurrentHashMap<String, StoredPluginMeta>()
    val nameMap = ConcurrentHashMap<String, String>() // MainAPI.name -> internalName

    fun init(context: Context) {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString("plugin_meta", null)?.let { raw ->
            try {
                val decoded = json.decodeFromString<Map<String, StoredPluginMeta>>(raw)
                pluginMetaMap.putAll(decoded)
            } catch (_: Exception) {}
        }
        sp.getString("sources_last", null)?.let { raw ->
            quickApply(raw, persist = false)
        }
        scope.launch {
            PluginManager.loadAllOnlinePlugins(context)
            rebuildNameMap()
            _version.value += 1
            refreshMetaIfNeeded()
            syncNow()
        }
        // مزامنة دورية كل 15 دقيقة أثناء التشغيل
        scope.launch {
            while (isActive) {
                delay(15 * 60_000L)
                syncNow()
            }
        }
    }

    private suspend fun rebuildNameMap() {
        val online = PluginManager.getPluginsOnline().associateBy { it.filePath }
        for (api in APIHolder.allProviders) {
            val iname = api.sourcePlugin?.let { online[it]?.internalName }
            if (!iname.isNullOrBlank()) {
                nameMap[api.name] = iname
            }
        }
    }

    /**
     * quickApply خارج القفل فوراً حتى تختفي الإضافة المسحوبة خلال ثوانٍ (§7.4.1)
     */
    fun quickApply(rawBody: String, persist: Boolean = true) {
        try {
            val obj = JSONObject(rawBody)
            val sp = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prevBody = sp?.getString("sources_last", null)
            if (persist && sp != null) {
                sp.edit().putString("sources_last", rawBody).apply()
            }

            val pluginsArr = obj.optJSONArray("plugins") ?: JSONArray()
            val pluginsSet = mutableSetOf<String>()
            for (i in 0 until pluginsArr.length()) {
                val p = pluginsArr.optString(i).trim()
                if (p.isNotEmpty()) pluginsSet.add(p)
            }

            val newOrder = mutableMapOf<String, Int>()
            val newRefs = mutableMapOf<String, ServerRef>()
            val newAdminLang = mutableMapOf<String, Boolean>()

            val serversArr = obj.optJSONArray("servers") ?: JSONArray()
            for (i in 0 until serversArr.length()) {
                val s = serversArr.getJSONObject(i)
                val iname = s.optString("internalName").trim()
                if (iname.isEmpty()) continue
                pluginsSet.add(iname)
                val prio = s.optInt("priority", i + 1)
                val nVal = s.optInt("n", i + 1)
                newOrder[iname] = prio
                val tArr = s.optJSONArray("tvTypes") ?: JSONArray()
                val tvTypes = (0 until tArr.length()).map { tArr.optString(it) }
                val lSource = s.optString("langSource", "plugin")
                val ar = s.optBoolean("arabic", s.optString("lang") == "ar")
                if (lSource == "admin") {
                    newAdminLang[iname] = ar
                }
                newRefs[iname] = ServerRef(
                    n = nVal,
                    priority = prio,
                    repo = s.optString("repo"),
                    internalName = iname,
                    version = s.optInt("version", 0),
                    lang = s.optString("lang", if (ar) "ar" else "other"),
                    langSource = lSource,
                    arabic = ar,
                    kind = s.optString("kind", "auto"),
                    live = s.optBoolean("live", false),
                    tool = s.optBoolean("tool", false),
                    tvTypes = tvTypes,
                    category = s.optString("category", "عام")
                )
            }

            val toolsArr = obj.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until toolsArr.length()) {
                val t = toolsArr.getJSONObject(i)
                val iname = t.optString("internalName").trim()
                if (iname.isEmpty()) continue
                pluginsSet.add(iname)
                newRefs[iname] = ServerRef(
                    n = 0,
                    priority = 9999,
                    repo = t.optString("repo"),
                    internalName = iname,
                    version = t.optInt("version", 0),
                    lang = t.optString("lang", "other"),
                    arabic = t.optBoolean("arabic", false),
                    kind = t.optString("kind", "auto"),
                    live = false,
                    tool = true,
                    tvTypes = emptyList(),
                    category = "أدوات"
                )
            }

            order.clear()
            order.putAll(newOrder)
            refs.clear()
            refs.putAll(newRefs)
            adminLang.clear()
            adminLang.putAll(newAdminLang)
            allowedNow = if ("*" in pluginsSet) null else pluginsSet

            if (rawBody != prevBody) {
                _version.value += 1
            }
        } catch (_: Exception) {}
    }

    fun syncNow(): Job {
        val job = scope.launch {
            runSync()
        }
        activeSyncJob = job
        return job
    }

    fun syncIfStale() {
        if (System.currentTimeMillis() - lastSyncAt > STALE_MS) {
            syncNow()
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 45_000L) {
        val currentJob = activeSyncJob
        if (currentJob != null && currentJob.isActive) {
            withTimeoutOrNull(timeoutMs) { currentJob.join() }
            return
        }
        if (System.currentTimeMillis() - lastSyncAt > STALE_MS) {
            withTimeoutOrNull(timeoutMs) { runSync() }
        }
    }

    private suspend fun runSync() {
        val ctx = appContext ?: return
        // 1. GET /v1/sources خارج القفل وتطبيق quickApply فوراً
        val rawBody = Net.get("/v1/sources") ?: return
        quickApply(rawBody, persist = true)

        // 2. تحت القفل: الحذف أولاً ثم قراءة المستودعات والتنزيل
        syncMutex.withLock {
            val allowed = allowedNow ?: return@withLock
            val obj = try { JSONObject(rawBody) } catch (_: Exception) { return@withLock }
            val reposArr = obj.optJSONArray("repos") ?: JSONArray()
            val wantedRepos = (0 until reposArr.length())
                .map { RepositoryManager.parseRepoUrl(reposArr.optString(it)) }
                .filter { it.isNotEmpty() }
                .toSet()

            var changed = false

            // احذف أولاً كل إضافة مثبتة ليست في allowed قبل قراءة أي مستودع
            val installedBefore = PluginManager.getPluginsOnline()
            for (pd in installedBefore) {
                if (pd.internalName !in allowed) {
                    PluginManager.deletePlugin(File(pd.filePath))
                    changed = true
                }
            }
            if (changed) {
                APIHolder.initAll()
                rebuildNameMap()
                _version.value += 1
            }

            // احذف المستودعات غير المطلوبة
            for (savedRepo in RepositoryManager.getRepositories()) {
                if (savedRepo.url !in wantedRepos) {
                    RepositoryManager.removeRepository(ctx, savedRepo)
                }
            }

            var readFailed = false
            val availableByName = mutableMapOf<String, Pair<RepositoryData, com.lagradost.cloudstream3.plugins.SitePlugin>>()

            for (repoUrl in wantedRepos) {
                val repoData = RepositoryData(name = repoUrl, url = repoUrl)
                RepositoryManager.addRepository(repoData)
                val wrappers = RepositoryManager.getRepoPlugins(repoData)
                if (wrappers == null) {
                    readFailed = true
                    continue
                }
                val repoAr = ServerLang.repoIsArabic(wrappers.mapNotNull { it.plugin.language })
                for (w in wrappers) {
                    val p = w.plugin
                    if (p.internalName.isBlank()) continue
                    availableByName[p.internalName] = repoData to p
                    pluginMetaMap[p.internalName] = StoredPluginMeta(
                        language = p.language.orEmpty(),
                        tvTypes = p.tvTypes.orEmpty(),
                        repo = repoUrl,
                        repoArabic = repoAr
                    )
                }
            }

            val currentInstalled = PluginManager.getPluginsOnline().associateBy { it.internalName }
            val reportEntries = JSONArray()

            for (iname in allowed) {
                val ref = refs[iname]
                val avail = availableByName[iname]
                val installed = currentInstalled[iname]

                if (avail == null) {
                    if (!readFailed) {
                        reportEntries.put(
                            JSONObject()
                                .put("internalName", iname)
                                .put("version", ref?.version ?: 0)
                                .put("ok", false)
                                .put("error", "not_found_in_repo")
                        )
                    }
                    continue
                }

                val (repoData, sitePlugin) = avail
                if (sitePlugin.status == PluginManager.PROVIDER_STATUS_DOWN) {
                    reportEntries.put(
                        JSONObject()
                            .put("internalName", iname)
                            .put("version", sitePlugin.version)
                            .put("ok", false)
                            .put("error", "marked_down_in_repo")
                    )
                    continue
                }

                val approvedCap = ref?.version ?: 0
                val targetFile = PluginManager.getPluginPath(ctx, iname, repoData.url)
                val needsDownload = installed == null ||
                    !File(installed.filePath).exists() ||
                    (installed.version < sitePlugin.version && (approvedCap == 0 || sitePlugin.version <= approvedCap))

                if (needsDownload) {
                    val downloaded = RepositoryManager.downloadPluginToFile(
                        ctx,
                        sitePlugin.url,
                        targetFile,
                        sitePlugin.fileHash
                    )
                    if (downloaded != null) {
                        val pd = PluginData(
                            internalName = iname,
                            url = sitePlugin.url,
                            isOnline = true,
                            filePath = downloaded.absolutePath,
                            version = sitePlugin.version
                        )
                        val loaded = PluginManager.loadPlugin(ctx, downloaded, pd)
                        changed = true
                        reportEntries.put(
                            JSONObject()
                                .put("internalName", iname)
                                .put("version", sitePlugin.version)
                                .put("ok", loaded)
                                .put("error", if (loaded) JSONObject.NULL else (PluginManager.failedToLoad[downloaded.absolutePath] ?: "load_failed"))
                        )
                    } else {
                        reportEntries.put(
                            JSONObject()
                                .put("internalName", iname)
                                .put("version", sitePlugin.version)
                                .put("ok", false)
                                .put("error", "download_failed")
                        )
                    }
                } else if (installed != null) {
                    val isLoaded = PluginManager.plugins.containsKey(installed.filePath)
                    val ok = if (!isLoaded) {
                        changed = true
                        PluginManager.loadPlugin(ctx, File(installed.filePath), installed)
                    } else true
                    reportEntries.put(
                        JSONObject()
                            .put("internalName", iname)
                            .put("version", installed.version)
                            .put("ok", ok)
                            .put("error", if (ok) JSONObject.NULL else (PluginManager.failedToLoad[installed.filePath] ?: "load_failed"))
                    )
                }
            }

            if (!readFailed) {
                for (pd in PluginManager.getPluginsOnline()) {
                    if (pd.internalName !in availableByName) {
                        PluginManager.deletePlugin(File(pd.filePath))
                        changed = true
                    }
                }
            }

            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putString("plugin_meta", json.encodeToString(pluginMetaMap.toMap())).apply()

            if (changed) {
                APIHolder.initAll()
                rebuildNameMap()
                _version.value += 1
            }

            lastSyncAt = System.currentTimeMillis()
            sendReportIfNeeded(sp, reportEntries)
        }
    }

    private suspend fun refreshMetaIfNeeded() {
        val installed = PluginManager.getPluginsOnline()
        if (installed.isEmpty()) return
        if (installed.all { pluginMetaMap.containsKey(it.internalName) }) return
        for (repo in RepositoryManager.getRepositories()) {
            val wrappers = RepositoryManager.getRepoPlugins(repo) ?: continue
            val repoAr = ServerLang.repoIsArabic(wrappers.mapNotNull { it.plugin.language })
            for (w in wrappers) {
                pluginMetaMap[w.plugin.internalName] = StoredPluginMeta(
                    language = w.plugin.language.orEmpty(),
                    tvTypes = w.plugin.tvTypes.orEmpty(),
                    repo = repo.url,
                    repoArabic = repoAr
                )
            }
        }
        _version.value += 1
    }

    private suspend fun sendReportIfNeeded(
        sp: android.content.SharedPreferences,
        pluginsArr: JSONArray
    ) {
        if (pluginsArr.length() == 0) return
        val sb = StringBuilder()
        for (i in 0 until pluginsArr.length()) {
            val o = pluginsArr.getJSONObject(i)
            sb.append("${o.optString("internalName")}:${o.optInt("version")}:${o.optBoolean("ok")}:${o.optString("error")}|")
        }
        val sig = sb.toString()
        val today = System.currentTimeMillis() / 86_400_000L
        val lastSig = sp.getString("last_report_sig", null)
        val lastDay = sp.getLong("last_report_day", -1L)
        if (sig == lastSig && today == lastDay) return

        val code = Net.postJson("/v1/sources/report", JSONObject().put("plugins", pluginsArr))
        if (code in 200..299) {
            sp.edit().putString("last_report_sig", sig).putLong("last_report_day", today).apply()
        }
    }

    private fun internalNameOf(api: MainAPI): String? {
        nameMap[api.name]?.let { return it }
        val path = api.sourcePlugin ?: return null
        val fn = File(path).nameWithoutExtension
        return fn.substringBeforeLast('.')
    }

    fun allowed(api: MainAPI): Boolean {
        val currentAllowed = allowedNow ?: return true
        val iname = internalNameOf(api) ?: return false
        return iname in currentAllowed
    }

    fun revokedName(providerDisplayName: String): Boolean {
        val currentAllowed = allowedNow ?: return false
        val iname = nameMap[providerDisplayName] ?: return true
        return iname !in currentAllowed
    }

    fun rank(api: MainAPI): Int {
        val iname = internalNameOf(api) ?: return 9999
        return order[iname] ?: 9999
    }

    fun isArabic(api: MainAPI): Boolean {
        val iname = internalNameOf(api)
        val ref = iname?.let { refs[it] }
        val meta = iname?.let { pluginMetaMap[it] }
        val adm = iname?.let { adminLang[it] }
        return ServerLang.resolve(
            adminOverride = adm,
            pluginJsonLang = meta?.language,
            serverLang = ref?.lang,
            serverArabic = ref?.arabic,
            runtimeLang = api.lang,
            repoMajorityArabic = meta?.repoArabic ?: false,
            repoUrl = ref?.repo ?: meta?.repo
        )
    }

    fun isLive(api: MainAPI): Boolean {
        val iname = internalNameOf(api)
        val ref = iname?.let { refs[it] }
        val meta = iname?.let { pluginMetaMap[it] }
        return LiveDetect.isLive(
            kindOverride = ref?.kind,
            serverLiveFlag = ref?.live,
            runtimeTypes = api.supportedTypes.map { it.name },
            pluginJsonTypes = meta?.tvTypes,
            serverTypes = ref?.tvTypes,
            name = api.name,
            internalName = iname
        )
    }

    fun isTool(api: MainAPI): Boolean {
        val iname = internalNameOf(api)
        val ref = iname?.let { refs[it] }
        if (ref?.tool == true) return true
        val meta = iname?.let { pluginMetaMap[it] }
        val merged = LiveDetect.normalizeTypes(
            api.supportedTypes.map { it.name },
            meta?.tvTypes,
            ref?.tvTypes
        )
        return LiveDetect.isNotContent(api.name, iname, merged, ref?.kind)
    }
}
