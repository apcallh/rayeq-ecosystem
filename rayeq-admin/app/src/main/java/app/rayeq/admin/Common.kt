package app.rayeq.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ARABIC_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

fun String.toArabicDigits(): String {
    val sb = StringBuilder(length)
    for (c in this) {
        if (c in '0'..'9') sb.append(ARABIC_DIGITS[c - '0']) else sb.append(c)
    }
    return sb.toString()
}

fun Number.ar(): String = this.toString().toArabicDigits()

fun formatRelativeAr(timestampMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs == null || timestampMs <= 0L) return "لم يظهر بعد"
    val diffSec = ((nowMs - timestampMs) / 1000L).coerceAtLeast(0L)
    if (diffSec < 60) return "الآن"
    val mins = diffSec / 60
    if (mins < 60) {
        return when (mins) {
            1L -> "قبل دقيقة"
            2L -> "قبل دقيقتين"
            in 3L..10L -> "قبل ${mins.ar()} دقائق"
            else -> "قبل ${mins.ar()} دقيقة"
        }
    }
    val hours = mins / 60
    if (hours < 24) {
        return when (hours) {
            1L -> "قبل ساعة"
            2L -> "قبل ساعتين"
            in 3L..10L -> "قبل ${hours.ar()} ساعات"
            else -> "قبل ${hours.ar()} ساعة"
        }
    }
    val days = hours / 24
    return when (days) {
        1L -> "منذ يوم"
        2L -> "منذ يومين"
        in 3L..10L -> "منذ ${days.ar()} أيام"
        else -> "منذ ${days.ar()} يوماً"
    }
}

fun formatHoursAr(seconds: Long): String {
    if (seconds <= 0L) return "٠ د"
    val mins = seconds / 60
    if (mins < 60) return "${mins.ar()} د"
    val h = seconds / 3600.0
    return String.format(Locale.US, "%.1f س", h).toArabicDigits()
}

fun formatDateAr(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) return "بلا حد"
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
    return sdf.format(Date(epochMs)).toArabicDigits()
}

fun statusLabelAr(status: String): String = when (status) {
    "approved" -> "نشط"
    "pending" -> "بانتظار الموافقة"
    "blocked" -> "محظور"
    "locked" -> "مقفل"
    "expired" -> "منتهٍ"
    else -> status
}

fun statusColor(status: String): Color = when (status) {
    "approved" -> Color(0xFF10B981)
    "pending" -> Color(0xFFF59E0B)
    "blocked" -> Color(0xFFEF4444)
    "locked" -> Color(0xFFF97316)
    "expired" -> Color(0xFF94A3B8)
    else -> Color(0xFF64748B)
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun WeekBars(days: List<DayUsage>, modifier: Modifier = Modifier) {
    val maxSec = (days.maxOfOrNull { it.seconds } ?: 1L).coerceAtLeast(1L)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        for (d in days) {
            val frac = (d.seconds.toFloat() / maxSec.toFloat()).coerceIn(0.06f, 1f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .fillMaxHeight(frac * 0.75f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = d.day.takeLast(5).toArabicDigits(),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
