package app.rayeq.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen() {
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var newRepoUrl by remember { mutableStateOf("") }
    var inspectResult by remember { mutableStateOf<Inspect?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedRepoUrl by remember { mutableStateOf<String?>(null) }
    var selectedPluginInfo by remember { mutableStateOf<Plugin?>(null) }
    var confirmRemoveRepo by remember { mutableStateOf<String?>(null) }

    suspend fun loadCatalog(fresh: Boolean = false) {
        loading = true
        errorMsg = null
        val path = if (fresh) "/admin/catalog?fresh=1" else "/admin/catalog"
        val res = Api.call("GET", path, timeoutMs = 45000)
        if (res.ok && res.data != null) {
            val rArr = res.data.optJSONArray("repos") ?: JSONArray()
            val list = mutableListOf<Repo>()
            for (i in 0 until rArr.length()) {
                val r = rArr.getJSONObject(i)
                val pArr = r.optJSONArray("plugins") ?: JSONArray()
                val plugins = (0 until pArr.length()).map { idx ->
                    Api.parsePlugin(pArr.getJSONObject(idx))
                }
                list.add(
                    Repo(
                        url = r.optString("url"),
                        name = r.optString("name"),
                        description = r.optString("description"),
                        iconUrl = if (r.isNull("iconUrl")) null else r.optString("iconUrl"),
                        addedAt = r.optLong("addedAt"),
                        lastFetched = if (r.isNull("lastFetched")) null else r.optLong("lastFetched"),
                        error = if (r.isNull("error")) null else r.optString("error"),
                        ok = r.optBoolean("ok", true),
                        pluginCount = r.optInt("pluginCount", plugins.size),
                        installedCount = r.optInt("installedCount", 0),
                        plugins = plugins
                    )
                )
            }
            repos = list
        } else {
            errorMsg = res.error
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        loadCatalog(false)
    }

    val openRepo = repos.find { it.url == selectedRepoUrl }
    if (openRepo != null) {
        RepoDetailView(
            repo = openRepo,
            onBack = { selectedRepoUrl = null },
            onRefresh = { scope.launch { loadCatalog(true) } },
            onDeleteRepo = { confirmRemoveRepo = openRepo.url },
            onPluginInfo = { selectedPluginInfo = it },
            onReloadCatalog = { scope.launch { loadCatalog(false) } }
        )
    } else {
        // القائمة الرئيسية للمستودعات (لا يوجد أي مستودع مضاف مسبقاً ولا قائمة اقتراحات)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "المستودعات (${repos.size.ar()})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { scope.launch { loadCatalog(true) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("إضافة مستودع جديد", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = newRepoUrl,
                            onValueChange = { newRepoUrl = it },
                            placeholder = { Text("https://.../repo.json") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        errorMsg = null
                                        val res = Api.call(
                                            "POST",
                                            "/admin/repos/inspect",
                                            JSONObject().put("url", newRepoUrl.trim()),
                                            timeoutMs = 45000
                                        )
                                        if (res.ok && res.data != null) {
                                            val rp = res.data.optJSONObject("repo") ?: JSONObject()
                                            val pArr = res.data.optJSONArray("plugins") ?: JSONArray()
                                            val pls = (0 until pArr.length()).map {
                                                Api.parsePlugin(pArr.getJSONObject(it))
                                            }
                                            val eArr = res.data.optJSONArray("listErrors") ?: JSONArray()
                                            val errs = (0 until eArr.length()).map {
                                                eArr.getJSONObject(it).optString("error")
                                            }
                                            inspectResult = Inspect(
                                                url = rp.optString("url"),
                                                name = rp.optString("name"),
                                                description = rp.optString("description"),
                                                iconUrl = if (rp.isNull("iconUrl")) null else rp.optString("iconUrl"),
                                                plugins = pls,
                                                listErrors = errs
                                            )
                                        } else {
                                            errorMsg = res.error
                                        }
                                    }
                                },
                                enabled = newRepoUrl.isNotBlank()
                            ) {
                                Text("معاينة")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        errorMsg = null
                                        val res = Api.call(
                                            "POST",
                                            "/admin/repos",
                                            JSONObject().put("url", newRepoUrl.trim()),
                                            timeoutMs = 45000
                                        )
                                        if (res.ok) {
                                            newRepoUrl = ""
                                            inspectResult = null
                                            loadCatalog(false)
                                        } else {
                                            errorMsg = res.error
                                        }
                                    }
                                },
                                enabled = newRepoUrl.isNotBlank()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("إضافة")
                            }
                        }

                        val insp = inspectResult
                        if (insp != null) {
                            Divider()
                            Text("المعاينة: ${insp.name}", fontWeight = FontWeight.Bold)
                            if (insp.description.isNotBlank()) {
                                Text(insp.description, fontSize = 12.sp)
                            }
                            Text("عدد الإضافات المتاحة: ${insp.plugins.size.ar()}", fontSize = 12.sp)
                            if (insp.listErrors.isNotEmpty()) {
                                Text(
                                    "أخطاء قوائم فرعية: ${insp.listErrors.joinToString()}",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (errorMsg != null) {
                            Text(errorMsg!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (repos.isEmpty() && !loading) {
                item {
                    Text(
                        "لا توجد مستودعات مضافة بعد.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            items(repos, key = { it.url }) { r ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedRepoUrl = r.url },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(r.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            StatusChip(
                                text = "${r.installedCount.ar()} / ${r.pluginCount.ar()} مثبتة",
                                color = if (r.ok) MaterialTheme.colorScheme.primary else Color(0xFFEF4444)
                            )
                        }
                        if (r.description.isNotBlank()) {
                            Text(r.description, fontSize = 12.sp)
                        }
                        if (!r.ok && r.error != null) {
                            Text(
                                "خطأ القراءة: ${Api.errText(r.error)}",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    val pInfo = selectedPluginInfo
    if (pInfo != null) {
        ModalBottomSheet(onDismissRequest = { selectedPluginInfo = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(pInfo.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("الاسم الداخلي: ${pInfo.internalName} · الإصدار: ${pInfo.version.ar()}", fontSize = 12.sp)
                if (pInfo.description.isNotBlank()) Text(pInfo.description, fontSize = 13.sp)
                if (pInfo.authors.isNotEmpty()) {
                    Text("المؤلفون: ${pInfo.authors.joinToString()}", fontSize = 12.sp)
                }
                Text("الفئة: ${pInfo.category} · اللغة: ${pInfo.language.ifBlank { "-" }}", fontSize = 12.sp)
                val rep = pInfo.report
                if (rep != null) {
                    Divider()
                    Text(
                        "تقارير الأجهزة: نجاح ${rep.ok.ar()} · فشل ${rep.fail.ar()}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    if (!rep.error.isNullOrBlank()) {
                        Text("آخر خطأ: ${rep.error}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val remUrl = confirmRemoveRepo
    if (remUrl != null) {
        AlertDialog(
            onDismissRequest = { confirmRemoveRepo = null },
            title = { Text("حذف المستودع") },
            text = { Text("سيتم حذف المستودع وجميع السيرفرات المثبتة منه. هل أنت متأكد؟") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveRepo = null
                    selectedRepoUrl = null
                    scope.launch {
                        Api.call("POST", "/admin/repos/remove", JSONObject().put("url", remUrl))
                        loadCatalog(false)
                    }
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveRepo = null }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
fun RepoDetailView(
    repo: Repo,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteRepo: () -> Unit,
    onPluginInfo: (Plugin) -> Unit,
    onReloadCatalog: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }

    val filtered = remember(repo.plugins, search, statusFilter) {
        repo.plugins.filter { p ->
            val matchSt = statusFilter == "all" || p.pluginStatus == statusFilter
            val q = search.trim().lowercase()
            val matchQ = q.isEmpty() || p.name.lowercase().contains(q) || p.internalName.lowercase().contains(q)
            matchSt && matchQ
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                }
                Text(repo.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Row {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "إعادة القراءة")
                }
                IconButton(onClick = onDeleteRepo) {
                    Icon(Icons.Default.Delete, contentDescription = "حذف المستودع", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    Api.call(
                        "POST",
                        "/admin/plugins",
                        JSONObject().put("repo", repo.url).put("all", "install"),
                        timeoutMs = 45000
                    )
                    onReloadCatalog()
                }
            }) { Text("تثبيت الكل") }

            OutlinedButton(onClick = {
                scope.launch {
                    Api.call(
                        "POST",
                        "/admin/plugins",
                        JSONObject().put("repo", repo.url).put("all", "uninstall"),
                        timeoutMs = 45000
                    )
                    onReloadCatalog()
                }
            }) { Text("إلغاء تثبيت الكل") }

            OutlinedButton(onClick = {
                scope.launch {
                    Api.call(
                        "POST",
                        "/admin/plugins/update",
                        JSONObject().put("repo", repo.url).put("all", true),
                        timeoutMs = 45000
                    )
                    onReloadCatalog()
                }
            }) { Text("تحديث الكل") }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("بحث في إضافات المستودع…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        val stFilters = listOf(
            "all" to "الكل",
            "installed" to "مثبتة",
            "update" to "متاح تحديث",
            "failed" to "فشل",
            "available" to "متاحة"
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stFilters) { (k, label) ->
                FilterChip(
                    selected = statusFilter == k,
                    onClick = { statusFilter = k },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.internalName }) { p ->
                val isInstalled = p.installedVersion != null
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPluginInfo(p) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusChip(p.category, MaterialTheme.colorScheme.primary)
                                val stText = when (p.pluginStatus) {
                                    "installed" -> "مثبتة (v${p.installedVersion?.ar()})"
                                    "update" -> "تحديث v${p.installedVersion?.ar()} ← v${p.version.ar()}"
                                    "failed" -> "فشل"
                                    "disabled" -> "معطّلة"
                                    else -> "متاحة (v${p.version.ar()})"
                                }
                                val stCol = when (p.pluginStatus) {
                                    "installed" -> Color(0xFF10B981)
                                    "update" -> Color(0xFFF59E0B)
                                    "failed" -> Color(0xFFEF4444)
                                    else -> Color(0xFF64748B)
                                }
                                StatusChip(stText, stCol)
                            }
                            if (p.pluginStatus == "failed" && !p.report?.error.isNullOrBlank()) {
                                Text(
                                    p.report!!.error!!,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.pluginStatus == "update") {
                                TextButton(onClick = {
                                    scope.launch {
                                        Api.call(
                                            "POST",
                                            "/admin/plugins/update",
                                            JSONObject().put("repo", repo.url).put("internalName", p.internalName)
                                        )
                                        onReloadCatalog()
                                    }
                                }) { Text("تحديث") }
                            }
                            Switch(
                                checked = isInstalled,
                                onCheckedChange = { wantInstall ->
                                    scope.launch {
                                        val body = JSONObject().put("repo", repo.url)
                                        if (wantInstall) {
                                            body.put("install", JSONArray().put(p.internalName))
                                        } else {
                                            body.put("uninstall", JSONArray().put(p.internalName))
                                        }
                                        Api.call("POST", "/admin/plugins", body, timeoutMs = 45000)
                                        onReloadCatalog()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
