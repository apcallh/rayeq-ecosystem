package app.rayeq.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rayeq.auth.Auth
import app.rayeq.auth.AuthState
import app.rayeq.auth.Gate
import app.rayeq.data.AppInfo
import app.rayeq.data.ArabicDigits
import app.rayeq.data.RemoteAppConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GateScreen(authState: AuthState) {
    val scope = rememberCoroutineScope()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // إعادة الفحص كل 12 ثانية ما دامت شاشة البوابة ظاهرة (§14.2)
    LaunchedEffect(authState.gate) {
        while (true) {
            delay(12_000L)
            nowMs = System.currentTimeMillis()
            Auth.heartbeatNow()
        }
    }

    LaunchedEffect(authState.lockedUntil) {
        while (authState.lockedUntil != null && nowMs < authState.lockedUntil) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
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
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val title = when (authState.gate) {
                    Gate.PENDING -> "طلبك قيد المراجعة"
                    Gate.LOCKED -> "الحساب مقفل مؤقتاً"
                    Gate.BLOCKED -> "تم إيقاف الحساب"
                    Gate.EXPIRED -> "انتهت مدة الوصول"
                    Gate.KICKED -> "تم تسجيل خروجك"
                    else -> "حالة الحساب"
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                val subtitle = when (authState.gate) {
                    Gate.PENDING -> "بمجرد موافقة الإدارة سيفتح التطبيق تلقائياً خلال ثوانٍ دون الحاجة لإعادة التشغيل."
                    Gate.LOCKED -> {
                        val lu = authState.lockedUntil
                        if (lu != null && lu > nowMs) {
                            val remSec = ((lu - nowMs) / 1000L).coerceAtLeast(0L)
                            "يُفتح تلقائياً بعد ${ArabicDigits.toEasternArabic(remSec.toString())} ثانية"
                        } else {
                            "الحساب مقفل حالياً من قِبل الإدارة."
                        }
                    }
                    Gate.BLOCKED -> "تم حظر هذا الحساب من قِبل الإدارة."
                    Gate.EXPIRED -> "انتهت صلاحية الوصول المحددة لحسابك."
                    Gate.KICKED -> "تم إنهاء الجلسة من الإدارة. يمكنك تسجيل الدخول مجدداً."
                    else -> ""
                }
                Text(subtitle, textAlign = TextAlign.Center, fontSize = 13.sp)

                if (authState.gate == Gate.KICKED) {
                    Button(
                        onClick = { Auth.signOut() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("تسجيل الدخول مجدداً")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { scope.launch { Auth.heartbeatNow() } }) {
                            Text("تحقّق الآن")
                        }
                        OutlinedButton(onClick = { Auth.signOut() }) {
                            Text("تسجيل الخروج")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MaintenanceScreen(config: RemoteAppConfig) {
    // تحدّث نفسها كل 15 ثانية وعند الاستئناف (§14.2)
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            AppInfo.refresh()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("التطبيق في وضع الصيانة", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = config.maintenanceMessage.ifBlank { "نعمل على تحديث الخدمة، يرجى المحاولة بعد قليل." },
                    textAlign = TextAlign.Center
                )
                Button(onClick = { AppInfo.refresh() }) {
                    Text("إعادة المحاولة")
                }
            }
        }
    }
}
