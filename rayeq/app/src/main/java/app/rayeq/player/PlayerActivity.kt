package app.rayeq.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import app.rayeq.auth.Auth
import app.rayeq.auth.Gate
import app.rayeq.data.Prefs

class PlayerActivity : ComponentActivity() {
    private val vm: PlayerVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val authState by Auth.state.collectAsState()
            // عند الحظر أو القفل أو انتهاء الصلاحية أو الطرد أثناء التشغيل: إغلاق المشغّل والانتقال لشاشة البوابة (§9.5, §15)
            LaunchedEffect(authState.gate) {
                if (authState.gate != Gate.APPROVED) {
                    finish()
                }
            }

            val isPlaying by vm.isPlaying.collectAsState()
            val posMs by vm.positionMs.collectAsState()
            val durMs by vm.durationMs.collectAsState()
            val banner by vm.toastBanner.collectAsState()
            val cfg by Prefs.settings.collectAsState()

            PlayerChrome(
                titleHeader = intent.getStringExtra("title_header") ?: "رايق",
                isLive = intent.getBooleanExtra("is_live", false),
                isPlaying = isPlaying,
                isLoadingInitial = false,
                discoveredServersCount = 0,
                positionMs = posMs,
                durationMs = durMs,
                bufferedMs = 0L,
                showRemainingTime = cfg.showRemainingTime,
                currentQualityBadge = "تلقائي",
                currentSpeed = cfg.defaultSpeed,
                resizeModeLabel = cfg.defaultResizeMode,
                isLocked = false,
                toastBanner = banner,
                resumeBannerTime = null,
                activeSkipSegment = null,
                nextEpisodeCountdownSec = null,
                servers = emptyList(),
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                subDelaySec = 0f,
                onBack = { finish() },
                onTogglePlay = {
                    vm.exoPlayer?.let { p ->
                        if (p.isPlaying) p.pause() else p.play()
                    }
                },
                onSeekTo = { vm.exoPlayer?.seekTo(it) },
                onSeekDelta = { d ->
                    vm.exoPlayer?.let { p -> p.seekTo((p.currentPosition + d).coerceAtLeast(0L)) }
                },
                onPrevEpisode = {},
                onNextEpisode = {},
                onToggleLock = {},
                onCycleResizeMode = {},
                onEnterPip = { enterPipNow() },
                onSelectServer = {},
                onSelectAudio = {},
                onSelectSubtitle = {},
                onAdjustSubDelay = {},
                onOpenOnlineSubs = {},
                onOpenDeviceSubs = {},
                onChangeSpeed = { vm.exoPlayer?.setPlaybackSpeed(it) },
                onTakeScreenshot = {},
                onToggleBackgroundAudio = {},
                onRotateOrientation = {
                    requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                },
                onSkipSegment = { seg ->
                    val end = seg.endMs
                    if (end != null) vm.exoPlayer?.seekTo(end)
                },
                onRestartFromBeginning = { vm.exoPlayer?.seekTo(0L) }
            )
        }
    }

    private fun enterPipNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val (num, den) = PlayerMath.pipAspectRatio(1920, 1080)
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(num, den))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(Prefs.settings.value.autoPip)
            }
            enterPictureInPictureMode(builder.build())
        }
    }
}
