package app.rayeq.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun UserSourcesSection(userId: Long, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UserSources?>(null) }
    var loading by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        val res = Api.call("GET", "/admin/users/$userId/sources")
        if (res.ok && res.data != null) {
            state = Api.parseUserSources(res.data)
        }
        loading = false
    }

    LaunchedEffect(userId) {
        load()
    }

    val s = state
    if (loading && s == null) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        return
    }
    if (s == null) return

    val contentServers = s.servers.filter { !it.tool }
    val reachedCount = contentServers.count { it.effective }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("الإضافات لهذا المستخدم", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "يصله ${reachedCount.ar()} من ${contentServers.size.ar()}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = { confirmReset = true }) {
                    Text("إرجاع للافتراضي")
                }
            }

            Text(
                text = "التغيير يصل خلال ثوانٍ إذا كان التطبيق مفتوحاً، وإلا عند فتحه.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )

            // مفتاح إخفاء الإضافات غير العربية
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("إخفاء الإضافات غير العربية", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "إضافات البث المباشر الأجنبية تبقى شغالة",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                Switch(
                    checked = s.hideNonArabic,
                    onCheckedChange = { checked ->
                        scope.launch {
                            val body = JSONObject().put(
                                "rules",
                                JSONObject().put("hideNonArabic", checked)
                            )
                            val res = Api.call("PUT", "/admin/users/$userId/sources", body)
                            if (res.ok && res.data != null) {
                                state = Api.parseUserSources(res.data)
                                onChanged()
                            }
                        }
                    }
                )
            }

            // مفتاح إخفاء البث المباشر
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("إخفاء البث المباشر", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "العربية والأجنبية",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                Switch(
                    checked = s.hideLive,
                    onCheckedChange = { checked ->
                        scope.launch {
                            val body = JSONObject().put(
                                "rules",
                                JSONObject().put("hideLive", checked)
                            )
                            val res = Api.call("PUT", "/admin/users/$userId/sources", body)
                            if (res.ok && res.data != null) {
                                state = Api.parseUserSources(res.data)
                                onChanged()
                            }
                        }
                    }
                )
            }

            Divider()

            // قائمة السيرفرات
            for (srv in s.servers) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(srv.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusChip(srv.category, MaterialTheme.colorScheme.primary)
                            if (srv.arabic) StatusChip("عربي", Color(0xFF10B981))
                            if (srv.live) StatusChip("بث مباشر", Color(0xFFF59E0B))
                            if (!srv.globalEnabled) StatusChip("معطل عامًا", Color(0xFFEF4444))
                            if (srv.userEnabled != null) StatusChip("مخصص", Color(0xFF818CF8))
                            else if (srv.hiddenByRule) StatusChip("مخفي بالقاعدة", Color(0xFF94A3B8))
                        }
                    }
                    Switch(
                        checked = srv.effective,
                        enabled = srv.globalEnabled,
                        onCheckedChange = { wantEnabled ->
                            scope.launch {
                                val ruleDefault = !srv.hiddenByRule
                                // إذا أُعيد مفتاح إلى ما تعطيه القواعد يُحذف الإعداد الخاص (null)
                                val sendVal: Any = if (wantEnabled == ruleDefault) JSONObject.NULL else wantEnabled
                                val arr = JSONArray().put(
                                    JSONObject()
                                        .put("internalName", srv.internalName)
                                        .put("enabled", sendVal)
                                )
                                val res = Api.call(
                                    "PUT",
                                    "/admin/users/$userId/sources",
                                    JSONObject().put("servers", arr)
                                )
                                if (res.ok && res.data != null) {
                                    state = Api.parseUserSources(res.data)
                                    onChanged()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("إرجاع للافتراضي") },
            text = { Text("هل تريد مسح جميع القواعد والتخصيصات الخاصة بهذا المستخدم؟") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        scope.launch {
                            val res = Api.call("DELETE", "/admin/users/$userId/sources")
                            if (res.ok && res.data != null) {
                                state = Api.parseUserSources(res.data)
                                onChanged()
                            }
                        }
                    }
                ) {
                    Text("تأكيد")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}
