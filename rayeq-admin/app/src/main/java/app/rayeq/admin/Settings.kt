package app.rayeq.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AdminSettingsScreen() {
    val scope = rememberCoroutineScope()
    val themeMode by Api.themeMode.collectAsState()

    var latestVersion by remember { mutableStateOf("") }
    var latestCode by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var minCode by remember { mutableStateOf("") }
    var announcement by remember { mutableStateOf("") }
    var origAnnouncement by remember { mutableStateOf("") }
    var announcementId by remember { mutableStateOf(0) }
    var maintenance by remember { mutableStateOf(false) }
    var maintenanceMessage by remember { mutableStateOf("") }
    var supportContact by remember { mutableStateOf("") }

    var osKeyHint by remember { mutableStateOf<String?>(null) }
    var osKeySet by remember { mutableStateOf(false) }
    var newOsKey by remember { mutableStateOf("") }
    var integrations by remember { mutableStateOf<IntegrationsCfg?>(null) }

    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var kickConfirmStep by remember { mutableStateOf(0) } // 0, 1, 2 (تأكيد مزدوج)

    suspend fun loadAll() {
        val appRes = Api.call("GET", "/admin/app")
        if (appRes.ok && appRes.data != null) {
            val d = appRes.data
            latestVersion = if (d.isNull("latestVersion")) "" else d.optString("latestVersion", "")
            latestCode = if (d.isNull("latestCode")) "" else d.optInt("latestCode").toString()
            downloadUrl = if (d.isNull("downloadUrl")) "" else d.optString("downloadUrl", "")
            notes = d.optString("notes", "")
            minCode = if (d.isNull("minCode")) "" else d.optInt("minCode").toString()
            announcement = d.optString("announcement", "")
            origAnnouncement = announcement
            announcementId = d.optInt("announcementId", 0)
            maintenance = d.optBoolean("maintenance", false)
            maintenanceMessage = d.optString("maintenanceMessage", "")
            supportContact = d.optString("supportContact", "")
        }
        val subRes = Api.call("GET", "/admin/settings/subs")
        if (subRes.ok && subRes.data != null) {
            osKeySet = subRes.data.optBoolean("opensubtitlesKeySet", false)
            osKeyHint = if (subRes.data.isNull("opensubtitlesKeyHint")) null else subRes.data.optString("opensubtitlesKeyHint")
        }
        val intRes = Api.call("GET", "/admin/integrations")
        if (intRes.ok && intRes.data != null) {
            integrations = IntegrationsCfg(
                tmdb = intRes.data.optBoolean("tmdb", false),
                tvdb = intRes.data.optBoolean("tvdb", false),
                opensubtitles = intRes.data.optBoolean("opensubtitles", false)
            )
        }
    }

    LaunchedEffect(Unit) {
        loadAll()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "الإعدادات العامة والخدمات",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (statusBanner != null) {
                Text(statusBanner!!, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }

        // 1. الصيانة والإعلان والتحديث والدعم
        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("وضع الصيانة", fontWeight = FontWeight.Bold)
                        Switch(checked = maintenance, onCheckedChange = { maintenance = it })
                    }
                    OutlinedTextField(
                        value = maintenanceMessage,
                        onValueChange = { maintenanceMessage = it },
                        label = { Text("رسالة الصيانة") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Divider()
                    Text("الإعلان للمستخدمين", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = announcement,
                        onValueChange = { announcement = it },
                        label = { Text("نص الإعلان (رقم الإعلان الحالي: ${announcementId.ar()})") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Divider()
                    Text("تحديث التطبيق", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = latestVersion,
                            onValueChange = { latestVersion = it },
                            label = { Text("الإصدار (1.0.0)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = latestCode,
                            onValueChange = { latestCode = it.filter { c -> c.isDigit() } },
                            label = { Text("versionCode") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minCode,
                            onValueChange = { minCode = it.filter { c -> c.isDigit() } },
                            label = { Text("minCode (إجباري)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        label = { Text("رابط تنزيل التحديث (https://...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("ملاحظات الإصدار") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = supportContact,
                        onValueChange = { supportContact = it },
                        label = { Text("جهة الدعم والتواصل") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val nextAnnId = if (announcement.trim() != origAnnouncement.trim() && announcement.isNotBlank()) {
                                    announcementId + 1
                                } else {
                                    announcementId
                                }
                                val body = JSONObject()
                                    .put("latestVersion", latestVersion.ifBlank { JSONObject.NULL })
                                    .put("latestCode", latestCode.toIntOrNull() ?: JSONObject.NULL)
                                    .put("downloadUrl", downloadUrl.ifBlank { JSONObject.NULL })
                                    .put("notes", notes)
                                    .put("minCode", minCode.toIntOrNull() ?: JSONObject.NULL)
                                    .put("announcement", announcement)
                                    .put("announcementId", nextAnnId)
                                    .put("maintenance", maintenance) // boolean حقيقية دائماً
                                    .put("maintenanceMessage", maintenanceMessage)
                                    .put("supportContact", supportContact)
                                val res = Api.call("PUT", "/admin/app", body)
                                if (res.ok) {
                                    announcementId = nextAnnId
                                    origAnnouncement = announcement
                                    statusBanner = "تم حفظ إعدادات التطبيق بنجاح"
                                } else {
                                    statusBanner = res.error
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("حفظ إعدادات التطبيق")
                    }
                }
            }
        }

        // 2. الترجمة والخدمات
        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("الترجمة وحالة الخدمات", fontWeight = FontWeight.Bold)
                    val intg = integrations
                    if (intg != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip("TMDB: ${if (intg.tmdb) "متصل" else "غير مضبوط"}", if (intg.tmdb) Color(0xFF10B981) else Color(0xFFEF4444))
                            StatusChip("TVDB: ${if (intg.tvdb) "متصل" else "غير مضبوط"}", if (intg.tvdb) Color(0xFF10B981) else Color(0xFF64748B))
                            StatusChip("OpenSubtitles: ${if (intg.opensubtitles) "متصل" else "غير مضبوط"}", if (intg.opensubtitles) Color(0xFF10B981) else Color(0xFF64748B))
                        }
                    }
                    Text(
                        text = if (osKeySet) "مفتاح OpenSubtitles الحالي: ${osKeyHint ?: "مضبوط"}" else "لم يُضبط مفتاح OpenSubtitles بعد",
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = newOsKey,
                        onValueChange = { newOsKey = it },
                        label = { Text("مفتاح تطبيق OpenSubtitles الجديد") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val res = Api.call(
                                        "PUT",
                                        "/admin/settings/subs",
                                        JSONObject().put("opensubtitlesKey", newOsKey.trim())
                                    )
                                    if (res.ok) {
                                        newOsKey = ""
                                        loadAll()
                                    }
                                }
                            },
                            enabled = newOsKey.isNotBlank()
                        ) { Text("حفظ المفتاح") }

                        if (osKeySet) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    Api.call("PUT", "/admin/settings/subs", JSONObject().put("opensubtitlesKey", ""))
                                    loadAll()
                                }
                            }) { Text("مسح المفتاح") }
                        }
                    }
                }
            }
        }

        // 3. المظهر وتغيير كلمة سر المدير وطرد كل الأجهزة
        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("المظهر", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "النظام", "dark" to "داكن", "light" to "فاتح").forEach { (m, lbl) ->
                            FilterChip(
                                selected = themeMode == m,
                                onClick = { Api.setThemeMode(m) },
                                label = { Text(lbl) }
                            )
                        }
                    }
                    Divider()
                    Text("تغيير كلمة سر المدير", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = oldPass,
                        onValueChange = { oldPass = it },
                        label = { Text("كلمة السر الحالية") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = { newPass = it },
                        label = { Text("كلمة السر الجديدة (٨ أحرف فأكثر)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val res = Api.call(
                                    "POST",
                                    "/admin/password",
                                    JSONObject().put("old", oldPass).put("new", newPass)
                                )
                                if (res.ok) {
                                    oldPass = ""
                                    newPass = ""
                                    statusBanner = "تم تغيير كلمة السر بنجاح"
                                } else {
                                    statusBanner = res.error
                                }
                            }
                        },
                        enabled = oldPass.isNotBlank() && newPass.length >= 8
                    ) { Text("تحديث كلمة السر") }

                    Divider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { kickConfirmStep = 1 },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("طرد كل الأجهزة") }

                        OutlinedButton(onClick = { Api.logout() }) {
                            Text("تسجيل الخروج")
                        }
                    }
                }
            }
        }
    }

    // تأكيد مزدوج لطرد كل الأجهزة
    if (kickConfirmStep == 1) {
        AlertDialog(
            onDismissRequest = { kickConfirmStep = 0 },
            title = { Text("طرد كل الأجهزة (١/٢)") },
            text = { Text("سيؤدي هذا الإجراء إلى تسجيل خروج جميع المستخدمين وإلغاء موافقة أجهزتهم. هل تريد المتابعة؟") },
            confirmButton = {
                TextButton(onClick = { kickConfirmStep = 2 }) { Text("نعم، متابعة") }
            },
            dismissButton = {
                TextButton(onClick = { kickConfirmStep = 0 }) { Text("إلغاء") }
            }
        )
    } else if (kickConfirmStep == 2) {
        AlertDialog(
            onDismissRequest = { kickConfirmStep = 0 },
            title = { Text("تأكيد نهائي لطرد الكل (٢/٢)") },
            text = { Text("تأكيد أخير: هل أنت متأكد تماماً من طرد جميع الأجهزة الآن؟") },
            confirmButton = {
                TextButton(onClick = {
                    kickConfirmStep = 0
                    scope.launch {
                        Api.call("POST", "/admin/devices/kick-all")
                        statusBanner = "تم طرد جميع الأجهزة"
                    }
                }) { Text("طرد الكل الآن", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { kickConfirmStep = 0 }) { Text("تراجع") }
            }
        )
    }
}
