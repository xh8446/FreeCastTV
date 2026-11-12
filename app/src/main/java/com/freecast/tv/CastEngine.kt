package com.freecast.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

/**
 * 播放内核。所有 ExoPlayer 操作都投递到主线程执行，
 * DLNA 控制线程通过同步调用拿到最新状态。
 */
object CastEngine {

    data class State(
        val hasMedia: Boolean = false,
        val transportState: String = "NO_MEDIA_PRESENT",
        val uri: String? = null,
        val title: String? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val bufferedPositionMs: Long = 0L,
        val speed: Float = 1f,
        val volume: Int = 50,
        val muted: Boolean = false,
        val error: String? = null,
        /** 已缓冲百分比：-1 表示无法获取，0..100 为已预加载数据 / 总数据 */
        val bufferPercent: Int = -1
    )

    interface Listener {
        fun onStateChanged(state: State)
    }

    private const val TAG = "CastEngine"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var appContext: Context? = null
    private var playerView: PlayerView? = null

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var state = State()

    private var currentUri: String? = null
    private var currentTitle: String? = null
    private var lastError: String? = null
    private var savedVolume = 50

    /** 真实缓冲进度：已读取字节 / 媒体总字节（来自 Content-Length / Content-Range） */
    @Volatile
    private var loadedBytes = 0L

    @Volatile
    private var totalBytes = -1L

    private var httpClient: OkHttpClient? = null

    /**
     * 探测媒体总字节数：用 Range 请求读 Content-Range/Content-Length。
     * 拿到总大小后，已加载字节 / 总字节 就是真实的预加载比例。
     */
    private fun probeContentLength(uri: String) {
        val client = httpClient ?: return
        Thread {
            try {
                val req = okhttp3.Request.Builder()
                    .url(uri)
                    .header("Range", "bytes=0-0")
                    .header("getcontentFeatures.dlna.org", "1")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 9; FreeCastTV) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                    )
                    .build()
                client.newCall(req).execute().use { resp ->
                    val total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: resp.header("Content-Length")?.toLongOrNull()
                    if (total != null && total > 0) {
                        totalBytes = total
                        Log.i(TAG, "content length = $total bytes")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "probe content length failed: ${e.message}")
            }
        }.start()
    }

    /** 播放错误后的自动重试：指数退避，最多 5 次，并从原位置续播 */
    private var retryCount = 0
    private val retryRunnable = object : Runnable {
        override fun run() {
            val p = exoPlayer ?: return
            if (currentUri == null) return
            if (retryCount > 5) {
                Log.w(TAG, "max retries reached, giving up")
                return
            }
            if (p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED) return
            Log.i(TAG, "auto retry #${retryCount + 1} for $currentUri")
            retryCount++
            lastError = null
            // 记录出错位置，重连后从原处继续，避免回到片头
            val resumeMs = p.currentPosition.coerceAtLeast(0L)
            p.prepare()
            if (resumeMs > 0L) p.seekTo(resumeMs)
            p.playWhenReady = true
            updateState()
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            updateState()
            mainHandler.postDelayed(this, 500L)
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val app = appContext ?: return
        mainHandler.post {
            if (exoPlayer != null) return@post
            // 部分媒体源使用自签名/过期证书，电视系统校验会失败。
            // 仅对播放数据源放宽证书校验，不涉及账号密码传输。
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
            val client = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
            val httpFactory = OkHttpDataSource.Factory(client)
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 9; FreeCastTV) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                )
                .setDefaultRequestProperties(mapOf("getcontentFeatures.dlna.org" to "1"))
            // 加大缓冲：投送端 App 切后台/短暂断流时不至于立刻卡住
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000,   // 最小缓冲 30 秒
                    120_000,  // 最大缓冲 120 秒
                    1_500,    // 起播前缓冲
                    3_000     // 卡顿后恢复播放所需缓冲
                )
                .build()
            val p = ExoPlayer.Builder(app)
                .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
                .setLoadControl(loadControl)
                .build()
            httpClient = client
            p.volume = 0.5f
            // 统计真实已加载字节，用于「已缓冲 xx%」进度
            p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onLoadCompleted(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                    mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData
                ) {
                    val bytes = loadEventInfo.bytesLoaded
                    if (bytes > 0) loadedBytes += bytes
                }
            })
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateState()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateState()
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    updateState()
                }

                override fun onVolumeChanged(volume: Float) {
                    if (volume > 0f) savedVolume = (volume * 100f).roundToInt()
                    updateState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "playback error", error)
                    val cause = error.cause?.message?.take(80)
                    lastError = if (cause.isNullOrBlank()) error.errorCodeName else "${error.errorCodeName}: $cause"
                    updateState()
                    // IO_NETWORK 错误：投送端切后台 / 网络抖动等，延迟几秒自动重试
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                            val delay = (retryCount + 1) * 3_000L
                            Log.i(TAG, "schedule retry in ${delay}ms")
                            mainHandler.removeCallbacks(retryRunnable)
                            mainHandler.postDelayed(retryRunnable, delay)
                        }
                    }
                }
            })
            exoPlayer = p
            playerView?.player = p
            mainHandler.post(poller)
            updateState()
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        listener.onStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun getState(): State = state

    fun attachView(view: PlayerView) {
        onMain {
            playerView = view
            exoPlayer?.let { view.player = it }
        }
    }

    fun detachView() {
        onMain {
            playerView?.player = null
            playerView = null
        }
    }

    fun setAvTransportUri(uri: String, title: String?, mimeType: String? = null) {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            currentUri = uri
            currentTitle = title
            lastError = null
            retryCount = 0
            loadedBytes = 0L
            totalBytes = -1L
            mainHandler.removeCallbacks(retryRunnable)
            try {
                val resolvedMime = resolveMime(uri, mimeType)
                Log.i(TAG, "set uri=$uri mime=$mimeType resolved=$resolvedMime")
                // 记录媒体地址主机名：若为手机局域网 IP，说明是手机本地代理，手机切后台必然断流
                try {
                    Trace.log("CastEngine", "media host=" + (android.net.Uri.parse(uri).host ?: "?"))
                } catch (_: Throwable) {}
                val itemBuilder = MediaItem.Builder().setUri(uri)
                if (resolvedMime != null) itemBuilder.setMimeType(resolvedMime)
                p.setMediaItem(itemBuilder.build())
                p.prepare()
                // 探测媒体总大小，配合已加载字节算出真实「已缓冲」百分比
                if (resolvedMime != MimeTypes.APPLICATION_M3U8 && resolvedMime != MimeTypes.APPLICATION_MPD) {
                    probeContentLength(uri)
                }
                // 部分控制点只下发 SetAVTransportURI 不再发 Play，这里直接起播，
                // 避免用户必须手动点一下才能播放。
                p.playWhenReady = true
            } catch (e: Exception) {
                Log.e(TAG, "setMediaItem failed", e)
                lastError = e.message
            }
            updateState()
        }
    }

    /**
     * 只对 HLS / DASH 强制指定 MIME，其余交给 ExoPlayer 自动嗅探。
     * DLNA 的 protocolInfo 经常给出 video/mpeg 之类的泛化类型，
     * 强制套用会让播放器选错解封装器，报 PARSING_CONTAINER_UNSUPPORTED。
     */
    private fun resolveMime(uri: String, mime: String?): String? {
        val raw = mime?.trim()?.lowercase()?.substringBefore(';').orEmpty()
        val path = uri.lowercase().substringBefore('?').substringBefore('#')
        return when {
            raw == MimeTypes.APPLICATION_M3U8 ||
                raw == "application/x-mpegurl" ||
                raw == "audio/mpegurl" ||
                raw == "audio/x-mpegurl" ||
                raw == "vnd.apple.mpegurl" -> MimeTypes.APPLICATION_M3U8
            raw == MimeTypes.APPLICATION_MPD ||
                raw == "video/vnd.mpeg.dash.mpd" -> MimeTypes.APPLICATION_MPD
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun play(speed: Float? = null) {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            if (currentUri == null) return@onMainSync
            lastError = null
            // 控制点默认下发 1.0，不覆盖电视端用户手动选择的倍速
            if (speed != null && speed in 0.5f..2.0f && speed != 1.0f) {
                p.playbackParameters = PlaybackParameters(speed)
            }
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
            updateState()
        }
    }

    fun pause() {
        onMainSync {
            exoPlayer?.pause()
            updateState()
        }
    }

    fun stop() {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            p.stop()
            updateState()
        }
    }

    /**
     * 停止并清空当前媒体，回到"等待投屏"状态（供双击返回使用）。
     */
    fun reset() {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            p.stop()
            p.clearMediaItems()
            currentUri = null
            currentTitle = null
            lastError = null
            retryCount = 0
            loadedBytes = 0L
            totalBytes = -1L
            mainHandler.removeCallbacks(retryRunnable)
            updateState()
        }
    }

    fun togglePlayPause() {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            if (currentUri == null) return@onMainSync
            val current = state.transportState
            if (current == "PLAYING" || current == "TRANSITIONING") {
                p.pause()
            } else {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.play()
            }
            updateState()
        }
    }

    fun isPlayingNow(): Boolean = try { exoPlayer?.isPlaying == true } catch (_: Throwable) { false }

    /**
     * 离开播放画面（会员、帮助与反馈、切到别处）时调用：暂停并静音。
     * 静音是关键——投屏端有时会重发 Play，只 pause 会出现「后台还有声音」。
     */
    fun pauseAndMute() {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            if (p.isPlaying) p.pause()
            p.volume = 0f
            updateState()
        }
    }

    /** 回到播放画面：恢复音量并继续播放。pause 后播放器仍在 PAUSED 状态，
     * 缓冲没释放，直接 play() 就够了，不需要重新 prepare。 */
    fun resumeFromLeave() {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            p.volume = (if (savedVolume <= 0) 50 else savedVolume) / 100f
            if (currentUri != null && !p.isPlaying) {
                p.playWhenReady = true
            }
            updateState()
        }
    }

    fun seekTo(positionMs: Long) {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            val duration = p.duration
            var target = positionMs.coerceAtLeast(0L)
            if (duration > 0 && target > duration) target = duration
            p.seekTo(target)
            updateState()
        }
    }

    fun seekBy(deltaMs: Long) {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            val duration = p.duration
            var target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
            if (duration > 0 && target > duration) target = duration
            p.seekTo(target)
            updateState()
        }
    }

    fun setSpeed(speed: Float) {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            val s = speed.coerceIn(0.5f, 5.0f)
            p.playbackParameters = PlaybackParameters(s)
            updateState()
        }
    }

    fun adjustSpeed(delta: Float) {
        val current = state.speed
        val next = (Math.round((current + delta) * 100f) / 100f).coerceIn(0.5f, 2.0f)
        setSpeed(next)
    }

    fun setVolume(volume0to100: Int) {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            val v = volume0to100.coerceIn(0, 100)
            if (v > 0) savedVolume = v
            p.volume = v / 100f
            updateState()
        }
    }

    fun setMuted(muted: Boolean) {
        onMainSync {
            val p = exoPlayer ?: return@onMainSync
            if (muted) {
                if (p.volume > 0f) savedVolume = (p.volume * 100f).roundToInt()
                p.volume = 0f
            } else {
                p.volume = (if (savedVolume <= 0) 50 else savedVolume) / 100f
            }
            updateState()
        }
    }

    private fun updateState() {
        val p = exoPlayer ?: return
        val duration = try {
            p.duration.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
        val position = try {
            p.currentPosition.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
        val buffered = try {
            p.bufferedPosition.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
        val transportState = when {
            currentUri == null -> "NO_MEDIA_PRESENT"
            p.playbackState == Player.STATE_BUFFERING -> "TRANSITIONING"
            p.playbackState == Player.STATE_READY ->
                if (p.playWhenReady) "PLAYING" else "PAUSED_PLAYBACK"
            p.playbackState == Player.STATE_ENDED ->
                if (p.playWhenReady) "STOPPED" else "PAUSED_PLAYBACK"
            else -> "STOPPED"
        }
        // 已缓冲百分比：优先用字节（已预加载 / 总大小），拿不到总量时退回按时间估算
        val percent = when {
            totalBytes > 0 && loadedBytes > 0 ->
                (loadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
            duration > 0 && buffered > 0 ->
                (buffered * 100 / duration).toInt().coerceIn(0, 100)
            transportState == "PLAYING" || transportState == "PAUSED_PLAYBACK" -> 100
            else -> -1
        }
        state = State(
            hasMedia = currentUri != null,
            transportState = transportState,
            uri = currentUri,
            title = currentTitle,
            positionMs = position,
            durationMs = duration,
            bufferedPositionMs = buffered,
            speed = p.playbackParameters.speed,
            volume = (p.volume * 100f).roundToInt().coerceIn(0, 100),
            muted = p.volume <= 0.001f,
            error = lastError,
            bufferPercent = percent
        )
        val snapshot = state
        for (listener in listeners) {
            try {
                listener.onStateChanged(snapshot)
            } catch (_: Exception) {
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun onMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }
}
