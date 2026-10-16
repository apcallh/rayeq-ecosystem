package app.rayeq.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(initialFilter: String = "all") {
    val scope = rememberCoroutineScope()
    var allUsers by remember { mutableStateOf<List<User>>(emptyList()) }
    var serverNow by remember { mutableStateOf(System.currentTimeMillis()) }
    var filterKey by remember(initialFilter) { mutableStateOf(initialFilter) }
    var sortKey by remember { mutableStateOf("online") } // online | last_seen | watch | watch7d
    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var activeUser by remember { mutableStateOf<UserDetail?>(null) }
    var showDurationDialogFor by remember { mutableStateOf<Pair<String, List<Long>>?>(null) }
    var confirmDeleteIds by remember { mutableStateOf<List<Long>?>(null) }

    suspend fun loadUsers() {
        val res = Api.call("GET", "/admin/users?sort=$sortKey")
        if (res.ok && res.data != null) {
            serverNow = res.data.optLong("now", System.currentTimeMillis())
            val arr = res.data.optJSONArray("users") ?: JSONArray()
            val list = mutableListOf<User>()
            for (i in 0 until arr.length()) {
                list.add(Api.parseUser(arr.getJSONObject(i)))
            }
            if (sortKey == "watch7d") {
                list.sortByDescending { it.watchSeconds7d }
            }
            allUsers = list
        }
    }

    suspend fun openUserDetail(userId: Long) {
        val res = Api.call("GET", "/admin/users/$userId")
        if (res.ok && res.data != null) {
            val uObj = res.data.optJSONObject("user") ?: return
            val u = Api.parseUser(uObj)
            val daysArr = uObj.optJSONArray("days") ?: JSONArray()
            val days = (0 until daysArr.length()).map { idx ->
                val d = daysArr.getJSONObject(idx)
                DayUsage(
                    day = d.optString("day"),
                    seconds = d.optLong("seconds", 0L),
                    screens = d.optInt("screens", 0)
                )
            }
            activeUser = UserDetail(u, days)
        }
    }

    LaunchedEffect(sortKey) {
        loadUsers()
    }

    val soonThreshold = serverNow + 3 * 86400_000L
    val filtered = remember(allUsers, filterKey, query, serverNow) {
        allUsers.filter { u ->
            val matchFilter = when (filterKey) {
                "pending" -> u.pending
                "online" -> u.online
                "active" -> u.effectiveStatus == "approved"
                "expired" -> u.effectiveStatus == "expired"
                "blocked" -> u.effectiveStatus == "blocked"
                "locked" -> u.effectiveStatus == "locked"
                "expiring" -> u.effectiveStatus == "approved" && u.accessUntil != null && u.accessUntil in serverNow..soonThreshold
                else -> true
            }
            val q = query.trim().lowercase()
            val matchSearch = q.isEmpty() ||
                u.name.lowercase().contains(q) ||
                u.email.lowercase().contains(q) ||
                u.deviceModel.lowercase().contains(q)
            matchFilter && matchSearch
        }
    }

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
            Text(
                "المستخدمون (${filtered.size.ar()})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { scope.launch { loadUsers() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "تحديث")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("بحث بالاسم أو البريد أو الجهاز…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // شريط المرشحات مع العدد
        val filters = listOf(
            "pending" to "بانتظار (${allUsers.count { it.pending }.ar()})",
            "online" to "المتصلون (${allUsers.count { it.online }.ar()})",
            "all" to "الكل (${allUsers.size.ar()})",
            "active" to "نشط (${allUsers.count { it.effectiveStatus == "approved" }.ar()})",
            "expired" to "منتهٍ (${allUsers.count { it.effectiveStatus == "expired" }.ar()})",
            "blocked" to "محظور (${allUsers.count { it.effectiveStatus == "blocked" }.ar()})",
            "locked" to "مقفل (${allUsers.count { it.effectiveStatus == "locked" }.ar()})",
            "expiring" to "ينتهي قريباً"
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filters) { (k, label) ->
                FilterChip(
                    selected = filterKey == k,
                    onClick = { filterKey = k },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        // شريط الترتيب
        val sorts = listOf(
            "online" to "المتصلون أولاً",
            "last_seen" to "آخر ظهور",
            "watch" to "ساعات المشاهدة",
            "watch7d" to "مشاهدة ٧ أيام"
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorts) { (k, label) ->
                AssistChip(
                    onClick = { sortKey = k },
                    label = {
                        Text(
                            text = if (sortKey == k) "✓ $label" else label,
                            fontSize = 11.sp
                        )
                    }
                )
            }
        }

        // شريط الإجراءات الجماعية عند التحديد المتعدد
        if (selectedIds.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("محدد: ${selectedIds.size.ar()}", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { selectedIds = emptySet() }) {
                            Text("إلغاء التحديد")
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            Button(onClick = {
                                showDurationDialogFor = "approve" to selectedIds.toList()
                            }) { Text("موافقة") }
                        }
                        item {
                            OutlinedButton(onClick = {
                                showDurationDialogFor = "lock" to selectedIds.toList()
                            }) { Text("قفل") }
                        }
                        item {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    Api.call(
                                        "POST",
                                        "/admin/users/bulk",
                                        JSONObject()
                                            .put("ids", JSONArray(selectedIds.toList()))
                                            .put("action", "unlock")
                                    )
                                    selectedIds = emptySet()
                                    loadUsers()
                                }
                            }) { Text("فتح") }
                        }
                        item {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    Api.call(
                                        "POST",
                                        "/admin/users/bulk",
                                        JSONObject()
                                            .put("ids", JSONArray(selectedIds.toList()))
                                            .put("action", "block")
                                    )
                                    selectedIds = emptySet()
                                    loadUsers()
                                }
                            }) { Text("حظر") }
                        }
                        item {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    Api.call(
                                        "POST",
                                        "/admin/users/bulk",
                                        JSONObject()
                                            .put("ids", JSONArray(selectedIds.toList()))
                                            .put("action", "kick")
                                    )
                                    selectedIds = emptySet()
                                    loadUsers()
                                }
                            }) { Text("طرد") }
                        }
                        item {
                            OutlinedButton(onClick = {
                                confirmDeleteIds = selectedIds.toList()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("حذف")
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filtered, key = { it.id }) { u ->
                val isSelected = selectedIds.contains(u.id)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { openUserDetail(u.id) } },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + u.id else selectedIds - u.id
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (u.online) Color(0xFF10B981) else Color(0xFF64748B))
                                )
                                Text(
                                    text = u.name.ifBlank { "بدون اسم" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                StatusChip(
                                    text = statusLabelAr(u.effectiveStatus),
                                    color = statusColor(u.effectiveStatus)
                                )
                                if (u.online && u.state != null) {
                                    val stAr = when (u.state) {
                                        "watching" -> "يشاهد الآن"
                                        "background" -> "في الخلفية"
                                        else -> "يتصفح"
                                    }
                                    StatusChip(stAr, MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(
                                text = "${u.email} · ${u.deviceType} (${u.deviceModel.ifBlank { "جهاز" }})",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "آخر ظهور: ${formatRelativeAr(u.lastSeen, serverNow)} · الوصول حتى: ${formatDateAr(u.accessUntil)}",
                                fontSize = 11.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "المشاهدة: ${formatHoursAr(u.watchSecondsTotal)} (اليوم ${formatHoursAr(u.watchSecondsToday)}) · تصفح: ${u.browseCount.ar()}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                                if (u.customSources > 0) {
                                    StatusChip(
                                        text = "إضافات مخصصة (${u.customSources.ar()})",
                                        color = Color(0xFF818CF8)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ورقة تفاصيل المستخدم
    val detail = activeUser
    if (detail != null) {
        ModalBottomSheet(onDismissRequest = { activeUser = null }) {
            val u = detail.user
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(u.name.ifBlank { u.email }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(u.email, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        StatusChip(statusLabelAr(u.effectiveStatus), statusColor(u.effectiveStatus))
                    }
                }

                item {
                    Text(
                        "الإحصاءات: الكلي ${formatHoursAr(u.watchSecondsTotal)} · اليوم ${formatHoursAr(u.watchSecondsToday)} · ٧ أيام ${formatHoursAr(u.watchSeconds7d)} · التصفح ${u.browseCount.ar()} · الإصدار ${u.appVersion ?: "-"}",
                        fontSize = 12.sp
                    )
                    WeekBars(days = detail.days)
                }

                // الإجراءات الفردية
                item {
                    Text("الإجراءات", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { showDurationDialogFor = "approve" to listOf(u.id) }) {
                            Text("موافقة")
                        }
                        OutlinedButton(onClick = { showDurationDialogFor = "access" to listOf(u.id) }) {
                            Text("تعديل المدة")
                        }
                        if (u.locked) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    Api.call("POST", "/admin/users/${u.id}/unlock", JSONObject())
                                    openUserDetail(u.id)
                                    loadUsers()
                                }
                            }) { Text("فتح القفل") }
                        } else {
                            OutlinedButton(onClick = { showDurationDialogFor = "lock" to listOf(u.id) }) {
                                Text("قفل")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (u.status == "blocked") {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    Api.call("POST", "/admin/users/${u.id}/unblock", JSONObject())
                                    openUserDetail(u.id)
                                    loadUsers()
                                }
                            }) { Text("فك الحظر") }
                        } else {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    Api.call("POST", "/admin/users/${u.id}/block", JSONObject())
                                    openUserDetail(u.id)
                                    loadUsers()
                                }
                            }) { Text("حظر") }
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                Api.call("POST", "/admin/users/${u.id}/kick", JSONObject())
                                openUserDetail(u.id)
                                loadUsers()
                            }
                        }) { Text("طرد الأجهزة") }
                        OutlinedButton(
                            onClick = { confirmDeleteIds = listOf(u.id) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("حذف") }
                    }
                }

                // الأجهزة
                item {
                    Text("الأجهزة (${u.devices.size.ar()})", fontWeight = FontWeight.Bold)
                }
                items(u.devices) { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${d.model.ifBlank { "جهاز" }} (${d.type})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    "آخر ظهور: ${formatRelativeAr(d.lastSeen, serverNow)} · ${if (d.approved) "معتمد" else "بانتظار الموافقة"}",
                                    fontSize = 11.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!d.approved && d.signedIn) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            Api.call("POST", "/admin/devices/${d.id}/approve")
                                            openUserDetail(u.id)
                                            loadUsers()
                                        }
                                    }) { Text("موافقة") }
                                }
                                if (d.signedIn) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            Api.call("POST", "/admin/devices/${d.id}/kick")
                                            openUserDetail(u.id)
                                            loadUsers()
                                        }
                                    }) { Text("طرد") }
                                }
                            }
                        }
                    }
                }

                // قسم الإضافات لهذا المستخدم
                item {
                    UserSourcesSection(
                        userId = u.id,
                        onChanged = { scope.launch { loadUsers() } }
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    // حوار المدة (يوم · 7 أيام · 30 يوماً · 3 أشهر · 6 أشهر · سنة · بلا حد · مخصص)
    val durTarget = showDurationDialogFor
    if (durTarget != null) {
        val (act, ids) = durTarget
        DurationPickerDialog(
            title = if (act == "lock") "مدة القفل" else "تحديد مدة الوصول",
            isLock = act == "lock",
            onDismiss = { showDurationDialogFor = null },
            onSelect = { durationJson ->
                showDurationDialogFor = null
                scope.launch {
                    if (ids.size == 1) {
                        Api.call("POST", "/admin/users/${ids.first()}/$act", durationJson)
                        if (activeUser?.user?.id == ids.first()) openUserDetail(ids.first())
                    } else {
                        Api.call(
                            "POST",
                            "/admin/users/bulk",
                            JSONObject()
                                .put("ids", JSONArray(ids))
                                .put("action", act)
                                .put("duration", durationJson)
                        )
                        selectedIds = emptySet()
                    }
                    loadUsers()
                }
            }
        )
    }

    val delIds = confirmDeleteIds
    if (delIds != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteIds = null },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل أنت متأكد من حذف الحساب وجميع بياناته نهائياً؟") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteIds = null
                    scope.launch {
                        if (delIds.size == 1) {
                            Api.call("DELETE", "/admin/users/${delIds.first()}")
                            if (activeUser?.user?.id == delIds.first()) activeUser = null
                        } else {
                            Api.call(
                                "POST",
                                "/admin/users/bulk",
                                JSONObject().put("ids", JSONArray(delIds)).put("action", "delete")
                            )
                            selectedIds = emptySet()
                        }
                        loadUsers()
                    }
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteIds = null }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
fun DurationPickerDialog(
    title: String,
    isLock: Boolean,
    onDismiss: () -> Unit,
    onSelect: (JSONObject) -> Unit
) {
    var customNumber by remember { mutableStateOf("") }
    var customUnit by remember { mutableStateOf("days") } // hours | days | months

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = if (isLock) {
                    listOf(
                        "حتى أفتحه يدوياً" to JSONObject().put("unlimited", true),
                        "ساعة واحدة" to JSONObject().put("hours", 1),
                        "يوم واحد" to JSONObject().put("days", 1),
                        "٧ أيام" to JSONObject().put("days", 7),
                        "٣٠ يوماً" to JSONObject().put("days", 30)
                    )
                } else {
                    listOf(
                        "يوم واحد" to JSONObject().put("days", 1),
                        "٧ أيام" to JSONObject().put("days", 7),
                        "٣٠ يوماً" to JSONObject().put("days", 30),
                        "٣ أشهر" to JSONObject().put("months", 3),
                        "٦ أشهر" to JSONObject().put("months", 6),
                        "سنة" to JSONObject().put("months", 12),
                        "بلا حد" to JSONObject().put("unlimited", true)
                    )
                }
                for ((label, obj) in presets) {
                    OutlinedButton(
                        onClick = { onSelect(obj) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
                Divider()
                Text("مدة مخصصة:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customNumber,
                        onValueChange = { customNumber = it.filter { c -> c.isDigit() } },
                        label = { Text("العدد") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = customUnit == "hours",
                        onClick = { customUnit = "hours" },
                        label = { Text("ساعات") }
                    )
                    FilterChip(
                        selected = customUnit == "days",
                        onClick = { customUnit = "days" },
                        label = { Text("أيام") }
                    )
                    FilterChip(
                        selected = customUnit == "months",
                        onClick = { customUnit = "months" },
                        label = { Text("أشهر") }
                    )
                }
            }
        },
        confirmButton = {
            val n = customNumber.toIntOrNull()
            if (n != null && n > 0) {
                TextButton(onClick = { onSelect(JSONObject().put(customUnit, n)) }) {
                    Text("تطبيق المخصص")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
