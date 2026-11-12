package com.freecast.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.freecast.tv.upnp.SoapHandler
import com.freecast.tv.upnp.DiscoveryProbe
import com.freecast.tv.upnp.SsdpServer
import com.freecast.tv.upnp.UpnpHttpServer

class CastService : Service(), CastEngine.Listener {

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "freecast_service"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CastService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var httpServer: UpnpHttpServer? = null
    private var ssdpServer: SsdpServer? = null
    private var discoveryProbe: DiscoveryProbe? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var notificationManager: NotificationManager? = null
    private var lastNotificationText: String = ""
    private val netHandler = Handler(Looper.getMainLooper())

    private val netCheck = object : Runnable {
        override fun run() {
            try {
                checkNetwork()
            } catch (e: Exception) {
                Log.w(TAG, "network check failed", e)
            }
            netHandler.postDelayed(this, 8000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 服务初始化任何异常都不能让进程崩掉：崩了等于开机自启失效、打开 App 黑屏闪退
        try {
            isRunning = true
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification(notificationText()))
            acquireWakeLock()
            CastEngine.init(this)
            CastEngine.addListener(this)
            // 开机自启时网络可能还没就绪，定时检查并在网络变化时重建 SSDP
            netHandler.postDelayed(netCheck, 4000L)
            registerNetworkCallback()
        } catch (t: Throwable) {
            Log.w(TAG, "service init failed", t)
            try { Trace.error("CastService", t) } catch (_: Throwable) {}
        }
    }

    /** 网络恢复（开机后连上 Wi-Fi / 网线）立刻重建发现服务，避免「软件没开搜不到」 */
    private fun registerNetworkCallback() {
        // registerDefaultNetworkCallback 需要 API 24；老设备（Android 5/6）直接跳过，
        // 否则 NoSuchMethodError 会崩掉整个进程（TV 打开就黑屏的元凶）
        if (android.os.Build.VERSION.SDK_INT < 24) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    netHandler.postDelayed({
                        try {
                            if (httpServer == null) startServers() else checkNetwork()
                        } catch (t: Throwable) {
                            Log.w(TAG, "restart after network available failed", t)
                        }
                    }, 1500L)
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (t: Throwable) {
            Log.w(TAG, "register network callback failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (httpServer == null) {
            startServers()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        netHandler.removeCallbacks(netCheck)
        try {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                    ?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {
        }
        networkCallback = null
        CastEngine.removeListener(this)
        try {
            ssdpServer?.stop()
        } catch (_: Exception) {
        }
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        ssdpServer = null
        httpServer = null
        try {
            discoveryProbe?.stop()
        } catch (_: Exception) {
        }
        discoveryProbe = null
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    private fun startServers() {
        DeviceInfo.ips = NetUtils.allIpv4Addresses()
        DeviceInfo.ip = DeviceInfo.ips.firstOrNull() ?: ""
        Log.i(TAG, "local ips = ${DeviceInfo.ips}")

        val handlers = SoapHandler()
        var http: UpnpHttpServer? = null
        for (port in 49152..49200) {
            val server = UpnpHttpServer(port, handlers)
            if (server.start()) {
                http = server
                break
            }
        }
        if (http == null) {
            val server = UpnpHttpServer(0, handlers)
            if (server.start()) http = server
        }
        if (http == null) {
            Log.e(TAG, "unable to start HTTP server")
            return
        }
        httpServer = http
        DeviceInfo.httpPort = http.listeningPort

        ssdpServer = SsdpServer(
            context = this,
            uuid = DeviceInfo.uuid,
            locationProvider = { DeviceInfo.locationUrl() }
        ).also { it.start() }

        // 诊断用：同时监听 mDNS / 乐联 / 当贝cast 等非 DLNA 发现协议
        try {
            discoveryProbe?.stop()
            discoveryProbe = DiscoveryProbe(this).also { it.start() }
        } catch (t: Throwable) {
            Log.w(TAG, "discovery probe failed", t)
        }

        updateNotification(true)
    }

    /** 网络变化或开机时网络尚未就绪，重建 SSDP 监听。 */
    private fun checkNetwork() {
        val ips = NetUtils.allIpv4Addresses()
        if (ips != DeviceInfo.ips) {
            Log.i(TAG, "network changed ${DeviceInfo.ips} -> $ips")
            DeviceInfo.ips = ips
            DeviceInfo.ip = ips.firstOrNull() ?: ""
            if (httpServer == null) {
                startServers()
            } else {
                startSsdp()
            }
            updateNotification(true)
        } else if (ips.isNotEmpty() && DeviceInfo.ssdpInterfaces == 0) {
            Log.i(TAG, "ssdp not running, restart")
            startSsdp()
        }
    }

    private fun startSsdp() {
        try {
            ssdpServer?.stop()
        } catch (_: Exception) {
        }
        ssdpServer = null
        if (DeviceInfo.ip.isBlank()) return
        ssdpServer = SsdpServer(
            context = this,
            uuid = DeviceInfo.uuid,
            locationProvider = { DeviceInfo.locationUrl() }
        ).also { it.start() }
    }

    private var lastLaunchedUri: String? = null

    override fun onStateChanged(state: CastEngine.State) {
        updateNotification(false)
        // 软件未打开（后台服务在跑）时收到投屏：自动拉起播放界面，
        // 否则只有声音没有画面。用户刚主动返回播放页的 5 秒内不弹回。
        val uri = state.uri
        if (state.hasMedia && !uri.isNullOrBlank() && uri != lastLaunchedUri) {
            lastLaunchedUri = uri
            val recentlyExited = System.currentTimeMillis() - FreeCastApp.playerExitAt < 5000L
            if (!FreeCastApp.mainVisible && !recentlyExited) {
                openPlayer()
            }
        } else if (!state.hasMedia) {
            lastLaunchedUri = null
        }
    }

    private fun openPlayer() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
            Log.i(TAG, "cast arrived, launch player activity")
        } catch (e: Exception) {
            Log.w(TAG, "launch player failed", e)
        }
    }

    private fun notificationText(): String {
        val state = CastEngine.getState()
        val ip = if (DeviceInfo.ip.isBlank()) "未连接网络" else DeviceInfo.ip
        return if (state.hasMedia) {
            getString(R.string.notification_text_cast, state.title ?: ip)
        } else {
            getString(R.string.notification_text, ip)
        }
    }

    private fun updateNotification(force: Boolean) {
        val text = notificationText()
        if (!force && text == lastNotificationText) return
        lastNotificationText = text
        try {
            notificationManager?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "update notification failed", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = getString(R.string.notification_channel_desc)
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "freecast:cast").apply {
                setReferenceCounted(false)
                // 投屏期间保持 CPU 唤醒，24 小时后自动释放，避免长期占用
                acquire(24 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed", e)
        }
        // 保持 Wi-Fi 高性能模式：TV/车机待机时 Wi-Fi 省电会丢弃组播包，
        // 导致手机端搜不到设备
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wifi?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "freecast:wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }
}
