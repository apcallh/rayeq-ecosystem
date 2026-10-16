package app.rayeq.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rayeq.BuildConfig
import app.rayeq.auth.Auth
import app.rayeq.auth.AuthState
import app.rayeq.data.*

@Composable
fun SettingsScreen(settings: AppSettings, authState: AuthState) {
    var showLicenses by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 150.dp), // حشوة سفلية 150dp (§14.9)
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("الإعدادات", style = MaterialTheme.typography.headlineMedium)
        }

        // 1. مجموعة الحساب
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الحساب", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("البريد: ${authState.email ?: "غير مسجل"}", fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (authState.accessUntil == null) "مدة الوصول: بلا حد"
                            else "الوصول حتى: ${authState.accessUntil}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(onClick = { Auth.signOut() }) {
                            Text("تسجيل الخروج")
                        }
                    }
                }
            }
        }

        // 2. مجموعة المظهر (القوالب الـ6 + AMOLED)
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("المظهر والقوالب", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in PALETTES.take(4)) {
                            FilterChip(
                                selected = settings.palette == p.id,
                                onClick = { Prefs.update { it.copy(palette = p.id) } },
                                label = { Text(p.nameAr, fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("خلفية AMOLED سوداء بالكامل", fontSize = 13.sp)
                        Switch(
                            checked = settings.amoled,
                            onCheckedChange = { v -> Prefs.update { it.copy(amoled = v) } }
                        )
                    }
                }
            }
        }

        // 3. مجموعة التشغيل والترجمة
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("المشغّل والترجمة", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("تشغيل الحلقة التالية تلقائياً", fontSize = 13.sp)
                        Switch(
                            checked = settings.autoNext,
                            onCheckedChange = { v -> Prefs.update { it.copy(autoNext = v) } }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ترجمة تلقائية مع السيرفرات العربية", fontSize = 13.sp)
                        Switch(
                            checked = settings.autoSubsOnArabicServers,
                            onCheckedChange = { v -> Prefs.update { it.copy(autoSubsOnArabicServers = v) } }
                        )
                    }
                    SubtitleStylePreview(settings)
                }
            }
        }

        // 4. النسخ الاحتياطي والمزامنة السحابية
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("البيانات والنسخ الاحتياطي", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Button(
                        onClick = { CloudSync.syncAllNow() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("مزامنة الآن مع السحابة")
                    }
                }
            }
        }

        // 5. حول والتراخيص (GPL-3.0 + OFL) (§0.2, §14.9)
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حول رايق", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("الإصدار: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontSize = 13.sp)
                    OutlinedButton(onClick = { showLicenses = true }) {
                        Text("التراخيص مفتوحة المصدر (GPL-3.0 / OFL)")
                    }
                }
            }
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("التراخيص مفتوحة المصدر") },
            text = {
                Text(
                    "• وحدة مكتبة الإضافات (:library) مشتقة من مشروع CloudStream 4.8.0 ومرخصة بموجب رخصة GNU General Public License v3.0 (GPL-3.0).\n" +
                    "• الخطوط العربية المضمنة (IBM Plex Sans Arabic, Tajawal, Almarai, Noto Naskh Arabic, Cairo) مرخصة بموجب SIL Open Font License 1.1 (OFL-1.1)."
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text("إغلاق") }
            }
        )
    }
}
