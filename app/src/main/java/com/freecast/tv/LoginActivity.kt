package com.freecast.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 账号登录 / 注册页。登录成功后可选择进入主界面或稍后再登录。 */
class LoginActivity : Activity() {

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var inviteInput: EditText
    private lateinit var submit: Button
    private lateinit var switchMode: TextView
    private lateinit var forgotBtn: TextView
    private lateinit var status: TextView

    private var registerMode = false

    // 强制更新：云端有新版本时必须更新，否则退出软件
    private var forceUpdatePending = false
    private var latestVersionCode = 0
    private var updateInProgress = false
    private var installLaunched = false
    private var awaitingInstallPermission = false
    private var pendingApk: File? = null

    // 投屏成功（媒体已到达）时，登录页自动让位给播放界面
    private var jumpedToPlayer = false
    private var mediaOnEntry = false
    private var mediaEnded = false
    private val castListener = object : CastEngine.Listener {
        override fun onStateChanged(state: CastEngine.State) {
            if (!state.hasMedia) {
                mediaEnded = true
                return
            }
            if (forceUpdatePending) return
            // 退出登录时已在播放的不抢回播放页；登录页出现后才开始投屏的才跳转
            if (mediaOnEntry && !mediaEnded) return
            enterPlayerForCast()
        }
    }

    override fun onStart() {
        super.onStart()
        // addListener 会立即回调当前状态，媒体已到达时会自动进入播放界面
        try { CastEngine.addListener(castListener) } catch (_: Throwable) {}
    }

    override fun onStop() {
        try { CastEngine.removeListener(castListener) } catch (_: Throwable) {}
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 从「允许安装未知应用」设置页返回后继续安装
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            pendingApk?.let { installApk(it) }
            return
        }
        // 从系统安装器返回但版本没变 → 用户取消了安装，按未更新处理
        if (installLaunched) {
            installLaunched = false
            if (currentVersionCode() < latestVersionCode) exitForNoUpdate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaOnEntry = try { CastEngine.getState().hasMedia } catch (_: Throwable) { false }
        Trace.log("LoginActivity", "onCreate enter, package=$packageName")
        try {
            Trace.log("LoginActivity", "about to setContentView")
            setContentView(R.layout.activity_login)
            Trace.log("LoginActivity", "setViewContent OK")
            // 登录页也启动接收服务：否则手机在注册登录界面搜不到投屏设备
            try { CastService.start(this) } catch (_: Throwable) {}

            usernameInput = findViewById(R.id.username_input)
            passwordInput = findViewById(R.id.password_input)
            inviteInput = findViewById(R.id.invite_input)
            submit = findViewById(R.id.login_submit)
            switchMode = findViewById(R.id.login_switch)
            forgotBtn = findViewById(R.id.login_forgot)
            status = findViewById(R.id.login_status)
            Trace.log("LoginActivity", "all views found OK")

            submit.setOnClickListener { submit() }
            passwordInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else {
                    false
                }
            }
            switchMode.setOnClickListener { toggleMode() }
            forgotBtn.setOnClickListener { openForgotPassword() }
            findViewById<TextView>(R.id.login_skip).setOnClickListener {
                Trace.log("LoginActivity", "user clicked '直接试用'")
                FreeCastApp.markEntered(this)
                Trace.log("LoginActivity", "markEntered done")
                try {
                    val intent = Intent(this, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                    Trace.log("LoginActivity", "startActivity MainActivity OK")
                    // 延迟 finish，让 MainActivity 先起来，用户不会看到黑屏闪退
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { finish() } catch (_: Exception) {}
                    }, 500L)
                } catch (e: Throwable) {
                    Trace.error("LoginActivity", e)
                    android.app.AlertDialog.Builder(this, R.style.DialogTheme)
                        .setTitle("启动异常")
                        .setMessage("进入主界面失败，请截图发给 QQ：84461502\n\n${e.javaClass.simpleName}: ${e.message}")
                        .setPositiveButton("关闭", null)
                        .show()
                }
            }
            // 测试阶段：隐藏「分享免费投」邀请入口（不可见 = TV/触屏都无法聚焦）
            findViewById<TextView>(R.id.login_share)?.visibility = View.GONE
            findViewById<TextView>(R.id.login_share)?.setOnClickListener {
                InviteShare.show(this, FreeCastApp.sessionToken(this)) {
                    android.widget.Toast.makeText(
                        this, "请先在上方登录或注册，登录后即可生成邀请二维码",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            findViewById<TextView>(R.id.login_push).setOnClickListener {
                startActivity(Intent(this, PushActivity::class.java))
            }
            // 「直接试用」高亮呼吸闪烁，引导用户先试用
            findViewById<TextView>(R.id.login_skip)?.let { skip ->
                val blink = android.animation.ObjectAnimator.ofFloat(skip, "alpha", 1f, 0.35f).apply {
                    duration = 700L
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
                skip.setOnFocusChangeListener { _, hasFocus ->
                    // 聚焦时停止闪烁并保持全亮，避免遥控操作时看不清
                    if (hasFocus) {
                        blink.cancel()
                        skip.alpha = 1f
                    } else {
                        blink.start()
                    }
                }
            }
            Trace.log("LoginActivity", "onCreate fully initialized OK")

            // 记住上次登录成功的账号密码：返回首页时自动回填，直接点登录即可进入
            val prefs = getSharedPreferences("freecast", MODE_PRIVATE)
            usernameInput.setText(prefs.getString("login_username", ""))
            passwordInput.setText(prefs.getString("login_password", ""))

            // 首页字体按屏幕尺寸自适应（手机按宽度缩放，电视/大屏保持原样）
            applyResponsiveText()
            // 遥控器默认焦点落在「登录」按钮，可上下切换到注册/直接试用
            submit.requestFocus()
            // 云端有更新时，注册登录页弹出强制更新窗口
            checkForceUpdate()
        } catch (e: Throwable) {
            Trace.error("LoginActivity", e)
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("启动异常")
                .setMessage("登录页启动遇到问题，请长按版本号→导出日志发给 QQ：84461502\n\n${sw.toString().take(1500)}")
                .setPositiveButton("关闭") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private var lastBackAt = 0L

    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000L) {
            finishAffinity()
            return
        }
        lastBackAt = now
        android.widget.Toast.makeText(this, R.string.login_back_exit_hint, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toggleMode() {
        registerMode = !registerMode
        submit.setText(if (registerMode) R.string.login_register_submit else R.string.login_submit)
        switchMode.setText(if (registerMode) R.string.login_to_login else R.string.login_to_register)
        inviteInput.visibility = if (registerMode) TextView.VISIBLE else TextView.GONE
        status.visibility = TextView.GONE
    }

    /**
     * 首页字体按屏幕大小自适应：
     * - 手机（宽度 < 600dp）：保持设计稿字号，和以前完全一致
     * - 平板/电视（宽度 >= 600dp）：按宽度等比放大，1.0x ~ 1.8x
     */
    private fun applyResponsiveText() {
        try {
            val dm = resources.displayMetrics
            val widthDp = dm.widthPixels / dm.density
            if (widthDp < 600f) return
            val scale = (widthDp / 600f).coerceIn(1.0f, 1.8f)
            if (scale == 1f) return

            fun walk(v: android.view.View) {
                // 输入框/按钮保持固定字号，避免文字被撑出输入框
                if (v is TextView && v !is EditText && v !is Button) {
                    v.textSize = v.textSize * scale
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(findViewById(android.R.id.content))
        } catch (e: Throwable) {
            Trace.error("LoginActivity", e)
        }
    }

    private fun submit() {
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (username.length < 3) {
            showError(getString(R.string.login_error_username))
            return
        }
        if (password.length < 6) {
            showError(getString(R.string.login_error_password))
            return
        }

        setBusy(true)
        status.visibility = TextView.VISIBLE
        status.setText(R.string.login_working)

        val body = JSONObject()
            .put("action", if (registerMode) "register" else "login")
            .put("username", username)
            .put("password", password)
        if (registerMode) {
            // 注册时上报设备信息，云端据此校验邀请奖励是否可发放
            val inviteCode = inviteInput.text.toString().trim().uppercase()
            body.put("deviceId", DeviceInfo.uuid)
            body.put("androidId", androidId())
            body.put("deviceBrand", Build.BRAND ?: "")
            body.put("deviceModel", Build.MODEL ?: "")
            body.put("deviceFingerprint", Build.FINGERPRINT ?: "")
            if (inviteCode.isNotEmpty()) body.put("inviteCode", inviteCode)
        }
        Trace.log("LoginActivity", "sending ${if (registerMode) "register" else "login"} for $username")
        CloudApi.post(body) { json -> onResult(json, username) }
    }

    private fun onResult(json: JSONObject?, username: String) {
        setBusy(false)
        Trace.log("LoginActivity", "onResult ok=${json?.optBoolean("ok")}")
        if (json == null) {
            showError(getString(R.string.login_error_network))
            return
        }
        if (json.optBoolean("ok")) {
            val token = json.optString("token")
            val name = json.optString("username", username)
            FreeCastApp.saveSession(this, token, name)
            FreeCastApp.markEntered(this)
            // 记录本次登录信息，返回首页时保持填写状态
            getSharedPreferences("freecast", MODE_PRIVATE).edit()
                .putString("login_username", name)
                .putString("login_password", passwordInput.text.toString())
                .apply()
            status.visibility = TextView.GONE
            android.widget.Toast.makeText(this, R.string.login_success, android.widget.Toast.LENGTH_SHORT).show()
            val invite = json.optJSONObject("invite")
            if (invite != null && invite.optString("reason") != "no_code") {
                val msg = if (invite.optBoolean("ok")) {
                    R.string.login_invite_reward_ok
                } else {
                    R.string.login_invite_reward_fail
                }
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val message = when (json.optString("error")) {
            "bad_credentials" -> R.string.login_error_credentials
            "user_exists" -> R.string.login_error_exists
            "username_format" -> R.string.login_error_username
            "password_format" -> R.string.login_error_password
            else -> R.string.login_error_network
        }
        showError(getString(message))
    }

    private fun setBusy(busy: Boolean) {
        submit.isEnabled = !busy
        usernameInput.isEnabled = !busy
        passwordInput.isEnabled = !busy
        inviteInput.isEnabled = !busy
        switchMode.isEnabled = !busy
        forgotBtn.isEnabled = !busy
    }

    /** 与设备号生成逻辑一致的 ANDROID_ID，注册时随邀请码一起上报 */
    private fun androidId(): String = try {
        android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun showError(message: String) {
        status.visibility = TextView.VISIBLE
        status.text = message
    }

    /** 忘记密码：弹窗让用户输入账号 + 新密码，用当前设备 ID 作为身份凭证 */
    private fun openForgotPassword() {
        val view = layoutInflater.inflate(R.layout.dialog_reset_password, null)
        val username = view.findViewById<EditText>(R.id.reset_username).apply {
            setText(usernameInput.text.toString())
        }
        val pwd = view.findViewById<EditText>(R.id.reset_password)
        val pwd2 = view.findViewById<EditText>(R.id.reset_password2)

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.login_forgot_title)
            .setMessage(R.string.login_forgot_msg)
            .setView(view)
            .setPositiveButton(R.string.login_forgot_submit) { _, _ ->
                val u = username.text.toString().trim()
                val p1 = pwd.text.toString()
                val p2 = pwd2.text.toString()
                if (u.length < 3) {
                    showError(getString(R.string.login_error_username))
                    return@setPositiveButton
                }
                if (p1.length < 6) {
                    showError(getString(R.string.login_error_password))
                    return@setPositiveButton
                }
                if (p1 != p2) {
                    showError("两次输入的密码不一致")
                    return@setPositiveButton
                }
                val body = JSONObject()
                    .put("action", "reset_password")
                    .put("username", u)
                    .put("password", p1)
                    .put("deviceId", DeviceInfo.uuid)
                setBusy(true)
                status.visibility = TextView.VISIBLE
                status.setText(R.string.login_working)
                CloudApi.post(body) { json ->
                    setBusy(false)
                    if (json?.optBoolean("ok") == true) {
                        status.visibility = TextView.GONE
                        android.widget.Toast.makeText(
                            this, R.string.login_forgot_ok, android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val err = json?.optString("error")
                        val msg = when (err) {
                            "device_not_match" -> R.string.login_forgot_device_mismatch
                            "user_not_found" -> R.string.login_forgot_user_missing
                            "username_format" -> R.string.login_error_username
                            "password_format" -> R.string.login_error_password
                            else -> R.string.login_error_network
                        }
                        showError(getString(msg))
                    }
                }
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    // ---------- 强制更新 ----------

    private fun currentVersionCode(): Int = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (_: Exception) {
        0
    }

    private fun checkForceUpdate() {
        CloudApi.post(
            JSONObject()
                .put("action", "version")
                .put("versionCode", currentVersionCode())
                .put("flavor", AppMode.flavor(this))
        ) { json ->
            if (isFinishing || isDestroyed) return@post
            if (json?.optBoolean("ok") != true || !json.optBoolean("hasUpdate")) return@post
            latestVersionCode = json.optInt("versionCode", 0)
            showForceUpdateDialog(json)
        }
    }

    private fun showForceUpdateDialog(json: JSONObject) {
        if (forceUpdatePending || isFinishing) return
        forceUpdatePending = true
        val url = json.optString("updateUrl")
        val backupUrl = json.optString("backupUrl")
        val versionName = json.optString("versionName")
        val changelog = json.optString("changelog").ifBlank { "请更新到最新版本后继续使用" }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(if (versionName.isBlank()) "发现新版本" else "发现新版本 $versionName")
            .setMessage("$changelog\n\n自动下载卡住不动时，请点「手动下载」用浏览器打开备用地址。\n备用地址（蓝奏云）提取码：yyxh")
            .setCancelable(false)
            .setPositiveButton("自动更新") { _, _ ->
                if (url.isBlank()) {
                    openUpdatePage(backupUrl)
                } else {
                    startUpdateDownload(url, backupUrl)
                }
            }
            .setNeutralButton("手动下载") { _, _ ->
                openUpdatePage(backupUrl.ifBlank { url })
            }
            .setNegativeButton("退出软件") { _, _ -> exitForNoUpdate() }
            .show()
    }

    /** 用浏览器打开下载页；TV 无浏览器时复制链接并提示 */
    private fun openUpdatePage(link: String) {
        if (link.isBlank()) {
            exitForNoUpdate()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            android.widget.Toast.makeText(this, "已用浏览器打开下载页（提取码 yyxh），下载后安装即可", android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("update_url", link))
                AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle("无法打开浏览器")
                    .setMessage("本机没有可用浏览器。下载地址已复制到剪贴板（提取码 yyxh）：\n\n$link")
                    .setCancelable(false)
                    .setPositiveButton("退出软件") { _, _ -> exitForNoUpdate() }
                    .show()
            } catch (_: Exception) {
                exitForNoUpdate()
            }
        }
    }

    /** 自动下载失败：不再直接退出，引导用浏览器手动下载 */
    private fun showDownloadFailedDialog(error: String, link: String) {
        if (isFinishing || isDestroyed) {
            exitForNoUpdate()
            return
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("自动更新失败")
            .setMessage("原因：$error\n\n可点「手动下载」用浏览器打开备用地址（蓝奏云）更新。\n备用地址提取码：yyxh")
            .setCancelable(false)
            .setPositiveButton("手动下载") { _, _ -> openUpdatePage(link) }
            .setNeutralButton("重试") { _, _ ->
                forceUpdatePending = false
                checkForceUpdate()
            }
            .setNegativeButton("退出软件") { _, _ -> exitForNoUpdate() }
            .show()
    }

    /** 未更新：直接退出整个软件 */
    private fun exitForNoUpdate() {
        android.widget.Toast.makeText(this, "请更新后使用", android.widget.Toast.LENGTH_SHORT).show()
        try { finishAffinity() } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 300L)
    }

    /** 应用内下载 APK 并拉起系统安装器（带进度） */
    private fun startUpdateDownload(url: String, fallbackLink: String = "") {
        if (updateInProgress) return
        updateInProgress = true
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
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
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
                updateInProgress = false
                val f = apk
                if (f != null) {
                    installApk(f)
                } else {
                    showDownloadFailedDialog(error, fallbackLink.ifBlank { url })
                }
            }
        }.start()
    }

    private fun installApk(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                pendingApk = file
                awaitingInstallPermission = true
                android.widget.Toast.makeText(this, R.string.update_allow_install, android.widget.Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
            installLaunched = true
            // 依次尝试 PackageInstaller / ACTION_VIEW 等安装通道
            val error = ApkInstaller.install(this, file)
            if (error != null) {
                android.widget.Toast.makeText(
                    this, getString(R.string.install_failed_detail, error),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                exitForNoUpdate()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, getString(R.string.install_failed_detail, e.javaClass.simpleName),
                android.widget.Toast.LENGTH_LONG
            ).show()
            exitForNoUpdate()
        }
    }

    // ---------- 投屏成功自动进入播放界面 ----------

    private fun enterPlayerForCast() {
        if (jumpedToPlayer || isFinishing || isDestroyed) return
        // 用户刚主动从播放页返回：短时间内不再自动跳回，避免「按返回被弹回播放页」
        if (System.currentTimeMillis() - FreeCastApp.playerExitAt < 6000L) {
            Trace.log("LoginActivity", "skip auto-enter player: user just left player")
            return
        }
        jumpedToPlayer = true
        Trace.log("LoginActivity", "media arrived on login page → open player")
        FreeCastApp.markEntered(this)
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (e: Throwable) {
            Trace.error("LoginActivity", e)
        }
        finish()
    }
}
