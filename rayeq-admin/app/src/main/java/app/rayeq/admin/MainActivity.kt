package app.rayeq.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        setContent {
            val themeMode by Api.themeMode.collectAsState()
            val signedIn by Api.signedIn.collectAsState()
            RayeqAdminTheme(mode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!signedIn) {
                        LoginScreen()
                    } else {
                        AdminShell()
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var retryCountdown by remember { mutableStateOf(0) }

    LaunchedEffect(retryCountdown) {
        if (retryCountdown > 0) {
            delay(1000L)
            retryCountdown -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "إدارة رايق",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("اسم المستخدم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("كلمة المرور") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (retryCountdown > 0) {
                    Text(
                        text = "محاولات كثيرة، حاول بعد ${retryCountdown.ar()} ثانية",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                } else if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
                Button(
                    onClick = {
                        if (busy || retryCountdown > 0) return@Button
                        busy = true
                        errorMsg = null
                        scope.launch {
                            val res = Api.login(username.trim(), password)
                            busy = false
                            if (!res.ok) {
                                if (res.code == 429 && res.retryAfterSec != null) {
                                    retryCountdown = res.retryAfterSec
                                }
                                errorMsg = res.error
                            }
                        }
                    },
                    enabled = !busy && retryCountdown == 0 && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("تسجيل الدخول", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminShell() {
    var currentTab by remember { mutableStateOf(0) }
    var userFilterJump by remember { mutableStateOf("all") }

    BackHandler(enabled = currentTab != 0) {
        currentTab = 0
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val tabs = listOf(
                    "الرئيسية" to Icons.Default.Home,
                    "المستخدمون" to Icons.Default.Person,
                    "المستودعات" to Icons.Default.List,
                    "السيرفرات" to Icons.Default.PlayArrow,
                    "الإعدادات" to Icons.Default.Settings
                )
                tabs.forEachIndexed { idx, (title, icon) ->
                    NavigationBarItem(
                        selected = currentTab == idx,
                        onClick = { currentTab = idx },
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> DashboardScreen(
                    onOpenUsersWithFilter = { filterKey ->
                        userFilterJump = filterKey
                        currentTab = 1
                    }
                )
                1 -> UsersScreen(initialFilter = userFilterJump)
                2 -> ReposScreen()
                3 -> ServersScreen()
                4 -> AdminSettingsScreen()
            }
        }
    }
}

@Composable
fun DashboardScreen(onOpenUsersWithFilter: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<Stats?>(null) }
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun refresh() {
        loading = true
        val stRes = Api.call("GET", "/admin/stats")
        if (stRes.ok && stRes.data != null) {
            val d = stRes.data
            val u = d.optJSONObject("users")
            val dv = d.optJSONObject("devices")
            val sr = d.optJSONObject("sources")
            stats = Stats(
                now = d.optLong("now", System.currentTimeMillis()),
                usersTotal = u?.optInt("total", 0) ?: 0,
                usersPending = u?.optInt("pending", 0) ?: 0,
                usersActive = u?.optInt("active", 0) ?: 0,
                usersBlocked = u?.optInt("blocked", 0) ?: 0,
                usersLocked = u?.optInt("locked", 0) ?: 0,
                usersExpired = u?.optInt("expired", 0) ?: 0,
                usersExpiringSoon = u?.optInt("expiringSoon", 0) ?: 0,
                usersNewWeek = u?.optInt("newWeek", 0) ?: 0,
                devicesTotal = dv?.optInt("total", 0) ?: 0,
                devicesOnline = dv?.optInt("online", 0) ?: 0,
                devicesPending = dv?.optInt("pending", 0) ?: 0,
                phones = dv?.optInt("phones", 0) ?: 0,
                tablets = dv?.optInt("tablets", 0) ?: 0,
                tvs = dv?.optInt("tvs", 0) ?: 0,
                activeDay = dv?.optInt("activeDay", 0) ?: 0,
                reposCount = sr?.optInt("repos", 0) ?: 0,
                serversCount = sr?.optInt("servers", 0) ?: 0,
                serversEnabled = sr?.optInt("enabled", 0) ?: 0,
                onlineNow = d.optInt("onlineNow", 0),
                watchingNow = d.optInt("watchingNow", 0),
                watchSecondsToday = d.optLong("watchSecondsToday", 0L)
            )
        }
        val lgRes = Api.call("GET", "/admin/log")
        if (lgRes.ok && lgRes.data != null) {
            val arr = lgRes.data.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<LogEntry>()
            for (i in 0 until minOf(arr.length(), 25)) {
                val item = arr.getJSONObject(i)
                list.add(
                    LogEntry(
                        at = item.optLong("at"),
                        action = item.optString("action"),
                        detail = item.optString("detail")
                    )
                )
            }
            logs = list
        }
        loading = false
    }

    // تحديث تلقائي كل 60 ثانية ما دامت الشاشة ظاهرة
    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(60_000L)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "لوحة التحكم",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { scope.launch { refresh() } }) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                }
            }
        }

        val s = stats
        if (s != null) {
            // 1. بطاقة بانتظار الموافقة بارزة
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUsersWithFilter("pending") },
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF59E0B).copy(alpha = 0.18f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "بانتظار الموافقة",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFFFBBF24)
                            )
                            Text(
                                text = "اضغط لمراجعة الطلبات والأجهزة الجديدة",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                        Text(
                            text = s.usersPending.ar(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFBBF24)
                        )
                    }
                }
            }

            // 2. بطاقات حية: متصل الآن · يشاهد الآن · ساعات اليوم
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenUsersWithFilter("online") },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981).copy(alpha = pulseAlpha))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("متصل الآن", fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.onlineNow.ar(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("يشاهد الآن", fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.watchingNow.ar(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("ساعات اليوم", fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                formatHoursAr(s.watchSecondsToday),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 3. بلاطات حالات المستخدمين
            item {
                val tiles = listOf(
                    Triple("نشط", s.usersActive, "active"),
                    Triple("ينتهي قريباً", s.usersExpiringSoon, "expiring"),
                    Triple("منتهٍ", s.usersExpired, "expired"),
                    Triple("محظور", s.usersBlocked, "blocked"),
                    Triple("مقفل", s.usersLocked, "locked"),
                    Triple("جديد هذا الأسبوع", s.usersNewWeek, "all")
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in tiles.chunked(3)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for ((label, count, filterKey) in row) {
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onOpenUsersWithFilter(filterKey) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(label, fontSize = 11.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            count.ar(),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. أشرطة توزيع أنواع الأجهزة + بلاطات المصادر
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("توزيع الأجهزة (${s.devicesTotal.ar()})", fontWeight = FontWeight.Bold)
                        Text(
                            "هاتف: ${s.phones.ar()} · لوحي: ${s.tablets.ar()} · تلفاز: ${s.tvs.ar()} · نشط اليوم: ${s.activeDay.ar()}",
                            fontSize = 13.sp
                        )
                        Divider(Modifier.padding(vertical = 4.dp))
                        Text("المصادر والكتالوج", fontWeight = FontWeight.Bold)
                        Text(
                            "المستودعات: ${s.reposCount.ar()} · السيرفرات: ${s.serversCount.ar()} · المفعّلة: ${s.serversEnabled.ar()}",
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 5. آخر الإجراءات الإدارية
            item {
                Text(
                    "آخر الإجراءات الإدارية",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(logs) { lg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(lg.action, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (lg.detail.isNotBlank()) {
                                Text(
                                    lg.detail,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Text(
                            formatRelativeAr(lg.at),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        } else if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
