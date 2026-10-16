package app.rayeq.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rayeq.data.ArabicDigits
import app.rayeq.data.SkipSegment

data class ServerUiItem(
    val indexNumber: Int,
    val providerName: String,
    val showProviderName: Boolean,
    val badgeText: String,
    val isStickyFavorite: Boolean,
    val isCurrent: Boolean,
    val url: String
) {
    val displayLabel: String
        get() {
            val nAr = ArabicDigits.toEasternArabic(indexNumber.toString())
            val base = if (showProviderName && providerName.isNotBlank()) {
                "سيرفر $nAr · $providerName"
            } else {
                "سيرفر $nAr"
            }
            return if (isStickyFavorite) "$base · المفضّل" else base
        }
}

data class AudioTrackUiItem(
    val index: Int,
    val label: String,
    val channelLayout: String,
    val isSupported: Boolean,
    val isSelected: Boolean
)

data class SubtitleTrackUiItem(
    val id: String,
    val label: String,
    val isArabic: Boolean,
    val isSelected: Boolean
)

@Composable
fun PlayerChrome(
    titleHeader: String,
    isLive: Boolean,
    isPlaying: Boolean,
    isLoadingInitial: Boolean,
    discoveredServersCount: Int,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    showRemainingTime: Boolean,
    currentQualityBadge: String,
    currentSpeed: Float,
    resizeModeLabel: String,
    isLocked: Boolean,
    toastBanner: String?,
    resumeBannerTime: String?,
    activeSkipSegment: SkipSegment?,
    nextEpisodeCountdownSec: Int?,
    servers: List<ServerUiItem>,
    audioTracks: List<AudioTrackUiItem>,
    subtitleTracks: List<SubtitleTrackUiItem>,
    subDelaySec: Float,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekDelta: (Long) -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onToggleLock: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onEnterPip: () -> Unit,
    onSelectServer: (String) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onAdjustSubDelay: (Float) -> Unit,
    onOpenOnlineSubs: () -> Unit,
    onOpenDeviceSubs: () -> Unit,
    onChangeSpeed: (Float) -> Unit,
    onTakeScreenshot: () -> Unit,
    onToggleBackgroundAudio: () -> Unit,
    onRotateOrientation: () -> Unit,
    onSkipSegment: (SkipSegment) -> Unit,
    onRestartFromBeginning: () -> Unit
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var activeSheet by remember { mutableStateOf<String?>(null) } // servers | audio | subs | more
    var showMoreTools by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            if (!isLocked && !isLive) {
                                val isRightSide = offset.x > size.width / 2f
                                // في RTL: النقر المزدوج يميناً يرجع أو يقدم حسب الموضع
                                onSeekDelta(if (isRightSide) -10_000L else 10_000L)
                            }
                        },
                        onLongPress = {
                            if (!isLocked && !isLive) {
                                onChangeSpeed(2.0f)
                            }
                        }
                    )
                }
        ) {
            // مؤشر التحميل الأولي (§9.5)
            if (isLoadingInitial) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8))
                    Text(
                        text = "وجدنا ${ArabicDigits.toEasternArabic(discoveredServersCount.toString())} سيرفرات…",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // رسالة التنبيه العلوية (انتقلنا إلى سيرفرك المفضّل / جودة أعلى / سيرفر عربي)
            if (!toastBanner.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 68.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xDD0B1020))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(toastBanner, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            // شريط الاستئناف «أكملنا من 12:34 · من البداية»
            if (!resumeBannerTime.isNullOrBlank() && !isLive) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp, bottom = 96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC131B2E))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("أكملنا من $resumeBannerTime", color = Color.White, fontSize = 12.sp)
                    Text(
                        text = "من البداية",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onRestartFromBeginning() }
                    )
                }
            }

            // زر تخطّي المقدمة / الخاتمة (بيانات حقيقية فقط من /v1/skip — §11.5)
            if (activeSkipSegment != null && !isLive) {
                Button(
                    onClick = { onSkipSegment(activeSkipSegment) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 96.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xEE38BDF8))
                ) {
                    Text(activeSkipSegment.labelAr, color = Color(0xFF062032), fontWeight = FontWeight.Bold)
                }
            }

            // عدّ تنازلي حلقي للحلقة التالية
            if (nextEpisodeCountdownSec != null && !isLive) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 148.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xEE131B2E))
                        .clickable { onNextEpisode() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "الحلقة التالية خلال ${ArabicDigits.toEasternArabic(nextEpisodeCountdownSec.toString())} ث",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (isLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        IconButton(
                            onClick = onToggleLock,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .clip(CircleShape)
                                .background(Color(0xAA000000))
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "فك القفل", tint = Color.White)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xCC000000),
                                        Color.Transparent,
                                        Color.Transparent,
                                        Color(0xDD000000)
                                    )
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 1. الشريط العلوي + صف الأدوات (§11.2)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    IconButton(onClick = onBack) {
                                        Icon(Icons.Default.ArrowForward, contentDescription = "رجوع", tint = Color.White)
                                    }
                                    Text(
                                        text = titleHeader,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = { activeSheet = "audio" }) {
                                        Text("الصوت", color = Color.White, fontSize = 12.sp)
                                    }
                                    if (!isLive) {
                                        TextButton(onClick = { activeSheet = "subs" }) {
                                            Text("الترجمة", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0x3338BDF8))
                                            .clickable { activeSheet = "servers" }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = currentQualityBadge,
                                            color = Color(0xFF38BDF8),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    TextButton(onClick = { activeSheet = "servers" }) {
                                        Text("السيرفرات", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }

                            // صف الأدوات: السرعة · لقطة شاشة · صوت بالخلفية · تدوير · المزيد
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssistChip(
                                    onClick = {
                                        val nextSpd = when (currentSpeed) {
                                            1.0f -> 1.25f
                                            1.25f -> 1.5f
                                            1.5f -> 2.0f
                                            2.0f -> 0.75f
                                            else -> 1.0f
                                        }
                                        onChangeSpeed(nextSpd)
                                    },
                                    label = { Text("${currentSpeed}X", color = Color.White, fontSize = 11.sp) }
                                )
                                AssistChip(
                                    onClick = onTakeScreenshot,
                                    label = { Text("لقطة شاشة", color = Color.White, fontSize = 11.sp) }
                                )
                                AssistChip(
                                    onClick = onToggleBackgroundAudio,
                                    label = { Text("صوت بالخلفية", color = Color.White, fontSize = 11.sp) }
                                )
                                AssistChip(
                                    onClick = onRotateOrientation,
                                    label = { Text("تدوير", color = Color.White, fontSize = 11.sp) }
                                )
                                AssistChip(
                                    onClick = { showMoreTools = !showMoreTools },
                                    label = { Text("‹ المزيد", color = Color.White, fontSize = 11.sp) }
                                )
                            }
                        }

                        // 2. شريط التقدم + الصف السفلي (§11.2)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isLive) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEF4444))
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("مباشر", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            } else {
                                val safeDur = durationMs.coerceAtLeast(1L)
                                Slider(
                                    value = (positionMs.toFloat() / safeDur.toFloat()).coerceIn(0f, 1f),
                                    onValueChange = { frac ->
                                        onSeekTo((frac * safeDur).toLong())
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = PlayerMath.formatClockDisplay(positionMs, durationMs, showRemainingTime),
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            // الصف السفلي: قفل | السابق، -10، تشغيل، +10، التالي | نسبة العرض · PiP
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onToggleLock) {
                                    Icon(Icons.Default.Lock, contentDescription = "قفل", tint = Color.White)
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!isLive) {
                                        TextButton(onClick = onPrevEpisode) {
                                            Text("السابق", color = Color.White, fontSize = 12.sp)
                                        }
                                        TextButton(onClick = { onSeekDelta(-10_000L) }) {
                                            Text("-١٠", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .background(Color(0x44FFFFFF))
                                            .clickable { onTogglePlay() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Clear else Icons.Default.PlayArrow,
                                            contentDescription = "تشغيل/إيقاف",
                                            tint = Color.White
                                        )
                                    }
                                    if (!isLive) {
                                        TextButton(onClick = { onSeekDelta(10_000L) }) {
                                            Text("+١٠", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(onClick = onNextEpisode) {
                                            Text("التالي", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = onCycleResizeMode) {
                                        Text(resizeModeLabel, color = Color.White, fontSize = 12.sp)
                                    }
                                    TextButton(onClick = onEnterPip) {
                                        Text("PiP", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // اللوح الجانبي للسيرفرات / الصوت / الترجمة
            if (activeSheet != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000))
                        .clickable { activeSheet = null }
                ) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(340.dp)
                            .clickable(enabled = false) {},
                        color = Color(0xF2131B2E)
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when (activeSheet) {
                                "servers" -> {
                                    Text("اختيار السيرفر", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        itemsIndexed(servers) { _, s ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (s.isCurrent) Color(0x3338BDF8) else Color(0x1AFFFFFF))
                                                    .clickable {
                                                        onSelectServer(s.url)
                                                        activeSheet = null
                                                    }
                                                    .padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (s.isCurrent) "✓ ${s.displayLabel}" else s.displayLabel,
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (s.isCurrent) FontWeight.Bold else FontWeight.Normal
                                                )
                                                Text(
                                                    text = s.badgeText,
                                                    color = Color(0xFF38BDF8),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                "audio" -> {
                                    Text("المسارات الصوتية", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        itemsIndexed(audioTracks) { _, a ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (a.isSelected) Color(0x3338BDF8) else Color(0x1AFFFFFF))
                                                    .clickable {
                                                        onSelectAudio(a.index)
                                                        activeSheet = null
                                                    }
                                                    .padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = if (a.isSelected) "✓ ${a.label}" else a.label,
                                                    color = if (a.isSupported) Color.White else Color.Gray,
                                                    fontSize = 13.sp
                                                )
                                                if (a.channelLayout.isNotBlank()) {
                                                    Text(a.channelLayout, color = Color(0xFF38BDF8), fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                                "subs" -> {
                                    Text("الترجمة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = onOpenOnlineSubs, modifier = Modifier.weight(1f)) {
                                            Text("من الإنترنت", fontSize = 12.sp)
                                        }
                                        OutlinedButton(onClick = onOpenDeviceSubs, modifier = Modifier.weight(1f)) {
                                            Text("من الجهاز", fontSize = 12.sp)
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("مزامنة الترجمة: ${"%.2f".format(subDelaySec)} ث", color = Color.White, fontSize = 12.sp)
                                        Row {
                                            TextButton(onClick = { onAdjustSubDelay(-0.25f) }) { Text("-0.25", color = Color.White) }
                                            TextButton(onClick = { onAdjustSubDelay(0.25f) }) { Text("+0.25", color = Color.White) }
                                        }
                                    }
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        item {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        onSelectSubtitle(null)
                                                        activeSheet = null
                                                    }
                                                    .padding(10.dp)
                                            ) {
                                                Text("إيقاف الترجمة", color = Color.White, fontSize = 13.sp)
                                            }
                                        }
                                        itemsIndexed(subtitleTracks) { _, sub ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (sub.isSelected) Color(0x3338BDF8) else Color(0x1AFFFFFF))
                                                    .clickable {
                                                        onSelectSubtitle(sub.id)
                                                        activeSheet = null
                                                    }
                                                    .padding(10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = if (sub.isSelected) "✓ ${sub.label}" else sub.label,
                                                    color = Color.White,
                                                    fontSize = 13.sp
                                                )
                                                if (sub.isArabic) {
                                                    Text("AR", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
