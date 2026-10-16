package app.rayeq.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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

@Composable
fun ServersScreen() {
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<List<ServerItem>>(emptyList()) }
    var working by remember { mutableStateOf<List<ServerItem>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }

    fun recomputeLabels(list: List<ServerItem>): List<ServerItem> {
        var n = 0
        return list.mapIndexed { idx, s ->
            val lbl = if (s.tool) "أداة (تُثبَّت ولا تُرقَّم)" else {
                n++
                "سيرفر ${n.ar()}"
            }
            s.copy(position = idx + 1, label = lbl)
        }
    }

    suspend fun loadServers() {
        val res = Api.call("GET", "/admin/servers")
        if (res.ok && res.data != null) {
            val arr = res.data.optJSONArray("items") ?: JSONArray()
            val list = (0 until arr.length()).map { Api.parseServerItem(arr.getJSONObject(it)) }
            val labeled = recomputeLabels(list)
            original = labeled
            working = labeled
        }
    }

    LaunchedEffect(Unit) {
        loadServers()
    }

    val liveCount = working.count { it.live && !it.tool }
    val hasChanges = working != original

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "السيرفرات والأولوية",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "بث مباشر: ${liveCount.ar()} · النوع يُحدَّث بعد الحفظ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            OutlinedButton(
                onClick = {
                    // رفع العربية للأعلى مع الحفاظ على ترتيبها النسبي
                    val ar = working.filter { it.arabic }
                    val nonAr = working.filter { !it.arabic }
                    working = recomputeLabels(ar + nonAr)
                }
            ) {
                Text("رفع العربية للأعلى", fontSize = 12.sp)
            }
        }

        if (hasChanges) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        scope.launch {
                            // إرسال القائمة كاملة دائماً إلى PUT /admin/servers
                            val arr = JSONArray()
                            for (s in working) {
                                val arOverride: Any = when (s.langSource) {
                                    "admin" -> s.arabic
                                    else -> JSONObject.NULL
                                }
                                arr.put(
                                    JSONObject()
                                        .put("repo", s.repo)
                                        .put("internalName", s.internalName)
                                        .put("enabled", s.enabled)
                                        .put("arabic", arOverride)
                                        .put("kind", s.kind)
                                )
                            }
                            val res = Api.call(
                                "PUT",
                                "/admin/servers",
                                JSONObject().put("servers", arr),
                                timeoutMs = 45000
                            )
                            saving = false
                            if (res.ok && res.data != null) {
                                val itemsArr = res.data.optJSONArray("items") ?: JSONArray()
                                val updated = (0 until itemsArr.length()).map {
                                    Api.parseServerItem(itemsArr.getJSONObject(it))
                                }
                                val labeled = recomputeLabels(updated)
                                original = labeled
                                working = labeled
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (saving) "جارٍ الحفظ…" else "حفظ الترتيب والتعديلات")
                }
                OutlinedButton(onClick = { working = original }) {
                    Text("تراجع")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(working, key = { _, it -> "${it.repo}|${it.internalName}" }) { idx, srv ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "${srv.label} · ${srv.name}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusChip(srv.category, MaterialTheme.colorScheme.primary)
                                    val arLabel = when {
                                        srv.arabic && srv.langSource == "admin" -> "عربي (يدوي)"
                                        srv.arabic -> "عربي (تلقائي)"
                                        srv.langSource == "admin" -> "أجنبي (يدوي)"
                                        else -> "أجنبي"
                                    }
                                    StatusChip(
                                        text = arLabel,
                                        color = if (srv.arabic) Color(0xFF10B981) else Color(0xFF64748B)
                                    )
                                }
                            }
                            Switch(
                                checked = srv.enabled,
                                onCheckedChange = { en ->
                                    val m = working.toMutableList()
                                    m[idx] = srv.copy(enabled = en)
                                    working = recomputeLabels(m)
                                }
                            )
                        }

                        // أدوات اللغة والنوع والترتيب
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // تبديل اللغة: تلقائي / عربي / أجنبي
                                AssistChip(
                                    onClick = {
                                        val m = working.toMutableList()
                                        val next = when {
                                            srv.langSource != "admin" -> srv.copy(arabic = true, lang = "ar", langSource = "admin")
                                            srv.arabic -> srv.copy(arabic = false, lang = "other", langSource = "admin")
                                            else -> srv.copy(langSource = "plugin")
                                        }
                                        m[idx] = next
                                        working = recomputeLabels(m)
                                    },
                                    label = { Text("اللغة", fontSize = 11.sp) }
                                )

                                // محدد النوع: تلقائي | بث مباشر | محتوى
                                val kinds = listOf("auto" to "تلقائي", "live" to "بث مباشر", "content" to "محتوى")
                                for ((kVal, kTitle) in kinds) {
                                    FilterChip(
                                        selected = srv.kind == kVal,
                                        onClick = {
                                            val m = working.toMutableList()
                                            val isLivePreview = when (kVal) {
                                                "live" -> true
                                                "content" -> false
                                                else -> srv.live
                                            }
                                            m[idx] = srv.copy(kind = kVal, live = isLivePreview)
                                            working = recomputeLabels(m)
                                        },
                                        label = { Text(kTitle, fontSize = 10.sp) }
                                    )
                                }
                            }

                            Row {
                                IconButton(
                                    onClick = {
                                        if (idx > 0) {
                                            val m = working.toMutableList()
                                            val item = m.removeAt(idx)
                                            m.add(0, item)
                                            working = recomputeLabels(m)
                                        }
                                    },
                                    enabled = idx > 0
                                ) {
                                    Text("للأعلى", fontSize = 10.sp)
                                }
                                IconButton(
                                    onClick = {
                                        if (idx > 0) {
                                            val m = working.toMutableList()
                                            val tmp = m[idx - 1]
                                            m[idx - 1] = m[idx]
                                            m[idx] = tmp
                                            working = recomputeLabels(m)
                                        }
                                    },
                                    enabled = idx > 0
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "فوق")
                                }
                                IconButton(
                                    onClick = {
                                        if (idx < working.lastIndex) {
                                            val m = working.toMutableList()
                                            val tmp = m[idx + 1]
                                            m[idx + 1] = m[idx]
                                            m[idx] = tmp
                                            working = recomputeLabels(m)
                                        }
                                    },
                                    enabled = idx < working.lastIndex
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "تحت")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
