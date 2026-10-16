package app.rayeq.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.rayeq.data.*
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PlayerBridge {
    @Volatile var activePlayer: ExoPlayer? = null
}

data class RankedServerCandidate(
    val link: ExtractorLink,
    val source: String,
    val isArabic: Boolean,
    val nominalQuality: Int,
    val realAssessment: RealQuality.Assessment? = null,
    val stickyLevel: Int = 0,
    val sortKey: Long = Long.MAX_VALUE
)

class PlayerVM : ViewModel() {
    var exoPlayer: ExoPlayer? = null
        private set

    private val hlsProbeSemaphore = kotlinx.coroutines.sync.Semaphore(3)
    private val hlsMeasuredQuality = ConcurrentHashMap<String, Int>()
    private val triedUrls = ConcurrentHashMap.newKeySet<String>()

    val candidates = MutableStateFlow<List<RankedServerCandidate>>(emptyList())
    val currentUrl = MutableStateFlow<String?>(null)
    val toastBanner = MutableStateFlow<String?>(null)
    val isPlaying = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)

    @Volatile private var currentReq: PlayRequest? = null
    @Volatile private var isManualSelection = false
    @Volatile private var isReattachingSubtitle = false
    @Volatile private var pendingResumeMs: Long = 0L
    @Volatile private var prefetchedNext = false

    fun initPlayer(context: Context, req: PlayRequest) {
        currentReq = req
        val cfg = Prefs.settings.value
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                cfg.forwardBufferSec * 1000,
                cfg.forwardBufferSec * 1000,
                1500,
                2500
            )
            .setBackBuffer(cfg.backBufferSec * 1000, true)
            .build()

        val renderersFactory = NextRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val player = ExoPlayer.Builder(context.applicationContext, renderersFactory)
            .setLoadControl(loadControl)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isReattachingSubtitle = false
                    // إصلاح خطأ تجاهل seekTo قبل prepare(): إعادة تطبيق الموضع عند STATE_READY (§10.1)
                    if (pendingResumeMs > 0L) {
                        val target = pendingResumeMs
                        pendingResumeMs = 0L
                        player.seekTo(target)
                    }
                    onCurrentServerReady()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
                Presence.setState(if (playing) "watching" else "browsing")
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isReattachingSubtitle) {
                    // إذا فشل التشغيل أثناء إرفاق ترجمة جانبية، فالمشكلة في ملف الترجمة — لا تنتقل لسيرفر آخر (§11.3)
                    isReattachingSubtitle = false
                    showBanner("تعذّر تحميل ملف الترجمة")
                    return
                }
                // خطأ أثناء التشغيل أو التحميل -> انتقل للسيرفر التالي من نفس الثانية
                val curPos = player.currentPosition.coerceAtLeast(0L)
                if (curPos > 0L) pendingResumeMs = curPos
                playNextUntriedCandidate()
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            private var segmentBytes = 0L
            private var segmentDurationMs = 0L

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                val dur = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
                if (dur > 0 && loadEventInfo.bytesLoaded > 0) {
                    segmentBytes += loadEventInfo.bytesLoaded
                    segmentDurationMs += dur
                }
            }
        })

        exoPlayer = player
        PlayerBridge.activePlayer = player

        // حلقة حفظ الموضع كل 5 ثوانٍ + الجلب المسبق في آخر 3 دقائق (§8.5, §10.1)
        viewModelScope.launch {
            while (isActive) {
                delay(5_000L)
                val p = exoPlayer ?: continue
                if (p.isPlaying && req.live == null) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.coerceAtLeast(0L)
                    positionMs.value = pos
                    durationMs.value = dur
                    Library.savePosition(req.title, req.season, req.episode, req.absEpisode, pos, dur)

                    if (!prefetchedNext && dur > 0L) {
                        val rem = dur - pos
                        if (rem <= Engine.PREFETCH_BEFORE_END_MS || (dur < 360_000L && pos >= dur / 2)) {
                            prefetchedNext = true
                        }
                    }
                }
            }
        }

        // مراقبة سحب السيرفر من المدير أثناء التشغيل (§9.5)
        viewModelScope.launch {
            SourceConfig.version.collect {
                val curUrl = currentUrl.value ?: return@collect
                val curCand = candidates.value.find { it.link.url == curUrl } ?: return@collect
                if (SourceConfig.revokedName(curCand.source)) {
                    pendingResumeMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    exoPlayer?.stop()
                    showBanner(
                        if (req.live != null) "أوقفت الإدارة هذه القناة"
                        else "أوقفت الإدارة هذا السيرفر، جارٍ الانتقال…"
                    )
                    playNextUntriedCandidate()
                }
            }
        }
    }

    /**
     * لكل رابط يُنشأ OkHttpDataSource جديد مع referer وheaders الخاصة بالرابط (§11.1)
     */
    fun prepareLink(context: Context, link: ExtractorLink, startPositionMs: Long = 0L) {
        val player = exoPlayer ?: return
        currentUrl.value = link.url
        triedUrls.add(link.url)
        if (startPositionMs > 0L) {
            pendingResumeMs = startPositionMs
        }

        val headersMap = mutableMapOf<String, String>()
        if (link.referer.isNotBlank()) headersMap["Referer"] = link.referer
        headersMap.putAll(link.headers)

        val okHttpFactory = OkHttpDataSource.Factory(Net.client)
            .setDefaultRequestProperties(headersMap)
        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val mimeType = when (link.type) {
            ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
            ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(link.url))
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * فحص HLS master playlist مرة واحدة لكل رابط HLS (<= 64 KB، مهلة 5 ثوانٍ، 3 بالتوازي) (§9.1)
     */
    fun probeHlsMasterIfNeeded(link: ExtractorLink, onMeasured: (Int) -> Unit) {
        if (!link.isM3u8 && link.type != ExtractorLinkType.M3U8 && !link.url.contains(".m3u8", ignoreCase = true)) {
            return
        }
        if (hlsMeasuredQuality.containsKey(link.url)) return
        viewModelScope.launch(Dispatchers.IO) {
            hlsProbeSemaphore.acquire()
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val rb = Request.Builder().url(link.url)
                if (link.referer.isNotBlank()) rb.header("Referer", link.referer)
                link.headers.forEach { (k, v) -> rb.header(k, v) }
                rb.header("Range", "bytes=0-65535")
                client.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string()?.take(65536) ?: return@use
                    val tier = LinkQuality.fromHlsMaster(text)
                    if (tier > 0) {
                        hlsMeasuredQuality[link.url] = tier
                        withContext(Dispatchers.Main) { onMeasured(tier) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                hlsProbeSemaphore.release()
            }
        }
    }

    private fun onCurrentServerReady() {
        val req = currentReq ?: return
        val curUrl = currentUrl.value ?: return
        val cand = candidates.value.find { it.link.url == curUrl } ?: return
        if (isManualSelection) {
            // حفظ السيرفر المثبّت يدوياً لكل عنوان (§9.4)
            val pick = StickyServer.createPick(
                source = cand.source,
                linkName = cand.link.name,
                rawQuality = cand.link.quality,
                url = cand.link.url,
                currentEpisode = req.episode
            )
            Library.saveStickyPick(req.title.key, pick)
        }
    }

    private fun playNextUntriedCandidate() {
        val next = candidates.value.firstOrNull { it.link.url !in triedUrls } ?: return
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        prepareLink(ctx, next.link, pendingResumeMs)
    }

    fun showBanner(msg: String) {
        viewModelScope.launch {
            toastBanner.value = msg
            delay(5_000L)
            if (toastBanner.value == msg) toastBanner.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer?.release()
        if (PlayerBridge.activePlayer == exoPlayer) {
            PlayerBridge.activePlayer = null
        }
        exoPlayer = null
    }
}
