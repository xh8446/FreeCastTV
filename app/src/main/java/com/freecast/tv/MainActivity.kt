package com.freecast.tv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : Activity(), CastEngine.Listener {

    private lateinit var playerView: PlayerView
    private lateinit var infoPanel: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var endpointText: TextView
    private lateinit var speedBadge: TextView
    private var bufferCenter: View? = null
    private var bufferText: TextView? = null
    private var bufferHint: TextView? = null
    private var bufferSpinner: View? = null
    private var watermarkTemplate: TextView? = null
    private val watermarks = mutableListOf<TextView>()

    // 触屏版控制面板：点按画面呼出/隐藏（标题状态 + 倍速 + 进度条）
    private var progressPanel: View? = null
    private var seekBar: android.widget.SeekBar? = null
    private var posText: TextView? = null
    private var durText: TextView? = null
    private var isSeeking = false
    private var overlayVisible = false
    /** 因离开播放画面而暂停，回到画面时自动续播 */
    private var pausedForLeave = false
    private var progressVisible = false
    private var boostHint: TextView? = null
    private val overlayViews = mutableListOf<View>()

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        fadeOverlay(0f)
        fadeProgress(0f)
        tvSel = 0
        applyTvSel()
    }
    private var lastOverlayKey: String? = null
    private var lastBackAt = 0L
    /** 播放中第一次按返回后，到该时间点前再按一次才真正退出 */
    private var backArmUntil = 0L
    private var icp: String = ""
    private var announcement: String = ""
    private var versionName: String = ""
    private var versionCode: Int = 0
    private var countedUri: String? = null
    private var trialTimerRunning = false
    private var castStartedAt = 0L
    private var perCastLimitHit = false
    private var watermarkSeeded = false
    private val rnd = java.util.Random()
    private var waitingSettingsBtn: Button? = null
    private var tvSeekBar: android.widget.SeekBar? = null
    private var tvSpeedDown: View? = null
    private var tvSpeedUp: View? = null
    private var tvSettingsBtn: Button? = null
    private val singleOkRunnable = Runnable {
        // 仅呼出控制条，焦点仍留在画面中央（不默认选中任何按钮）
        tvSel = 0
        showOverlay(true)
        applyTvSel()
    }
    /** 是否 TV/大屏模式（决定倍速徽标显示位置） */
    private val isTvMode: Boolean by lazy {
        try { AppMode.isTv(this) } catch (_: Throwable) { false }
    }

    // ---- TV 自绘控制条与焦点状态机（完全不依赖 Android 焦点系统）----
    // tvSel: 0=无 1=进度条 2=-0.25x 3=+0.25x 4=设置 5=返回
    private var isTvDevice = false
    private var lastHadMedia = false
    private var tvInfoPlaying: Boolean? = null
    private var tvSel = 0
    private var tvBar: LinearLayout? = null
    private var tvBtnSpeedDown: TextView? = null
    private var tvBtnSpeedLabel: TextView? = null
    private var tvBtnSpeedUp: TextView? = null
    private var tvBtnSettings: TextView? = null
    private var infoBackDefaultBg: android.graphics.drawable.Drawable? = null
    private var infoBackDefaultTextColor: Int = 0
    /** 遥控调节音量/倍速时的居中浮层提示 */
    private var tvHint: TextView? = null
    private val tvHintHide = Runnable { tvHint?.visibility = View.GONE }
    private val tvPanelNormal: android.graphics.drawable.Drawable by lazy { tvPanelBg(false) }
    private val tvPanelSelected: android.graphics.drawable.Drawable by lazy { tvPanelBg(true) }
    private val tvBgNormal: android.graphics.drawable.Drawable by lazy { tvBtnBg(false) }
    private val tvBgSelected: android.graphics.drawable.Drawable by lazy { tvBtnBg(true) }

    // 客服消息：登录后轮询云端，收到作者推送的文字消息时弹窗展示并支持回复
    private var chatLastId = 0L
    private var chatPollingStarted = false
    private var chatDialogShowing = false
    private val chatHandler = Handler(Looper.getMainLooper())
    private val chatPollRunnable = object : Runnable {
        override fun run() {
            pollClientMessages()
            chatHandler.postDelayed(this, 20_000L)
        }
    }

    /** 三个试用水印：每 50 毫秒随机漂移一步，撞到边缘反弹 */
    private val watermarkVec = mutableMapOf<TextView, FloatArray>()

    private val watermarkMover = object : Runnable {
        override fun run() {
            for (wm in watermarks) {
                if (wm.visibility != View.VISIBLE || wm.width <= 0) continue
                val parent = wm.parent as? View ?: continue
                val maxX = (parent.width - wm.width).coerceAtLeast(1).toFloat()
                val maxY = (parent.height - wm.height).coerceAtLeast(1).toFloat()

                if (!watermarkSeeded) {
                    wm.translationX = rnd.nextFloat() * maxX
                    wm.translationY = rnd.nextFloat() * maxY
                    watermarkVec[wm] = randomVector()
                    continue
                }

                val vec = watermarkVec.getOrPut(wm) { randomVector() }
                var x = wm.translationX + vec[0]
                var y = wm.translationY + vec[1]
                if (x <= 0f || x >= maxX) {
                    vec[0] = -vec[0]
                    x = x.coerceIn(0f, maxX)
                }
                if (y <= 0f || y >= maxY) {
                    vec[1] = -vec[1]
                    y = y.coerceIn(0f, maxY)
                }
                wm.translationX = x
                wm.translationY = y
            }
            watermarkSeeded = true
            hideHandler.postDelayed(this, 20L)
        }
    }

    private fun randomVector(): FloatArray {
        // 每 50ms 移动约 2dp，方向随机
        val speed = resources.displayMetrics.density * 2f
        val angle = rnd.nextDouble() * Math.PI * 2
        return floatArrayOf(
            (speed * Math.cos(angle)).toFloat(),
            (speed * Math.sin(angle)).toFloat()
        )
    }

    /** 试用期每 30 秒向云端上报一次播放时长（服务端累计，防改时间） */
    private val trialTick = object : Runnable {
        override fun run() {
            if (!trialTimerRunning) return
            val s = CastEngine.getState()
            if (s.hasMedia && s.transportState == "PLAYING" &&
                !LicenseManager.status.memberActive &&
                !LicenseManager.status.trialFree
            ) {
                LicenseManager.reportTime(30)
                if (LicenseManager.status.secondsLeft <= 0) {
                    onTrialExhausted()
                    return
                }
                // 单次投屏试用上限 20 分钟（测试阶段不限）
                if (BuildFlags.PAYMENT_ENABLED && castStartedAt > 0L) {
                    val played = (android.os.SystemClock.elapsedRealtime() - castStartedAt) / 1000
                    if (played >= LicenseManager.PER_CAST_SECONDS && !perCastLimitHit) {
                        perCastLimitHit = true
                        CastEngine.pause()
                        toast(R.string.trial_per_cast_limited)
                    }
                }
            }
            hideHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Trace.log("MainActivity", "onCreate enter")
        // 任何一步出问题都不要静默闪退，而是让用户看到错误信息
        try {
            doOnCreate(savedInstanceState)
        } catch (e: Throwable) {
            Trace.error("MainActivity", e)
            android.util.Log.e("MainActivity", "onCreate crashed", e)
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            showStartupError(sw.toString())
        }
    }

    private fun doOnCreate(savedInstanceState: Bundle?) {
        Trace.log("MainActivity", "doOnCreate start, calling super.onCreate")
        super.onCreate(savedInstanceState)
        Trace.log("MainActivity", "super.onCreate returned")

        // 未开通会员 / 试用中：每次冷启动都先显示注册登录页（会员直接进投屏页）
        if (FreeCastApp.needLoginOnLaunch(this)) {
            Trace.log("MainActivity", "needLoginOnLaunch=true → redirect to LoginActivity")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        Trace.log("MainActivity", "hasEntered=true, continuing")
        try { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
        try { enterImmersiveMode() } catch (e: Throwable) { Trace.error("MainActivity", e) }

        Trace.log("MainActivity", "about to setContentView")
        setContentView(R.layout.activity_main)
        AppMode.applyStatusBarTopPadding(this)
        Trace.log("MainActivity", "setViewContent OK")

        // 每个 view 都用 findViewByIdOrNull，缺失就跳过不崩
        playerView = findViewById(R.id.player_view)
        // 播放器不参与焦点竞争，避免遥控器按键被它吞掉
        playerView.isFocusable = false
        playerView.isFocusableInTouchMode = false
        playerView.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        infoPanel = findViewById(R.id.info_panel)
        titleText = findViewById(R.id.title_text)
        statusText = findViewById(R.id.status_text)
        hintText = findViewById(R.id.hint_text)
        endpointText = findViewById(R.id.endpoint_text)
        speedBadge = findViewById(R.id.speed_badge)

        // buffer_center / buffer_text / watermark 三个新 view 触屏版和 TV 版都已加入布局
        try { bufferCenter = findViewById(R.id.buffer_center) } catch (_: Throwable) {}
        try { bufferText = findViewById(R.id.buffer_text) } catch (_: Throwable) {}
        try { bufferHint = findViewById(R.id.buffer_hint) } catch (_: Throwable) {}
        try { bufferSpinner = findViewById(R.id.buffer_spinner) } catch (_: Throwable) {}
        try { watermarkTemplate = findViewById(R.id.watermark) } catch (_: Throwable) {}
        stylePauseOverlay()

        // 整屏点按：单击呼出底部进度条，双击（或 TV 确认键）暂停并呼出完整控制面板，均 2 秒后隐藏
        try {
            overlayViews.add(infoPanel)
            val progressId = resources.getIdentifier("progress_panel", "id", packageName)
            if (progressId != 0) {
                val panel = findViewById<View>(progressId)
                progressPanel = panel
                panel.visibility = View.GONE
                seekBar = findViewById(resources.getIdentifier("seek_bar", "id", packageName))
                posText = findViewById(resources.getIdentifier("pos_text", "id", packageName))
                durText = findViewById(resources.getIdentifier("dur_text", "id", packageName))

                seekBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) posText?.text = formatHms(progress * 1000L)
                    }

                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {
                        isSeeking = true
                        cancelHide()
                    }

                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                        isSeeking = false
                        try { CastEngine.seekTo((sb?.progress ?: 0) * 1000L) } catch (_: Exception) {}
                        showOverlay(true)
                    }
                })
            }
            val tapId = resources.getIdentifier("tap_catcher", "id", packageName)
            if (tapId != 0) {
                val tapHintId = resources.getIdentifier("boost_hint", "id", packageName)
                if (tapHintId != 0) boostHint = findViewById(tapHintId)
                setupTouchGestures(findViewById(tapId))
            }
        } catch (e: Throwable) {
            Trace.error("MainActivity", e)
        }

        // 用布局里的水印作模板，复制出 3 个更大的水印，全屏随机漂移
        watermarkTemplate?.let { template ->
            try {
                val parent = template.parent as? android.view.ViewGroup
                template.visibility = View.GONE
                if (parent != null) {
                    val text = template.text
                    val color = template.currentTextColor
                    repeat(5) {
                        val wm = TextView(this).apply {
                            this.text = text
                            setTextColor(color)
                            alpha = 0.45f
                            textSize = 60f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            rotation = -15f
                            visibility = View.GONE
                            isClickable = false
                            isFocusable = false
                            isLongClickable = false
                            // 不拦截触摸事件，保证画面/控制条可正常点击
                            setOnTouchListener { _, _ -> false }
                        }
                        parent.addView(
                            wm,
                            android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                        watermarks.add(wm)
                    }
                }
                hideHandler.removeCallbacks(watermarkMover)
                hideHandler.postDelayed(watermarkMover, 1200L)
            } catch (e: Throwable) {
                Trace.error("MainActivity", e)
            }
        }

        Trace.log("MainActivity", "all views found OK")

        findViewById<View>(R.id.speed_down)?.setOnClickListener {
            try { CastEngine.adjustSpeed(-0.25f); showOverlay(true) } catch (_: Exception) {}
        }
        findViewById<View>(R.id.speed_up)?.setOnClickListener {
            try { CastEngine.adjustSpeed(0.25f); showOverlay(true) } catch (_: Exception) {}
        }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) showOverlay(true)
        })

        Trace.log("MainActivity", "about to CastEngine.init")
        try { CastEngine.init(applicationContext) } catch (e: Throwable) { Trace.error("MainActivity", e) }
        Trace.log("MainActivity", "CastEngine.init OK, attachView")
        try { CastEngine.attachView(playerView) } catch (e: Throwable) { Trace.error("MainActivity", e) }
        Trace.log("MainActivity", "attachView OK")

        try { requestNotificationPermission() } catch (_: Exception) {}
        try { CastService.start(this); Trace.log("MainActivity", "CastService.start OK") }
        catch (e: Throwable) { Trace.error("MainActivity", e) }

        try { showOverlay(false) } catch (_: Exception) {}

        // 融合布局：运行时根据设备能力调整 UI
        val isTv = AppMode.isTv(this)
        isTvDevice = isTv
        // TV：构建自绘控制条（等待界面显示「设置」，播放时显示倍速/设置）
        if (isTv) buildTvBar()
        val tvDm = resources.displayMetrics
        val tvDensity = tvDm.density
        val infoPanel = findViewById<LinearLayout>(R.id.info_panel)
        val touchSpeedRow = findViewById<LinearLayout>(R.id.touch_speed_row)
        val speedBadgeTv = findViewById<TextView>(R.id.speed_badge_tv)
        val settingsBtn = findViewById<Button>(R.id.settings_button)
        val waitingSettingsBtnRef = findViewById<Button>(R.id.waiting_settings_btn)
        waitingSettingsBtn = waitingSettingsBtnRef

        // 焦点导航引用的控件（TV 与触屏统一，避免 TV 检测误判导致遥控失效）
        tvSeekBar = seekBar
        tvSpeedDown = findViewById(R.id.speed_down)
        tvSpeedUp = findViewById(R.id.speed_up)
        tvSettingsBtn = findViewById(R.id.settings_button)
        seekBar?.isFocusable = true
        seekBar?.isFocusableInTouchMode = true

        if (isTv) {
            // TV 模式：自绘 tvBar 接管倍速/设置入口；XML 控件全部隐藏避免重叠
            touchSpeedRow?.visibility = View.GONE
            speedBadgeTv?.visibility = View.GONE
            findViewById<TextView>(R.id.speed_badge)?.visibility = View.GONE
            waitingSettingsBtnRef?.visibility = View.GONE
            settingsBtn?.visibility = View.GONE
            // 顶栏：返回 / 标题 / 倍速- / 倍速+ / 设置 同一水平线，统一边框与字号
            configureTvInfoPanel(false)
            // TV 完全用自绘高亮，禁止系统焦点进入顶栏/进度条，避免出现「假焦点空一步」
            infoPanel?.isFocusable = false
            infoPanel?.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            currentFocus?.clearFocus()
            val pp = progressPanel?.layoutParams as? FrameLayout.LayoutParams
            // 恢复贴近两侧的全宽进度条，不再限制成短条
            pp?.gravity = Gravity.BOTTOM
            pp?.width = FrameLayout.LayoutParams.MATCH_PARENT
            pp?.setMargins(
                (20 * tvDensity).toInt(), 0,
                (20 * tvDensity).toInt(), (72 * tvDensity).toInt()
            )
            progressPanel?.layoutParams = pp
            // TV 不使用系统焦点，进度条高亮由自绘状态机控制
            seekBar?.isFocusable = false
            seekBar?.isFocusableInTouchMode = false
        } else {
            // 触屏模式：保持上一版布局，返回在上、标题在下
            speedBadgeTv?.visibility = View.GONE
            findViewById<LinearLayout>(R.id.tv_top_row)?.orientation = LinearLayout.VERTICAL
            findViewById<TextView>(R.id.title_text)?.let { t ->
                (t.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                    lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    lp.weight = 0f
                    lp.setMargins(0, (8 * tvDensity).toInt(), 0, 0)
                    t.layoutParams = lp
                }
                t.setPadding(0, 0, 0, 0)
            }
            // 等待投屏时的设置按钮对所有设备都显示，保证遥控器一定能把焦点移过去
            val tapCatcher = findViewById<View>(R.id.tap_catcher)
            if (tapCatcher != null && boostHint == null) {
                boostHint = findViewById(R.id.boost_hint)
                setupTouchGestures(tapCatcher)
            }
        }

        // 融合布局里始终有 settings_button
        settingsBtn?.setOnClickListener { try { showSettings() } catch (_: Exception) {} }
        waitingSettingsBtnRef?.setOnClickListener { try { showSettings() } catch (_: Exception) {} }
        // 标题栏左上角返回：结束投屏页回到注册登录/首页
        try {
            findViewById<Button>(R.id.info_back).setOnClickListener { handleBack() }
        } catch (_: Throwable) {}

        try { loadVersionInfo() } catch (_: Exception) {}
        try { loadConfig() } catch (_: Exception) {}
        try { checkUpdate(false) } catch (_: Exception) {}
        Trace.log("MainActivity", "about to LicenseManager.refresh")
        try { LicenseManager.refresh { render(CastEngine.getState()) } } catch (_: Exception) {}
        try { migrateLegacyLicense() } catch (_: Exception) {}

        // 上次启动有崩溃，弹一次提示
        FreeCastApp.readCrashLog(this)?.let { stack ->
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("上次启动异常")
                .setMessage("App 上次启动时遇到了问题，已自动恢复。\n\n$stack")
                .setPositiveButton("知道了", null)
                .show()
        }
        Trace.log("MainActivity", "doOnCreate fully done OK")
    }

    private fun showDebugLogDialog() {
        val trace = Trace.readAll(this) ?: "(无 trace 日志)"
        val crash = FreeCastApp.readCrashLog(this) ?: "(无 crash 日志)"
        val msg = "=== trace.log ===\n$trace\n\n=== crash.log ===\n$crash\n\n【请把以上内容复制发给作者 QQ：84461502】"
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("调试日志")
            .setMessage(msg.take(6000))
            .setPositiveButton("复制到剪贴板") { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("FreeCast Log", msg))
                    toast("日志已复制")
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("清空", null)
            .show()
    }

    private fun showStartupError(stack: String) {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("启动异常")
                .setMessage("App 启动时遇到了问题，请截图发给作者 QQ：84461502\n\n${stack.take(1500)}")
                .setPositiveButton("关闭") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } catch (_: Exception) {
            finish()
        }
    }

    /** 旧版本已激活的设备：云端无许可记录，用本地保存的密钥补登记（绑定本机） */
    private fun migrateLegacyLicense() {
        // 调试期所有版本统一按未激活试用处理，不做旧许可补登记
        if (!BuildFlags.PAYMENT_ENABLED) return
        val prefs = getSharedPreferences("freecast", Context.MODE_PRIVATE)
        val legacyKey = prefs.getStringSet("used_keys", emptySet())?.firstOrNull() ?: return
        LicenseManager.refresh {
            if (!LicenseManager.status.memberActive) {
                CloudApi.post(
                    JSONObject()
                        .put("key", legacyKey)
                        .put("deviceId", DeviceInfo.uuid)
                        .put("rebind", true)
                ) { json ->
                    if (json?.optBoolean("ok") == true) {
                        LicenseManager.refresh { render(CastEngine.getState()) }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        FreeCastApp.mainVisible = true
        CastEngine.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        // 只有视图尚未绑定播放器时才重新绑定，避免每次返回都重建 Surface 造成几秒卡顿
        if (::playerView.isInitialized && playerView.player == null) CastEngine.attachView(playerView)
        // 从会员/反馈等页面切回播放画面：恢复音量并继续播放
        if (pausedForLeave) {
            pausedForLeave = false
            try { CastEngine.resumeFromLeave() } catch (_: Throwable) {}
        }
        startChatPolling()
        ensureOverlayPermissionOnce()
        // 车机/触屏：返回后重新进入全屏，隐藏底部导航栏
        try { enterImmersiveMode() } catch (_: Throwable) {}
        hideHandler.postDelayed({ try { enterImmersiveMode() } catch (_: Throwable) {} }, 800L)
    }

    override fun onPause() {
        super.onPause()
        // 进入会员 / 帮助与反馈 / 切其他 App 都立即暂停并静音；
        // 这些页面是半透明窗口，播放页不会被销毁，返回时直接在原画面上继续播放
        if (!isFinishing) {
            try {
                if (CastEngine.isPlayingNow()) {
                    CastEngine.pauseAndMute()
                    pausedForLeave = true
                }
            } catch (_: Throwable) {}
        }
    }

    /** Android 10+ 后台拉不起 Activity：引导用户开启「显示在其他应用上层」，
     *  这样未打开软件时收到投屏也能自动弹出播放画面。只提示一次。 */
    private fun ensureOverlayPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (android.provider.Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences("freecast", MODE_PRIVATE)
        if (prefs.getBoolean("overlay_asked", false)) return
        prefs.edit().putBoolean("overlay_asked", true).apply()
        try {
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("开启后台自动跳转")
                .setMessage("开启「显示在其他应用上层」后，即使星投屏没打开，手机投屏时也会自动弹出播放画面（现在只有声音）。")
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {}
                }
                .setNegativeButton("以后再说", null)
                .show()
        } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 部分车机获得焦点后会重新显示导航栏，这里再隐藏一次
        if (hasFocus) {
            try { enterImmersiveMode() } catch (_: Throwable) {}
            hideHandler.postDelayed({ try { enterImmersiveMode() } catch (_: Throwable) {} }, 300L)
        }
    }

    override fun onStop() {
        FreeCastApp.mainVisible = false
        stopChatPolling()
        CastEngine.removeListener(this)
        // 不再因切后台/锁屏暂停：TV 自己拉流解码，手机端退出、切后台、锁屏都不影响接收端播放
        super.onStop()
    }

    override fun onDestroy() {
        trialTimerRunning = false
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.removeCallbacks(trialTick)
        hideHandler.removeCallbacks(watermarkMover)
        chatHandler.removeCallbacks(chatPollRunnable)
        CastEngine.detachView()
        super.onDestroy()
    }

    override fun onStateChanged(state: CastEngine.State) {
        render(state)
    }

    /** 暂停/缓冲居中卡片美化：大号带阴影的「已暂停」+ 次级小字提示 */
    private fun stylePauseOverlay() {
        val center = bufferCenter as? LinearLayout ?: return
        center.setBackgroundResource(R.drawable.bg_pause_card)
        center.setPadding(dp(56), dp(34), dp(56), dp(32))
        center.elevation = dp(12).toFloat()
        bufferText?.apply {
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 3f, 0xE6000000.toInt())
        }
        bufferHint?.apply {
            textSize = 17f
            setTextColor(0xF2FFE9C7.toInt())
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
            setLineSpacing(dp(5).toFloat(), 1f)
            (layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(18)
                layoutParams = it
            }
        }
        bufferSpinner?.let { spinner ->
            (spinner.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.width = dp(52)
                it.height = dp(52)
                spinner.layoutParams = it
            }
        }
    }

    /** TV：播放时顶栏只保留「返回 / 标题 / 倍速- / 倍速+ / 设置」，等待时恢复完整设备提示。 */
    private fun configureTvInfoPanel(playing: Boolean) {
        if (!isTvDevice) return
        val density = resources.displayMetrics.density
        infoPanel.orientation = LinearLayout.VERTICAL
        infoPanel.gravity = Gravity.START
        infoPanel.setBackgroundResource(R.drawable.bg_panel)
        infoPanel.setPadding(
            (14 * density).toInt(), (12 * density).toInt(),
            (14 * density).toInt(), (12 * density).toInt()
        )
        (infoPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.TOP
            lp.setMargins((20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt(), 0)
            infoPanel.layoutParams = lp
        }

        titleText.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = 0
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 1f
                lp.setMargins(0, 0, 0, 0)
                layoutParams = lp
            }
            maxLines = if (playing) 1 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = if (playing) 20f else 18f
            setPadding(
                (12 * density).toInt(), 0, (12 * density).toInt(), 0
            )
        }
        val extraVisibility = if (playing) View.GONE else View.VISIBLE
        statusText.visibility = extraVisibility
        hintText.visibility = extraVisibility
        endpointText.visibility = extraVisibility
    }

    private fun render(state: CastEngine.State) {
        // 等待/播放切换时重置 TV 焦点，避免上一个界面的选中项带过来
        if (state.hasMedia != lastHadMedia) {
            lastHadMedia = state.hasMedia
            tvSel = 0
        }
        if (isTvDevice && tvInfoPlaying != state.hasMedia) {
            tvInfoPlaying = state.hasMedia
            configureTvInfoPanel(state.hasMedia)
        }
        speedBadge.text = formatSpeed(state.speed)
        // 等待投屏时也显示当前倍速，让「−」「+」之间始终有数值可看
        speedBadge.visibility = if (isTvMode) View.GONE else View.VISIBLE

        // 等待页下方不再放第二个「设置」；设置统一在 info_panel（触屏）或右上控制条（TV）
        waitingSettingsBtn?.visibility = View.GONE
        // 刷新自绘控制条（等待/播放模式切换 + 倍速标签）
        if (isTvDevice) applyTvSel()

        // TV 模式：XML 倍速/设置按钮停用（由自绘 tvBar 接管）
        if (isTvMode) {
            tvSpeedDown?.visibility = View.GONE
            tvSpeedUp?.visibility = View.GONE
            tvSettingsBtn?.visibility = View.GONE
        }

        // 底部进度条由点击画面 / OK 键呼出，2 秒后自动隐藏（不再常显）

        // 水印：未开通会员且不在 5 天免费期内时显示（测试阶段一律不显示）
        val showWatermark = BuildFlags.PAYMENT_ENABLED &&
            LicenseManager.status.loaded &&
            !LicenseManager.status.memberActive &&
            !LicenseManager.status.trialFree
        watermarks.forEach { it.visibility = if (showWatermark) View.VISIBLE else View.GONE }

        // 缓冲/暂停：居中面板显示状态，并换行提示保持投屏 APP 前台
        val buffering = state.hasMedia && state.transportState == "TRANSITIONING"
        val isPaused = state.hasMedia && state.transportState == "PAUSED_PLAYBACK"
        if (buffering || isPaused) {
            bufferCenter?.visibility = View.VISIBLE
            bufferSpinner?.visibility = if (buffering) View.VISIBLE else View.GONE
            bufferHint?.visibility = View.VISIBLE
            bufferText?.text = if (buffering) {
                if (state.bufferPercent in 0..100) {
                    getString(R.string.status_buffered_percent, state.bufferPercent)
                } else {
                    getString(R.string.status_buffered_unknown)
                }
            } else {
                getString(R.string.status_paused)
            }
        } else {
            bufferCenter?.visibility = View.GONE
        }

        // 底部进度条（触屏版）
        seekBar?.let { sb ->
            val durSec = (state.durationMs / 1000).toInt()
            if (durSec > 0) {
                if (sb.max != durSec) sb.max = durSec
                if (!isSeeking) sb.progress = (state.positionMs / 1000).toInt().coerceIn(0, durSec)
            }
            if (!isSeeking) posText?.text = formatHms(state.positionMs)
            durText?.text = if (durSec > 0) formatHms(state.durationMs) else "--:--"
        }

        val ipText = if (DeviceInfo.ips.isEmpty()) {
            "未连接网络"
        } else {
            val ssdp = if (DeviceInfo.ssdpInterfaces > 0) "（SSDP：${DeviceInfo.ssdpInterfaces} 网卡）" else ""
            DeviceInfo.ips.joinToString(" / ") + ssdp
        }
        endpointText.text = "设备：${DeviceInfo.friendlyName}　$ipText"

        if (!state.hasMedia) {
            countedUri = null
            // 打开界面突出软件名称；投屏界面不显示试用说明
            titleText.text = getString(R.string.app_name)
            statusText.text = getString(R.string.waiting_title)
            hintText.text = if (AppMode.isTv(this)) {
                getString(R.string.waiting_hint) + "\n\n" + getString(R.string.waiting_menu_hint)
            } else {
                getString(R.string.waiting_hint)
            }
            // TV 等待页用右上角自绘「设置」，隐藏 XML 旧按钮避免两个设置重叠；触屏保持原样
            if (isTvDevice) findViewById<Button>(R.id.waiting_settings_btn)?.visibility = View.GONE
            showOverlay(false)
            cancelHide()
            progressVisible = false
            progressPanel?.visibility = View.GONE
            lastOverlayKey = state.transportState
            return
        }

        // 播放界面隐藏等待页的设置按钮，避免遮挡画面
        findViewById<Button>(R.id.waiting_settings_btn)?.visibility = View.GONE

        // 许可门控：每次新的投屏开始播放时校验
        if (state.transportState == "PLAYING" && state.uri != countedUri) {
            countedUri = state.uri
            castStartedAt = android.os.SystemClock.elapsedRealtime()
            perCastLimitHit = false
            val lic = LicenseManager.status
            if (!lic.memberActive && lic.loaded && !lic.trialFree &&
                (lic.castsLeft <= 0 || lic.secondsLeft <= 0)
            ) {
                onTrialExhausted()
                return
            }
            LicenseManager.onCastStarted({ onTrialExhausted() }, { showDailyLimit() })
            startTrialTimer()
        }

        // 单次 20 分钟上限已被触发时，阻止再次播放
        if (perCastLimitHit && state.transportState == "PLAYING") {
            CastEngine.pause()
        }

        val title = state.title?.takeIf { it.isNotBlank() } ?: fileNameFromUri(state.uri)
        titleText.text = title

        val status = when (state.transportState) {
            "PLAYING" -> getString(R.string.status_playing)
            "PAUSED_PLAYBACK" -> getString(R.string.status_paused)
            "STOPPED" -> getString(R.string.status_stopped)
            "TRANSITIONING" -> getString(R.string.status_buffering)
            else -> getString(R.string.status_idle)
        }
        val time = if (state.durationMs > 0) {
            "${formatHms(state.positionMs)} / ${formatHms(state.durationMs)}"
        } else {
            formatHms(state.positionMs)
        }
        statusText.text = when {
            state.error != null -> "播放失败（${state.error}）"
            state.transportState == "TRANSITIONING" ->
                if (state.durationMs > 0) {
                    getString(R.string.status_buffering_percent, bufferedPercent(state))
                } else {
                    getString(R.string.status_buffering)
                }
            else -> "$status · $time"
        }
        hintText.text = getString(R.string.hint_keys)

        // 缓冲/播放之间来回跳变时不重新弹出面板，否则定时器会被反复重置
        val stableState = if (state.transportState == "TRANSITIONING") "PLAYING" else state.transportState
        val overlayKey = stableState + "|" + state.error
        if (overlayKey != lastOverlayKey) {
            // 暂停/播放状态变化时呼出控制面板，2 秒后自动隐藏
            showOverlay(true)
        }
        lastOverlayKey = overlayKey
    }

    /**
     * 缓冲进度：已预加载到播放缓冲区的数据占总需要缓冲数据的比例（0-100）。
     * 对应 onBufferingUpdate 的 percent 语义，100% 即缓冲完成、开始播放。
     */
    private fun bufferedPercent(state: CastEngine.State): Int {
        if (state.durationMs <= 0L) return 0
        return (state.bufferedPositionMs * 100 / state.durationMs).toInt().coerceIn(0, 100)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // TV 设备：全量拦截按键，走自绘状态机（不依赖 Android 焦点系统，
        // 避免不同电视盒子上焦点行为不一致导致遥控失灵）
        if (isTvDevice) {
            val keyCode = event.keyCode
            val action = event.action
            val down = action == KeyEvent.ACTION_DOWN
            val hasMedia = try { CastEngine.getState().hasMedia } catch (_: Throwable) { false }
            val okKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A
            val dpadKey = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

            // 音量键：TV 上由应用直接调媒体音量，避免遥控音量键无反应
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            ) {
                if (down) handleVolumeKey(keyCode)
                return true
            }

            // ---- 等待投屏：左键选「返回」，其余方向键选「设置」----
            if (!hasMedia) {
                when {
                    keyCode == KeyEvent.KEYCODE_MENU ||
                        keyCode == KeyEvent.KEYCODE_INFO ||
                        keyCode == KeyEvent.KEYCODE_SETTINGS -> if (down) showSettings()

                    // 返回：等待页一次返回；播放中由 requestBack 判断是否需要连按两次
                    keyCode == KeyEvent.KEYCODE_BACK -> if (down) requestBack()

                    okKey -> {
                        if (down) {
                            if (tvSel == 5) {
                                handleBack()
                                return true
                            }
                            tvSel = 4
                            applyTvSel()
                        } else if (action == KeyEvent.ACTION_UP && tvSel == 4) {
                            showSettings()
                        }
                    }

                    dpadKey -> if (down) {
                        tvSel = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) 5 else 4
                        applyTvSel()
                    }
                }
                return true
            }

            // ---- 播放中：状态机导航 ----
            if (keyCode == KeyEvent.KEYCODE_MENU ||
                keyCode == KeyEvent.KEYCODE_INFO ||
                keyCode == KeyEvent.KEYCODE_SETTINGS
            ) {
                if (down) showSettings()
                return true
            }
            // 返回键：等待投屏时一次返回；播放中需连按两次，第一次只弹出提示，避免误触退出
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (down) requestBack()
                return true
            }
            if (okKey) {
                if (down) {
                    if (event.repeatCount > 0) return true
                    handleTvOk()
                }
                return true
            }
            if (dpadKey && down) handleTvDpad(keyCode)
            return true
        }

        // 触屏设备：保持原有手势逻辑
        return super.dispatchKeyEvent(event)
    }

    /** TV 状态机：OK 键（画面空白处单按即暂停/继续） */
    private fun handleTvOk() {
        if (tvSel == 0) {
            // 单按确认键：直接暂停/继续，不需要连按
            hideHandler.removeCallbacks(singleOkRunnable)
            try { CastEngine.togglePlayPause() } catch (_: Exception) {}
            showOverlay(true)
            applyTvSel()
            return
        }
        hideHandler.removeCallbacks(singleOkRunnable)
        when (tvSel) {
            1 -> try { CastEngine.togglePlayPause() } catch (_: Exception) {}
            2 -> {
                try { CastEngine.adjustSpeed(-0.25f) } catch (_: Exception) {}
                showTvHint("倍速 ${formatSpeed(CastEngine.getState().speed)}")
            }
            3 -> {
                try { CastEngine.adjustSpeed(0.25f) } catch (_: Exception) {}
                showTvHint("倍速 ${formatSpeed(CastEngine.getState().speed)}")
            }
            4 -> { showSettings(); return }
            5 -> { handleBack(); return }
        }
        showOverlay(true)
        applyTvSel()
    }

    /**
     * TV 状态机方向键：
     * 顶行顺序为 返回 ← 倍速- ← 倍速+ ← 设置（左右可循环到底）；
     * 顶行任意按键按下键直接到进度条；进度条按上键直接到倍速+；
     * 进度条聚焦时左右仍为快退/快进。
     */
    private fun handleTvDpad(keyCode: Int) {
        if (tvSel == 1) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    try { CastEngine.seekBy(-10_000L) } catch (_: Exception) {}
                    showOverlay(true)
                    return
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    try { CastEngine.seekBy(10_000L) } catch (_: Exception) {}
                    showOverlay(true)
                    return
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    tvSel = 3
                    applyTvSel()
                    showOverlay(true)
                    return
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> return
            }
        }
        tvSel = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 3          // 任意位置按上：直接到倍速+
            KeyEvent.KEYCODE_DPAD_DOWN -> 1        // 任意位置按下：直接到进度条
            KeyEvent.KEYCODE_DPAD_LEFT -> when (tvSel) {
                4 -> 3                             // 设置 → 倍速+
                3 -> 2                             // 倍速+ → 倍速-
                2 -> 5                             // 倍速- → 返回
                else -> 5                          // 返回（或空白）→ 返回
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (tvSel) {
                5 -> 2                             // 返回 → 倍速-
                2 -> 3                             // 倍速- → 倍速+
                3 -> 4                             // 倍速+ → 设置
                else -> 4                          // 设置（或空白）→ 设置
            }
            else -> tvSel
        }
        applyTvSel()
        showOverlay(true)
    }

    /** TV 控制条按钮背景：普通=半透明黑底，选中=橙色高亮 */
    private fun tvBtnBg(selected: Boolean): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        d.cornerRadius = 12f * resources.displayMetrics.density
        if (selected) {
            d.setColor(0xFFE0821A.toInt())
            d.setStroke((3 * resources.displayMetrics.density).toInt(), 0xFFFFC46B.toInt())
        } else {
            d.setColor(0xB3000000.toInt())
            d.setStroke((2 * resources.displayMetrics.density).toInt(), 0x66FFFFFF.toInt())
        }
        return d
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 进度条面板背景：普通为深色细边，选中与顶部按键一致（整块黄底 + 亮边） */
    private fun tvPanelBg(selected: Boolean): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        d.cornerRadius = 12f * resources.displayMetrics.density
        if (selected) {
            d.setColor(0xFFE0821A.toInt())
            d.setStroke((3 * resources.displayMetrics.density).toInt(), 0xFFFFC46B.toInt())
        } else {
            d.setColor(0xCC0E1624.toInt())
            d.setStroke((1 * resources.displayMetrics.density).toInt(), 0x4DFFFFFF)
        }
        return d
    }

    /** 居中浮层提示（音量 / 倍速 / 返回），默认 1.4 秒后自动消失，不占控制条位置 */
    private fun showTvHint(text: String, durationMs: Long = 1400L) {
        val hint = tvHint ?: TextView(this).apply {
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = tvBtnBg(false)
            setPadding(dp(32), dp(16), dp(32), dp(16))
            visibility = View.GONE
            addContentView(
                this,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        tvHint = hint
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.alpha = 1f
        hideHandler.removeCallbacks(tvHintHide)
        hideHandler.postDelayed(tvHintHide, durationMs)
    }

    /** TV 遥控音量键：直接调媒体音量并显示百分比 */
    private fun handleVolumeKey(keyCode: Int) {
        val am = try {
            getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        } catch (_: Throwable) { null } ?: return
        try {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP ->
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0)
                KeyEvent.KEYCODE_VOLUME_DOWN ->
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0)
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            if (am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)) android.media.AudioManager.ADJUST_UNMUTE
                            else android.media.AudioManager.ADJUST_MUTE, 0)
                    }
                }
            }
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val muted = am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
            showTvHint(if (muted) "静音" else "音量 ${(cur * 100 / max).coerceIn(0, 100)}%")
        } catch (_: Throwable) {}
    }

    /** 动态创建 TV 控制条：与「返回 / 标题」同一行，播放时依次为 倍速- 倍速+ 设置 */
    private fun buildTvBar() {
        val topRow = findViewById<LinearLayout>(R.id.tv_top_row)
        val density = resources.displayMetrics.density

        // 返回按钮与倍速/设置使用完全一致的边框、字号、内边距
        val back = findViewById<Button>(R.id.info_back)
        back?.apply {
            textSize = 16f
            minWidth = (96 * density).toInt()
            minimumWidth = (96 * density).toInt()
            minHeight = 0
            minimumHeight = 0
            height = (46 * density).toInt()
            gravity = Gravity.CENTER
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            background = tvBgNormal
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
        }

        fun mkBtn(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            minWidth = (96 * density).toInt()
            minimumWidth = (96 * density).toInt()
            height = (46 * density).toInt()
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            isSingleLine = true
            background = tvBgNormal
        }

        tvBtnSpeedDown = mkBtn("倍速 −").also {
            it.setOnClickListener { _ ->
                try { CastEngine.adjustSpeed(-0.25f) } catch (_: Exception) {}
                showTvHint("倍速 ${formatSpeed(CastEngine.getState().speed)}")
                showOverlay(true); applyTvSel()
            }
        }

        tvBtnSpeedUp = mkBtn("倍速 +").also {
            it.setOnClickListener { _ ->
                try { CastEngine.adjustSpeed(0.25f) } catch (_: Exception) {}
                showTvHint("倍速 ${formatSpeed(CastEngine.getState().speed)}")
                showOverlay(true); applyTvSel()
            }
        }

        tvBtnSettings = mkBtn(getString(R.string.settings_title)).also {
            it.setOnClickListener { showSettings() }
        }
        tvBtnSpeedLabel = null

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun spacing() = bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((10 * density).toInt(), 1)
        })
        val btnH = (46 * density).toInt()
        back?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, btnH
        ).apply { setMargins(0, 0, (14 * density).toInt(), 0) }
        bar.addView(tvBtnSpeedDown, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, btnH
        ))
        spacing()
        bar.addView(tvBtnSpeedUp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, btnH
        ))
        spacing()
        bar.addView(tvBtnSettings, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, btnH
        ))

        tvBar = bar
        // 追加在标题之后：返回、标题、倍速-、倍速+、设置 处于同一水平线
        topRow?.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (!overlayViews.contains(bar)) overlayViews.add(bar)
        applyTvSel()
    }

    /** 刷新 TV 控制条高亮与可见性（等待界面只显示「设置」） */
    private fun applyTvSel() {
        val hasMedia = try { CastEngine.getState().hasMedia } catch (_: Throwable) { false }
        if (tvBar == null) return

        // 等待界面：隐藏倍速按钮，只显示设置
        tvBtnSpeedDown?.visibility = if (hasMedia) View.VISIBLE else View.GONE
        tvBtnSpeedUp?.visibility = if (hasMedia) View.VISIBLE else View.GONE

        fun setSel(btn: TextView?, sel: Int) {
            btn ?: return
            val on = tvSel == sel && (hasMedia || sel == 4)
            btn.background = if (on) tvBgSelected else tvBgNormal
            btn.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        setSel(tvBtnSpeedDown, 2)
        setSel(tvBtnSpeedUp, 3)
        setSel(tvBtnSettings, 4)

        // 返回按钮高亮：tvSel==5 表示遥控焦点在「返回」上
        findViewById<Button>(R.id.info_back)?.let { back ->
            if (infoBackDefaultBg == null) {
                infoBackDefaultBg = back.background
                infoBackDefaultTextColor = back.currentTextColor
            }
            val on = tvSel == 5
            back.background = if (on) tvBgSelected else infoBackDefaultBg
            back.setTextColor(if (on) 0xFFFFFFFF.toInt() else infoBackDefaultTextColor)
            back.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }

        // 进度条：选中时整块黄底（与顶部按键一致），并把进度色改成白色保证对比度
        val progressSelected = tvSel == 1 && hasMedia
        progressPanel?.background = if (progressSelected) tvPanelSelected else tvPanelNormal
        seekBar?.let { sb ->
            val tint = if (progressSelected) 0xFFFFFFFF.toInt()
            else androidx.core.content.ContextCompat.getColor(this, R.color.accent)
            val cs = android.content.res.ColorStateList.valueOf(tint)
            sb.progressTintList = cs
            sb.thumbTintList = cs
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // TV 模式：dispatchKeyEvent 已拦截所有按键，这里只做 MENU/BACK 兜底
        if (isTvDevice) {
            if (keyCode in listOf(
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS
                )
            ) {
                showSettings()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                requestBack()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                try { CastEngine.togglePlayPause() } catch (_: Exception) {}
                showOverlay(true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                try { CastEngine.seekBy(-10_000L) } catch (_: Exception) {}
                showOverlay(true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                try { CastEngine.seekBy(10_000L) } catch (_: Exception) {}
                showOverlay(true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                try { CastEngine.adjustSpeed(0.25f) } catch (_: Exception) {}
                showOverlay(true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                try { CastEngine.adjustSpeed(-0.25f) } catch (_: Exception) {}
                showOverlay(true)
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_SETTINGS -> {
                showSettings()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                handleBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        requestBack()
    }

    /** 播放中连按两次返回才退出，避免误触；等待投屏界面一次即可返回 */
    private fun requestBack() {
        val hasMedia = try { CastEngine.getState().hasMedia } catch (_: Throwable) { false }
        if (!hasMedia) {
            handleBack()
            return
        }
        val now = System.currentTimeMillis()
        if (now <= backArmUntil) {
            backArmUntil = 0L
            handleBack()
        } else {
            backArmUntil = now + 2500L
            showTvHint(getString(R.string.back_stop_hint), 2400L)
        }
    }

    /** 单击返回：停止投屏并回到注册登录界面（不退出软件） */
    private fun handleBack() {
        // 遥控器可能同时下发 DOWN/UP，或部分盒子只发 UP：统一在这里去重，保证一次按键只处理一次
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 700L) return
        lastBackAt = now
        FreeCastApp.playerExitAt = now
        if (CastEngine.getState().hasMedia) CastEngine.reset()
        showOverlay(false)
        val intent = Intent(this, LoginActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun loadVersionInfo() {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            versionName = info.versionName ?: ""
            @Suppress("DEPRECATION")
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        } catch (_: Exception) {
        }
    }

    private fun loadConfig() {
        CloudApi.post(JSONObject().put("action", "config")) { json ->
            if (json?.optBoolean("ok") == true) {
                icp = json.optString("icp")
                announcement = json.optString("announcement")
            }
        }
    }

    private fun checkUpdate(manual: Boolean) {
        CloudApi.post(
            JSONObject()
                .put("action", "version")
                .put("versionCode", versionCode)
                .put("flavor", AppMode.flavor(this))
        ) { json ->
            when {
                json == null || !json.optBoolean("ok") -> {
                    if (manual) toast(R.string.update_failed)
                }

                !json.optBoolean("hasUpdate") -> {
                    if (manual) toast(R.string.update_latest)
                }

                else -> showUpdateDialog(json)
            }
        }
    }

    private fun showUpdateDialog(json: JSONObject) {
        val force = json.optBoolean("force")
        val url = json.optString("updateUrl")
        val changelog = json.optString("changelog").ifBlank { json.optString("versionName") }
        val builder = AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.update_title)
            .setMessage(changelog)
            .setPositiveButton(R.string.update_now) { _, _ ->
                if (url.isBlank()) {
                    toast(R.string.update_no_url)
                } else {
                    // 应用内直接下载安装，避免浏览器打开测试域名时的「页面访问提示」拦截
                    startAutoUpdate(url)
                }
            }
        if (!force) builder.setNegativeButton(R.string.update_later, null)
        builder.setCancelable(!force).show()
    }

    /** 一键自动更新：应用内下载 APK 并拉起系统安装器（带进度） */
    private fun startAutoUpdate(url: String) {
        val dialog = ProgressDialog(this).apply {
            setTitle(getString(R.string.update_title))
            setMessage(getString(R.string.update_downloading))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            progress = 0
            setCanceledOnTouchOutside(false)
        }
        dialog.show()
        Thread {
            var apk: File? = null
            var error = ""
            try {
                val dir = File(cacheDir, "update").apply { mkdirs() }
                val out = File(dir, "freecast-${System.currentTimeMillis()}.apk")
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw java.io.IOException("empty body")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var sum = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                sum += read
                                if (total > 0) {
                                    val pct = (sum * 100 / total).toInt().coerceIn(0, 100)
                                    runOnUiThread { dialog.progress = pct }
                                }
                            }
                        }
                    }
                }
                if (out.length() < 100 * 1024) throw java.io.IOException("file too small")
                apk = out
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                dialog.dismiss()
                val f = apk
                if (f != null) {
                    installApk(f)
                } else {
                    Toast.makeText(this, "下载失败：$error", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installApk(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
            // 依次尝试各安装通道，失败时提示真实原因
            val error = ApkInstaller.install(this, file)
            if (error != null) {
                Toast.makeText(this, getString(R.string.install_failed_detail, error), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.install_failed_detail, e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 客户端：接收作者推送的文字消息 ----------

    private fun startChatPolling() {
        if (chatPollingStarted) return
        chatPollingStarted = true
        chatLastId = getSharedPreferences("freecast", Context.MODE_PRIVATE)
            .getLong("chat_last_id", 0L)
        chatHandler.removeCallbacks(chatPollRunnable)
        chatHandler.post(chatPollRunnable)
    }

    private fun stopChatPolling() {
        chatPollingStarted = false
        chatHandler.removeCallbacks(chatPollRunnable)
    }

    private fun saveChatCursor(id: Long) {
        getSharedPreferences("freecast", Context.MODE_PRIVATE)
            .edit().putLong("chat_last_id", id).apply()
    }

    private fun pollClientMessages() {
        if (chatDialogShowing || isFinishing) return
        val body = JSONObject()
            .put("action", "client_get_messages")
            .put("deviceId", DeviceInfo.uuid)
            .put("sinceId", chatLastId)
        FreeCastApp.username(this)?.let { body.put("username", it) }
        CloudApi.post(body) { json ->
            if (json?.optBoolean("ok") != true) return@post
            val arr = json.optJSONArray("messages") ?: return@post
            if (arr.length() == 0) return@post
            val msgs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            val maxId = msgs.maxOfOrNull { it.optLong("id", 0L) } ?: 0L
            if (maxId > chatLastId) {
                chatLastId = maxId
                saveChatCursor(maxId)
            }
            val incoming = msgs.filter { it.optString("from") == "admin" }
            if (incoming.isNotEmpty()) showClientMessageDialog(incoming)
        }
    }

    private fun showClientMessageDialog(incoming: List<JSONObject>) {
        if (chatDialogShowing || isFinishing) return
        chatDialogShowing = true
        val density = resources.displayMetrics.density
        val text = incoming.joinToString("\n\n") { "客服：${it.optString("content")}" }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(), (12 * density).toInt(),
                (20 * density).toInt(), (4 * density).toInt()
            )
        }
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setLineSpacing(0f, 1.3f)
        })
        val input = android.widget.EditText(this).apply {
            hint = "输入回复内容"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
            minLines = 2
            gravity = Gravity.TOP
        }
        container.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("客服消息")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("回复") { _, _ -> sendClientMessage(input.text.toString()) }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { chatDialogShowing = false }
            .show()
    }

    private fun sendClientMessage(content: String) {
        val text = content.trim()
        if (text.isEmpty()) {
            toast("回复内容不能为空")
            return
        }
        val body = JSONObject()
            .put("action", "client_send_message")
            .put("deviceId", DeviceInfo.uuid)
            .put("content", text)
        FreeCastApp.username(this)?.let { body.put("username", it) }
        CloudApi.post(body) { json ->
            if (json?.optBoolean("ok") == true) {
                val id = json.optLong("id", 0L)
                if (id > chatLastId) {
                    chatLastId = id
                    saveChatCursor(id)
                }
                toast("已发送")
            } else {
                toast("发送失败，请稍后重试")
            }
        }
    }

    // ---------- 作者后台 ----------

    private fun promptAdminSecret() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.admin_orders_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("作者后台")
            .setView(input)
            .setPositiveButton("订单管理") { _, _ -> openAdminPanel(input.text.toString().trim(), "orders") }
            .setNeutralButton("用户管理") { _, _ -> openAdminPanel(input.text.toString().trim(), "users") }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openAdminPanel(admin: String, which: String) {
        if (admin.isBlank()) {
            Toast.makeText(this, R.string.admin_orders_forbidden, Toast.LENGTH_SHORT).show()
            return
        }
        when (which) {
            "orders" -> loadAdminOrders(admin)
            "users" -> loadAdminUsers(admin)
        }
    }

    private fun loadAdminUsers(admin: String) {
        Toast.makeText(this, getString(R.string.settings_member_loading), Toast.LENGTH_SHORT).show()
        CloudApi.post(JSONObject().put("action", "admin_users").put("admin", admin)) { json ->
            if (json?.optBoolean("ok") != true) {
                Toast.makeText(this, R.string.admin_orders_forbidden, Toast.LENGTH_SHORT).show()
                return@post
            }
            val arr = json.optJSONArray("users") ?: org.json.JSONArray()
            if (arr.length() == 0) {
                Toast.makeText(this, R.string.admin_users_empty, Toast.LENGTH_SHORT).show()
                return@post
            }
            val users = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            showAdminUsers(admin, users)
        }
    }

    private fun showAdminUsers(admin: String, users: List<JSONObject>) {
        val density = resources.displayMetrics.density
        val listView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (4 * density).toInt(),
                (16 * density).toInt(), (8 * density).toInt()
            )
        }
        for (u in users) {
            val name = u.optString("username")
            val created = u.optString("created_at").take(10)
            val last = u.optString("last_login").take(10).ifBlank { "-" }
            val plan = LicenseManager.planLabel(u.optString("memberPlan")).ifBlank { "未开通" }
            val revoked = u.optBoolean("memberRevoked")
            val memberLine = if (plan == "未开通") plan
                else "$plan${if (revoked) "（已移除）" else ""}"
            val text = "$name · $memberLine\n注册 $created · 最近登录 $last"
            listView.addView(TextView(this).apply {
                this.text = text
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setLineSpacing(0f, 1.2f)
                setPadding(
                    (14 * density).toInt(), (12 * density).toInt(),
                    (14 * density).toInt(), (12 * density).toInt()
                )
                background = getDrawable(R.drawable.bg_item_focus)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
                setOnClickListener { showAdminUserActions(admin, u) }
            })
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.admin_users_title)
            .setView(ScrollView(this).apply { addView(listView) })
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun showAdminUserActions(admin: String, u: JSONObject) {
        val name = u.optString("username")
        val plan = LicenseManager.planLabel(u.optString("memberPlan")).ifBlank { "未开通" }
        val revoked = u.optBoolean("memberRevoked")
        val message = buildString {
            append("账号：").append(name).append('\n')
            append("会员：").append(plan).append(if (revoked) "（已移除）" else "").append('\n')
            append("注册：").append(u.optString("created_at").take(10)).append('\n')
            append("最近登录：").append(u.optString("last_login").take(10).ifBlank { "-" })
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.admin_users_title)
            .setMessage(message)
            .setPositiveButton(R.string.admin_user_reset_pwd) { _, _ ->
                resetAdminUserPwd(admin, name)
            }
            .setNegativeButton("删除账号") { _, _ ->
                AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle("删除账号")
                    .setMessage("确定删除账号「$name」？所有关联的订单、会员、会话都会被清理，无法恢复。")
                    .setPositiveButton("确定删除") { _, _ -> deleteAdminUser(admin, name) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton(R.string.settings_close, null)
            .show()
    }

    private fun resetAdminUserPwd(admin: String, username: String) {
        CloudApi.post(
            JSONObject()
                .put("action", "admin_reset_user_password")
                .put("admin", admin)
                .put("username", username)
        ) { json ->
            val tmp = json?.optString("tempPassword")
            if (tmp.isNullOrBlank()) {
                Toast.makeText(this, "重置失败，请检查管理密钥", Toast.LENGTH_SHORT).show()
                return@post
            }
            android.app.AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.admin_user_reset_pwd)
                .setMessage(getString(R.string.admin_user_reset_ok, tmp))
                .setPositiveButton("复制") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("password", tmp))
                    Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun deleteAdminUser(admin: String, username: String) {
        CloudApi.post(
            JSONObject()
                .put("action", "admin_delete_user")
                .put("admin", admin)
                .put("username", username)
        ) { json ->
            if (json?.optBoolean("ok") == true) {
                Toast.makeText(this, R.string.admin_user_delete_ok, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "删除失败，请检查管理密钥", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAdminOrders(admin: String) {
        Toast.makeText(this, getString(R.string.settings_member_loading), Toast.LENGTH_SHORT).show()
        CloudApi.post(JSONObject().put("action", "admin_orders").put("admin", admin)) { json ->
            if (json?.optBoolean("ok") != true) {
                Toast.makeText(this, getString(R.string.admin_orders_forbidden), Toast.LENGTH_SHORT).show()
                return@post
            }
            val orders = json.optJSONArray("orders") ?: org.json.JSONArray()
            CloudApi.post(JSONObject().put("action", "admin_licenses").put("admin", admin)) { lic ->
                val licenses = lic?.optJSONArray("licenses") ?: org.json.JSONArray()
                if (orders.length() == 0 && licenses.length() == 0) {
                    Toast.makeText(this, getString(R.string.admin_orders_empty), Toast.LENGTH_SHORT).show()
                    return@post
                }
                showAdminBillList(admin, orders, licenses)
            }
        }
    }

    /** 统一列表：订单 + 激活码开通的会员；账号/设备/ID 放在点击后的详情窗口 */
    private fun showAdminBillList(
        admin: String,
        orders: org.json.JSONArray,
        licenses: org.json.JSONArray
    ) {
        val density = resources.displayMetrics.density
        val listView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (4 * density).toInt(),
                (16 * density).toInt(), (8 * density).toInt()
            )
        }

        fun addHeader(text: String) {
            listView.addView(TextView(this).apply {
                this.text = text
                setTextColor(0xFF8FB3FF.toInt())
                textSize = 12f
                setPadding((6 * density).toInt(), (12 * density).toInt(), 0, (6 * density).toInt())
            })
        }

        fun addRow(text: String, onClick: () -> Unit) {
            listView.addView(TextView(this).apply {
                this.text = text
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                setLineSpacing(0f, 1.2f)
                setPadding(
                    (14 * density).toInt(), (12 * density).toInt(),
                    (14 * density).toInt(), (12 * density).toInt()
                )
                background = getDrawable(R.drawable.bg_item_focus)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
                setOnClickListener { onClick() }
            })
        }

        if (orders.length() > 0) {
            addHeader("订单")
            for (i in 0 until orders.length()) {
                val o = orders.optJSONObject(i) ?: continue
                addRow(orderRowText(o)) { showAdminOrderDetail(admin, o) }
            }
        }
        if (licenses.length() > 0) {
            addHeader("激活码开通")
            for (i in 0 until licenses.length()) {
                val o = licenses.optJSONObject(i) ?: continue
                addRow(licenseRowText(o)) { showActivationDetail(admin, o) }
            }
        }

        val scroll = ScrollView(this).apply { addView(listView) }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.admin_orders_title)
            .setView(scroll)
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun orderRowText(o: JSONObject): String {
        val paid = o.optString("status") == "paid"
        val amount = o.optDouble("amount", 0.0)
        val plan = LicenseManager.planLabel(o.optString("plan"))
        val time = formatCloudTime(if (paid) o.optString("paid_at") else o.optString("created_at"))
        val pay = payMethodLabel(o.optString("pay_method"))
        val revoked = if (o.optBoolean("member_revoked")) " · 已移除会员" else ""
        return "${if (paid) "【已支付】" else "【待确认】"} ¥$amount · $plan · $pay · $time$revoked"
    }

    private fun licenseRowText(o: JSONObject): String {
        val plan = LicenseManager.planLabel(o.optString("plan"))
        val revoked = o.optBoolean("revoked")
        // 已移除的记录保留显示，标注状态便于追溯
        if (revoked) return "【已移除】$plan"
        val start = o.optString("activated_at").take(10)
        val expRaw = o.optString("expires_at")
        val expText = if (expRaw.isBlank()) "永久" else expRaw.take(10)
        return "【激活码】$plan · ${if (start.isBlank()) "-" else start} 至 $expText"
    }

    private fun payMethodLabel(method: String): String = when (method) {
        "alipay" -> "支付宝"
        "wechat" -> "微信"
        else -> "未记录"
    }

    /** 订单摘要：状态 / 金额档位 / 账号 / 设备型号 / 设备 ID / 支付方式 / 会员起止 */
    private fun orderSummary(o: JSONObject): String {
        val paid = o.optString("status") == "paid"
        val amount = o.optDouble("amount", 0.0)
        val plan = LicenseManager.planLabel(o.optString("plan"))
        val user = o.optString("username").ifBlank { "未登录" }
        val model = o.optString("device_model").ifBlank { "未知设备" }
        val dev = o.optString("device_id").ifBlank { "-" }
        val timeLabel = if (paid) "支付时间" else "下单时间"
        val time = formatCloudTime(if (paid) o.optString("paid_at") else o.optString("created_at"))
        val start = o.optString("member_start")
        val end = o.optString("member_end")
        val memberRange = when {
            start.isBlank() && end.isBlank() -> "未开通"
            end.isBlank() -> "${if (start.isBlank()) "-" else formatCloudTime(start)} 起 · 永久"
            else -> "${if (start.isBlank()) "-" else formatCloudTime(start)} 至 ${formatCloudTime(end)}"
        }
        val revoked = if (o.optBoolean("member_revoked")) "（已移除）" else ""
        return "${if (paid) "【已支付】" else "【待确认】"} ¥$amount · $plan\n" +
            "账号：$user\n设备：$model\n设备ID：$dev\n" +
            "支付方式：${payMethodLabel(o.optString("pay_method"))}\n" +
            "会员起止：$memberRange$revoked\n$timeLabel：$time"
    }

    private fun formatCloudTime(raw: String): String {
        if (raw.isBlank()) return "-"
        return try {
            // 云端返回的是带时区的本地时间字符串（如 2026-11-10T10:46:30.837+08:00），
            // 取年月日时分秒按本机时区解析即可
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = parser.parse(raw.take(19)) ?: return raw
            java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            raw
        }
    }

    private fun showAdminOrderDetail(admin: String, o: JSONObject) {
        val pending = o.optString("status") != "paid"
        val deviceId = o.optString("device_id")
        val username = o.optString("username")
        val orderId = o.optString("order_id")
        val message = orderSummary(o) + "\n订单号：$orderId"

        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(), (10 * density).toInt(),
                (20 * density).toInt(), (2 * density).toInt()
            )
        }
        content.addView(TextView(this).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setLineSpacing(0f, 1.2f)
        })
        content.addView(
            Button(this).apply {
                text = "发消息给客户端"
                setOnClickListener { showAdminChatDialog(admin, deviceId, username) }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        )

        val builder = AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.admin_order_detail)
            .setView(ScrollView(this).apply { addView(content) })
        if (pending) {
            builder.setPositiveButton(R.string.admin_confirm_pay) { _, _ ->
                confirmAdminOrder(admin, orderId, "设备 ${deviceId.take(8)}")
            }
            builder.setNegativeButton(R.string.admin_revoke_member) { _, _ ->
                confirmRevoke(admin, deviceId, username)
            }
            builder.setNeutralButton(R.string.settings_close, null)
        } else {
            builder.setPositiveButton(R.string.admin_revoke_member) { _, _ ->
                confirmRevoke(admin, deviceId, username)
            }
            builder.setNegativeButton(R.string.settings_close, null)
        }
        builder.show()
    }

    /** 作者与客户的文字对话：查看历史 + 推送新消息 */
    private fun showAdminChatDialog(admin: String, deviceId: String, username: String) {
        if (deviceId.isBlank() && username.isBlank()) {
            toast("该订单没有关联账号或设备，无法发送消息")
            return
        }
        CloudApi.post(
            JSONObject()
                .put("action", "admin_messages")
                .put("admin", admin)
                .put("deviceId", deviceId)
                .put("username", username)
        ) { json ->
            if (json?.optBoolean("ok") != true) {
                toast("读取对话失败，请检查管理密钥")
                return@post
            }
            val arr = json.optJSONArray("messages") ?: org.json.JSONArray()
            val density = resources.displayMetrics.density
            val history = TextView(this).apply {
                text = if (arr.length() == 0) "暂无消息" else buildString {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        append(if (m.optString("from") == "user") "客户：" else "我：")
                        append(m.optString("content"))
                        append('\n')
                    }
                }
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setLineSpacing(0f, 1.3f)
            }
            val input = android.widget.EditText(this).apply {
                hint = "输入要推送给客户的消息"
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF888888.toInt())
                minLines = 2
                gravity = Gravity.TOP
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (20 * density).toInt(), (10 * density).toInt(),
                    (20 * density).toInt(), (2 * density).toInt()
                )
                addView(history)
                addView(
                    input,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (12 * density).toInt() }
                )
            }
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("客户对话")
                .setView(ScrollView(this).apply { addView(box) })
                .setPositiveButton("发送") { _, _ ->
                    val text = input.text.toString().trim()
                    if (text.isEmpty()) {
                        toast("消息不能为空")
                    } else {
                        sendAdminMessage(admin, deviceId, username, text)
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun sendAdminMessage(admin: String, deviceId: String, username: String, content: String) {
        CloudApi.post(
            JSONObject()
                .put("action", "admin_send_message")
                .put("admin", admin)
                .put("deviceId", deviceId)
                .put("username", username)
                .put("content", content)
        ) { json ->
            if (json?.optBoolean("ok") == true) {
                toast("消息已推送")
                showAdminChatDialog(admin, deviceId, username)
            } else {
                toast("发送失败，请检查管理密钥")
            }
        }
    }

    /** 激活码开通的会员详情：账号 / 设备 / 设备 ID / 激活码 / 会员起止 / 状态 */
    private fun showActivationDetail(admin: String, o: JSONObject) {
        val deviceId = o.optString("device_id")
        val username = o.optString("username")
        val model = o.optString("device_model").ifBlank { "未记录" }
        val startRaw = o.optString("activated_at")
        val expiresRaw = o.optString("expires_at")
        val revoked = o.optBoolean("revoked")
        val message = buildString {
            append("档位：").append(LicenseManager.planLabel(o.optString("plan"))).append('\n')
            append("账号：").append(username.ifBlank { "未登录" }).append('\n')
            append("设备：").append(model).append('\n')
            append("设备ID：").append(deviceId.ifBlank { "-" }).append('\n')
            append("激活码：").append(o.optString("key").ifBlank { "-" }).append('\n')
            append("会员起止：")
            append(if (startRaw.isBlank()) "-" else startRaw.take(10))
            append(" 至 ")
            append(if (expiresRaw.isBlank()) "永久" else expiresRaw.take(10))
            append('\n')
            append("状态：").append(if (revoked) "已移除会员（记录保留）" else "生效中")
        }
        val builder = AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("激活码会员")
            .setMessage(message)
            .setNegativeButton(R.string.settings_close, null)
        if (!revoked) {
            builder.setPositiveButton(R.string.admin_revoke_member) { _, _ ->
                confirmRevoke(admin, deviceId, username)
            }
        }
        builder.show()
    }

    private fun confirmRevoke(admin: String, deviceId: String, username: String) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.admin_revoke_member)
            .setMessage(
                "将移除该设备/账号的会员权限，记录会保留在后台便于追溯；移除后需重新开通才能观看。\n\n" +
                    "账号：${username.ifBlank { "-" }}\n设备：${deviceId.ifBlank { "-" }}"
            )
            .setPositiveButton(R.string.admin_revoke_ok) { _, _ ->
                CloudApi.post(
                    JSONObject()
                        .put("action", "admin_revoke")
                        .put("admin", admin)
                        .put("deviceId", deviceId)
                        .put("username", username)
                ) { json ->
                    if (json?.optBoolean("ok") == true) {
                        Toast.makeText(this, R.string.admin_revoke_ok_toast, Toast.LENGTH_LONG).show()
                        LicenseManager.refresh { render(CastEngine.getState()) }
                    } else {
                        Toast.makeText(this, "操作失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmAdminOrder(admin: String, orderId: String, label: String) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("确认已收到该笔款项？")
            .setMessage("$label\n确认后云端立即为该设备开通会员")
            .setPositiveButton("确认开通") { _, _ ->
                CloudApi.post(
                    org.json.JSONObject()
                        .put("action", "confirm_order")
                        .put("admin", admin)
                        .put("orderId", orderId)
                ) { json ->
                    if (json?.optBoolean("ok") == true) {
                        android.widget.Toast.makeText(this, getString(R.string.admin_confirm_ok), Toast.LENGTH_LONG).show()
                        LicenseManager.refresh { render(CastEngine.getState()) }
                    } else {
                        android.widget.Toast.makeText(this, "操作失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettings() {
        // 进入设置界面时暂停播放，关闭设置后若之前正在播放则自动续播
        var resumeAfterSettings = false
        try {
            val st = CastEngine.getState()
            if (st.hasMedia && st.transportState == "PLAYING") {
                resumeAfterSettings = true
                CastEngine.pause()
            }
        } catch (_: Throwable) {}
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        view.findViewById<TextView>(R.id.settings_app).text =
            getString(R.string.settings_app, getString(R.string.app_name))
        val versionView = view.findViewById<TextView>(R.id.settings_version)
        versionView.text = getString(R.string.settings_version, versionName)

        // 隐藏的导出日志：连续点击版本号 5 次 → 显示 trace.log 内容
        var versionClickCount = 0
        var lastVersionClickAt = 0L
        versionView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastVersionClickAt > 2000) versionClickCount = 0
            versionClickCount++
            lastVersionClickAt = now
            if (versionClickCount >= 5) {
                versionClickCount = 0
                showDebugLogDialog()
            }
        }
        // 长按版本号 → 作者后台：确认收款并开通会员（需输入管理密钥，密钥不存本地）
        versionView.setOnLongClickListener {
            promptAdminSecret()
            true
        }
        // 备案号放在设置页最底部，小字号；未配置时隐藏
        val icpView = view.findViewById<TextView>(R.id.settings_icp)
        if (icp.isBlank()) {
            icpView.visibility = View.GONE
        } else {
            icpView.visibility = View.VISIBLE
            icpView.text = getString(R.string.settings_icp, icp)
        }
        val name = FreeCastApp.username(this) ?: getString(R.string.settings_not_logged_in)
        view.findViewById<TextView>(R.id.settings_account).text =
            getString(R.string.settings_account, name)

        // 邀请统计显示在账号同一行右侧
        val statsView = view.findViewById<TextView>(R.id.settings_invite_stats)
        val sessionToken = FreeCastApp.sessionToken(this)
        if (sessionToken != null) {
            CloudApi.post(
                JSONObject()
                    .put("action", "invite_info")
                    .put("token", sessionToken)
                    .put("deviceId", DeviceInfo.uuid)
            ) { json ->
                if (json?.optBoolean("ok") == true) {
                    statsView.text = getString(
                        R.string.invite_stats,
                        json.optInt("invited"),
                        json.optInt("days")
                    )
                    statsView.visibility = View.VISIBLE
                }
            }
        }

        val memberLabel = view.findViewById<TextView>(R.id.settings_member)
        val payButton = view.findViewById<Button>(R.id.settings_pay)
        if (BuildFlags.PAYMENT_ENABLED) {
            val memberText = when {
                !LicenseManager.status.loaded -> getString(R.string.settings_member_loading)
                LicenseManager.status.memberActive -> {
                    val plan = LicenseManager.planLabel(LicenseManager.status.plan)
                    val exp = LicenseManager.status.expiresAt.take(10)
                    // 永久会员只显示「永久」，不再带到期日
                    if (LicenseManager.status.plan == "forever" || exp.isBlank()) plan
                    else "$plan · 到期 $exp"
                }

                else -> getString(R.string.settings_member_none)
            }
            memberLabel.text = getString(R.string.settings_member, memberText)
        } else {
            // 调试期：隐藏一切会员/付费/激活信息
            memberLabel.visibility = View.GONE
            payButton.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.settings_device).text =
            getString(R.string.settings_device, DeviceInfo.uuid)

        val announcementView = view.findViewById<TextView>(R.id.settings_announcement)
        if (announcement.isBlank()) {
            announcementView.visibility = View.GONE
        } else {
            announcementView.visibility = View.VISIBLE
            announcementView.text = getString(R.string.settings_announcement, announcement)
        }

        val dialog = AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.settings_close, null)
            .create()

        // 对话框关闭后重新绑定画面，避免部分设备上 SurfaceView 被遮盖后只出声音不出画面；
        // 若进入设置前正在播放，则自动续播
        dialog.setOnDismissListener {
            if (::playerView.isInitialized) CastEngine.attachView(playerView)
            if (resumeAfterSettings) {
                resumeAfterSettings = false
                try { CastEngine.play() } catch (_: Throwable) {}
            }
            // 等待界面的设置按钮恢复焦点
            if (!CastEngine.getState().hasMedia) {
                findViewById<Button>(R.id.waiting_settings_btn)?.requestFocus()
            }
        }

        val auth = view.findViewById<Button>(R.id.settings_auth)
        val loggedIn = FreeCastApp.isLoggedIn(this)
        auth.setText(if (loggedIn) R.string.settings_logout else R.string.settings_login)
        auth.setOnClickListener {
            if (loggedIn) {
                // 退出登录后不再自动续播
                resumeAfterSettings = false
                dialog.dismiss()
                logout()
            } else {
                dialog.dismiss()
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
        val shareButton = view.findViewById<Button>(R.id.settings_share)
        if (!BuildFlags.PAYMENT_ENABLED || !loggedIn) {
            shareButton.visibility = View.GONE
        } else {
            shareButton.setOnClickListener {
                dialog.dismiss()
                InviteShare.show(this, FreeCastApp.sessionToken(this))
            }
        }
        view.findViewById<Button>(R.id.settings_update).setOnClickListener {
            checkUpdate(true)
        }
        if (BuildFlags.PAYMENT_ENABLED) {
            payButton.setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, PaymentActivity::class.java))
            }
        }
        view.findViewById<Button>(R.id.settings_feedback).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, FeedbackActivity::class.java))
        }
        dialog.show()
    }

    private fun startTrialTimer() {
        if (trialTimerRunning) return
        trialTimerRunning = true
        hideHandler.postDelayed(trialTick, 30_000L)
    }

    /** 试用超限：调试期只提示并重置试用；正式版进入开通会员页 */
    private fun onTrialExhausted() {
        trialTimerRunning = false
        CastEngine.reset()
        showOverlay(false)
        toast(R.string.trial_exhausted)
        if (BuildFlags.PAYMENT_ENABLED) {
            startActivity(Intent(this, PaymentActivity::class.java))
        } else {
            LicenseManager.init(applicationContext)
            render(CastEngine.getState())
        }
    }

    /** 5 天免费期内当日 5 次已用完：暂停并提示，次日恢复 */
    private fun showDailyLimit() {
        perCastLimitHit = true
        try { CastEngine.pause() } catch (_: Throwable) {}
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.trial_daily_limit_title)
            .setMessage(R.string.trial_daily_limit_msg)
            .setPositiveButton(R.string.pay_title) { _, _ ->
                startActivity(Intent(this, PaymentActivity::class.java))
            }
            .setNegativeButton(R.string.trial_daily_limit_ok, null)
            .show()
    }

    private fun logout() {
        val token = FreeCastApp.sessionToken(this)
        if (token == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }
        CloudApi.post(JSONObject().put("action", "logout").put("token", token)) {
            FreeCastApp.clearSession(this)
            // 真正退出账号：同时清除登录页记住的账号密码
            getSharedPreferences("freecast", Context.MODE_PRIVATE).edit()
                .remove("login_username")
                .remove("login_password")
                .apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    /** 分享得会员旧实现已由 InviteShare 统一处理 */

    private fun toast(resId: Int) {
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toast(msg: CharSequence) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // ---------------- 触屏手势：单击呼出控制条 / 双击暂停播放 / 长按 2x 上滑 3~5x ----------------

    private var boostActive = false
    private var boostSuppressTap = false
    private var boostSavedSpeed = 1f
    private var boostDownRawY = 0f
    private var boostLevel = 0

    private val boostStartRunnable = Runnable {
        try { startBoost() } catch (_: Throwable) {}
    }

    /** 长按：切到 2 倍速并记住原速，松手后恢复 */
    private fun startBoost() {
        val st = CastEngine.getState()
        if (!st.hasMedia) return
        boostActive = true
        boostSuppressTap = true
        boostLevel = 0
        boostSavedSpeed = st.speed
        try { CastEngine.setSpeed(2f) } catch (_: Throwable) {}
        speedBadge.visibility = View.VISIBLE
        showBoostHint(2f)
    }

    /** 上滑每 60dp 提一档：2x → 3x → 4x → 5x */
    private fun applyBoost(level: Int) {
        val lv = level.coerceIn(0, 3)
        if (lv == boostLevel) return
        boostLevel = lv
        try { CastEngine.setSpeed(2f + lv) } catch (_: Throwable) {}
        showBoostHint(2f + lv)
    }

    private fun showBoostHint(speed: Float) {
        val hint = boostHint ?: return
        hint.animate().cancel()
        hint.text = String.format(java.util.Locale.US, "%.1fx", speed)
        hint.setTextColor(if (speed >= 5f) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
        if (hint.visibility != View.VISIBLE) hint.alpha = 0.7f else hint.alpha = 0.85f
        hint.visibility = View.VISIBLE
    }

    private fun hideBoostHint() {
        val hint = boostHint ?: return
        hint.animate().cancel()
        hint.animate().alpha(0f).setDuration(200L).withEndAction {
            hint.visibility = View.GONE
            hint.alpha = 0.7f
        }.start()
    }

    private fun endBoost() {
        if (!boostActive) return
        boostActive = false
        try { CastEngine.setSpeed(boostSavedSpeed) } catch (_: Throwable) {}
        hideBoostHint()
    }

    private fun setupTouchGestures(target: View) {
        target.setOnTouchListener { _, ev ->
            handlePlaybackTouch(ev)
            true
        }
    }

    /** Activity 级触摸处理：不依赖 tap_catcher 是否被播放器盖住，保证车机/触屏单击一定有反应 */
    private val touchDetector: android.view.GestureDetector by lazy {
        android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    // 单击：呼出/收起控制条（不改动播放状态）
                    if (!boostSuppressTap) toggleOverlay()
                    return true
                }

                // 双击：暂停/继续
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    if (!boostSuppressTap) {
                        try { CastEngine.togglePlayPause() } catch (_: Throwable) {}
                        showOverlay(true)
                    }
                    return true
                }
            }
        )
    }

    /** 点按在控制条/信息面板上时放行给控件，其余全屏区域由 Activity 自己处理 */
    private fun isTouchOnControls(ev: android.view.MotionEvent): Boolean {
        fun hit(v: View?): Boolean {
            if (v == null || v.visibility != View.VISIBLE) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width &&
                ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
        }
        return hit(infoPanel) || hit(progressPanel)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // TV 遥控设备仍走按键；触屏（含车机）由 Activity 统一处理点按
        if (!isTvDevice && isTouchOnControls(ev)) return super.dispatchTouchEvent(ev)
        if (!isTvDevice) {
            handlePlaybackTouch(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 单击呼出/收起控制条，双击暂停，长按上滑临时倍速 */
    private fun handlePlaybackTouch(ev: android.view.MotionEvent) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                boostSuppressTap = false
                boostLevel = 0
                boostDownRawY = ev.rawY
                hideHandler.postDelayed(boostStartRunnable, 400L)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (boostActive) {
                    val up = boostDownRawY - ev.rawY
                    applyBoost((up / (60f * resources.displayMetrics.density)).toInt())
                } else if (kotlin.math.abs(ev.rawY - boostDownRawY) > 24f) {
                    hideHandler.removeCallbacks(boostStartRunnable)
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                hideHandler.removeCallbacks(boostStartRunnable)
                endBoost()
            }
        }
        touchDetector.onTouchEvent(ev)
    }

    /** 单击画面：呼出全部控制条；控制条已显示时再次单击收起 */
    private fun toggleOverlay() {
        if (overlayVisible || progressVisible) {
            cancelHide()
            fadeOverlay(0f)
            fadeProgress(0f)
        } else {
            showOverlay(true)
        }
    }

    /** 全量控制面板（标题/倍速/进度条），单击画面或 TV 确认键触发 */
    private fun showOverlay(autoHide: Boolean) {
        overlayVisible = true
        overlayViews.forEach { v ->
            v.animate().cancel()
            v.visibility = View.VISIBLE
            v.alpha = 1f
        }
        if (CastEngine.getState().hasMedia) showProgressBar()
        // TV 模式完全使用自绘焦点，不再把系统焦点塞给 SeekBar（否则会出现「空一步」的假焦点）
        if (autoHide) {
            scheduleHide()
        } else {
            cancelHide()
        }
    }

    /** 只显示底部进度条（触屏单击呼出） */
    private fun showProgressOnly() {
        if (!CastEngine.getState().hasMedia) return
        if (showProgressBar()) scheduleHide()
    }

    private fun showProgressBar(): Boolean {
        val p = progressPanel ?: return false
        p.animate().cancel()
        p.visibility = View.VISIBLE
        p.alpha = 1f
        progressVisible = true
        return true
    }

    private fun fadeOverlay(target: Float) {
        if (target == 0f) overlayVisible = false
        overlayViews.forEach { v ->
            v.animate().alpha(target).setDuration(250L)
                .withEndAction {
                    v.visibility = if (target == 0f) View.INVISIBLE else View.VISIBLE
                }
                .start()
        }
    }

    private fun fadeProgress(target: Float) {
        val p = progressPanel ?: return
        p.animate().cancel()
        if (target == 0f) {
            progressVisible = false
            p.animate().alpha(0f).setDuration(200L).withEndAction {
                if (!progressVisible) {
                    p.visibility = View.GONE
                    p.alpha = 1f
                }
            }.start()
        } else {
            p.visibility = View.VISIBLE
            p.alpha = 1f
            progressVisible = true
        }
    }

    /** 2 秒后自动隐藏控制条（每次呼出都重新计时） */
    private fun scheduleHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 2000L)
    }

    private fun cancelHide() {
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun fileNameFromUri(uri: String?): String {
        if (uri.isNullOrBlank()) return getString(R.string.app_name)
        return try {
            Uri.parse(uri).lastPathSegment?.substringBefore('?')?.takeIf { it.isNotBlank() }
                ?: getString(R.string.app_name)
        } catch (_: Exception) {
            getString(R.string.app_name)
        }
    }

    private fun formatSpeed(speed: Float): String {
        if (speed == speed.toInt().toFloat()) return "${speed.toInt()}.0x"
        return String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x"
    }

    private fun formatHms(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
