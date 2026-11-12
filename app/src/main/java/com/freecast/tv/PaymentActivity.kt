package com.freecast.tv

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 开通会员页：选择档位 → 微信/支付宝扫码付款 → 输入激活码开通。
 * 激活码由云端生成并绑定设备，一码一次。
 */
class PaymentActivity : Activity() {

    private lateinit var tiersLayout: LinearLayout
    private lateinit var amountText: TextView
    private lateinit var wechatBtn: Button
    private lateinit var alipayBtn: Button
    private lateinit var qrImage: ImageView
    private lateinit var qrHint: TextView
    private lateinit var qrTip: TextView
    private lateinit var keyInput: EditText
    private lateinit var submit: Button
    private lateinit var statusText: TextView
    private lateinit var orderBtn: Button
    private lateinit var orderStatusText: TextView

    private var selectedPlan = "month1"
    private var method = "wechat"

    /** 半自动订单：提交后每 3 秒查询一次，作者确认收款后自动开通 */
    private var orderId: String? = null
    private val orderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val orderPoller = object : Runnable {
        override fun run() {
            val id = orderId ?: return
            CloudApi.post(JSONObject().put("action", "order_status").put("orderId", id)) { json ->
                if (json?.optBoolean("ok") == true && json.optString("status") == "paid") {
                    orderStatusText.text = getString(R.string.pay_order_paid)
                    Toast.makeText(this@PaymentActivity, getString(R.string.pay_order_paid), Toast.LENGTH_LONG).show()
                    LicenseManager.refresh {
                        renderTiers()
                        finish()
                    }
                } else {
                    orderHandler.postDelayed(this, 3000L)
                }
            }
        }
    }

    private val prices: JSONObject
        get() = try {
            JSONObject(LicenseManager.effectivePrices)
        } catch (_: Exception) {
            JSONObject(LicenseManager.DEFAULT_PRICES)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)
        AppMode.applyStatusBarTopPadding(this)

        tiersLayout = findViewById(R.id.pay_tiers)
        amountText = findViewById(R.id.pay_amount)
        wechatBtn = findViewById(R.id.pay_method_wechat)
        alipayBtn = findViewById(R.id.pay_method_alipay)
        qrImage = findViewById(R.id.pay_qr_image)
        qrHint = findViewById(R.id.pay_qr_hint)
        qrTip = findViewById(R.id.pay_qr_tip)
        keyInput = findViewById(R.id.pay_key_input)
        submit = findViewById(R.id.pay_submit)
        statusText = findViewById(R.id.pay_status)
        orderBtn = findViewById(R.id.pay_order)
        orderStatusText = findViewById(R.id.pay_order_status)

        findViewById<Button>(R.id.pay_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.pay_qq).setOnClickListener { showAuthorQqDialog() }
        findViewById<Button>(R.id.pay_share).setOnClickListener {
            InviteShare.show(this, FreeCastApp.sessionToken(this))
        }

        wechatBtn.setOnClickListener {
            method = "wechat"
            updateSummary()
            renderQr()
        }
        alipayBtn.setOnClickListener {
            method = "alipay"
            updateSummary()
            renderQr()
        }

        submit.setOnClickListener { activate() }
        orderBtn.setOnClickListener { submitOrder() }
        keyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                activate()
                true
            } else {
                false
            }
        }

        applyAdaptiveLayout()
        renderTiers()
        renderQr()
        // TV 版进入页面后焦点落在第一个会员档位上
        if (AppMode.isTv(this)) {
            tiersLayout.post {
                ((tiersLayout.getChildAt(0) as? LinearLayout)?.getChildAt(0))?.requestFocus()
            }
        }
        LicenseManager.refresh {
            renderTiers()
            renderQr()
        }
    }

    /** TV / 触屏横屏：左档位+支付方式，右二维码；触屏竖屏：上下排列 */
    private fun applyAdaptiveLayout() {
        try {
            val dm = resources.displayMetrics
            val isTv = AppMode.isTv(this)
            val portrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            val columns = findViewById<LinearLayout>(R.id.pay_columns)
            val left = findViewById<LinearLayout>(R.id.pay_left)
            val right = findViewById<LinearLayout>(R.id.pay_right)
            if (isTv || !portrait) {
                columns.orientation = LinearLayout.HORIZONTAL
                left.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f
                )
                right.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                left.setPadding(0, 0, dp(16), 0)
                right.setPadding(dp(16), 0, 0, 0)
                // 横屏/TV：激活码输入与激活按钮移到左侧支付方式下方
                for (v in listOf<View>(keyInput, submit, statusText)) {
                    if (v.parent !== left) {
                        (v.parent as? android.view.ViewGroup)?.removeView(v)
                        left.addView(v)
                    }
                }
                val size = minOf((dm.heightPixels * 0.46f).toInt(), dp(300)).coerceAtLeast(dp(180))
                qrImage.layoutParams = qrImage.layoutParams.apply {
                    width = size
                    height = size
                }
            } else {
                columns.orientation = LinearLayout.VERTICAL
                left.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                right.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                left.setPadding(0, 0, 0, 0)
                right.setPadding(0, 0, 0, 0)
                val size = minOf(dm.widthPixels - dp(96), dp(240)).coerceAtLeast(dp(140))
                qrImage.layoutParams = qrImage.layoutParams.apply {
                    width = size
                    height = size
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 联系作者：弹窗显示 QQ，点击「复制并打开 QQ」复制号码后跳转 QQ */
    private fun showAuthorQqDialog() {
        val qq = "84461502"
        android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.pay_contact_author)
            .setMessage(getString(R.string.pay_contact_message, qq))
            .setPositiveButton(R.string.pay_copy_open_qq) { _, _ ->
                try {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ", qq))
                    Toast.makeText(this, getString(R.string.pay_qq_copied, qq), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
                openQQ()
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    /** 尝试打开 QQ 与作者聊天；若未安装则复制 QQ 号到剪贴板 */
    private fun openQQ() {
        val qq = "84461502"
        val mqqIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mqqwpa://im/chat?chat_type=wpa&uin=$qq&version=1")
        )
        if (packageManager.queryIntentActivities(mqqIntent, 0).isNotEmpty()) {
            try {
                startActivity(mqqIntent)
                return
            } catch (_: Exception) {
            }
        }
        val explicit = Intent().apply {
            setClassName("com.tencent.mobileqq", "com.tencent.mobileqq.activity.SplashActivity")
            putExtra("entrance", "3")
            putExtra("jump_url", "mqqwpa://im/chat?chat_type=wpa&uin=$qq&version=1")
        }
        if (packageManager.queryIntentActivities(explicit, 0).isNotEmpty()) {
            try {
                startActivity(explicit)
                return
            } catch (_: Exception) {
            }
        }
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ", qq))
            Toast.makeText(this, "未检测到 QQ，已复制作者 QQ $qq 到剪贴板", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "作者 QQ：$qq，请在 QQ 中搜索添加", Toast.LENGTH_LONG).show()
        }
    }

    /** 生成档位卡片：两列并排，当前档位高亮，遥控器可聚焦选择 */
    private fun renderTiers() {
        val priceObj = prices
        tiersLayout.removeAllViews()
        val plans = LicenseManager.planOrder.mapNotNull { (plan, label) ->
            val price = priceObj.optDouble(plan, Double.NaN)
            if (price.isNaN()) null else Triple(plan, label, price)
        }
        val cells = mutableListOf<Triple<String, String, TextView>>()

        var index = 0
        while (index < plans.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            for (col in 0 until 2) {
                val idx = index + col
                if (idx >= plans.size) {
                    // 单数档位时用占位撑住两列对齐
                    val spacer = View(this)
                    spacer.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                        if (col == 1) marginStart = dp(8)
                    }
                    row.addView(spacer)
                    continue
                }
                val (plan, label, price) = plans[idx]
                val cell = TextView(this)
                val selected = plan == selectedPlan
                cell.text = if (selected) "✓ $label  ¥${trimPrice(price)}" else "$label  ¥${trimPrice(price)}"
                cell.setTextColor(Color.WHITE)
                cell.textSize = 16f
                cell.typeface = Typeface.DEFAULT_BOLD
                cell.gravity = Gravity.CENTER
                cell.setBackgroundResource(R.drawable.bg_tier_focus)
                cell.isSelected = selected
                cell.isFocusable = true
                cell.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    if (col == 1) marginStart = dp(8)
                }
                cell.setOnClickListener {
                    if (!FreeCastApp.isLoggedIn(this@PaymentActivity)) {
                        showLoginRequired(plan, cells)
                        return@setOnClickListener
                    }
                    showTierConfirm(plan, label, price, cells)
                }
                cells.add(Triple(plan, label, cell))
                row.addView(cell)
            }
            tiersLayout.addView(row)
            index += 2
        }
        updateSummary()
    }

    /** 切换档位：只更新选中状态，不重建视图，避免遥控器焦点丢失 */
    private fun selectPlan(plan: String, cells: List<Triple<String, String, TextView>>) {
        selectedPlan = plan
        orderId = null
        orderHandler.removeCallbacks(orderPoller)
        orderStatusText.text = ""
        val priceObj = prices
        for ((p, label, cell) in cells) {
            val price = priceObj.optDouble(p, Double.NaN)
            val priceText = if (price.isNaN()) "" else "  ¥${trimPrice(price)}"
            val selected = p == plan
            cell.text = if (selected) "✓ $label$priceText" else "$label$priceText"
            cell.isSelected = selected
        }
        updateSummary()
    }

    /** 未登录时选择会员档位：提示后可继续支付或跳转登录 */
    private fun showLoginRequired(
        plan: String,
        cells: List<Triple<String, String, TextView>>
    ) {
        android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.tip_title)
            .setMessage(R.string.pay_login_required)
            .setPositiveButton(R.string.pay_continue_pay) { _, _ ->
                if (plan != selectedPlan) selectPlan(plan, cells)
            }
            .setNegativeButton(R.string.pay_go_login) { _, _ ->
                startActivity(Intent(this, LoginActivity::class.java))
            }
            .show()
    }

    /** 已登录时选择档位：弹窗显示档位详情，确认后切换选中；点击已选档位也能查看 */
    private fun showTierConfirm(
        plan: String,
        label: String,
        price: Double,
        cells: List<Triple<String, String, TextView>>
    ) {
        val priceText = if (price.isNaN()) "" else "¥${trimPrice(price)}"
        val days = when (plan) {
            "day1" -> 1
            "month1" -> 30
            "month3" -> 90
            "month6" -> 180
            "year1" -> 365
            else -> -1
        }
        val ruleText = if (days > 0) getString(R.string.pay_rule, days)
            else "会员永久有效，到期无需续费"
        val selected = plan == selectedPlan
        val message = buildString {
            append("档位：$label\n")
            if (priceText.isNotBlank()) append("价格：$priceText\n")
            append('\n').append(ruleText)
            if (selected) append("\n\n（当前已选档位）")
        }
        android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.pay_tier_title)
            .setMessage(message)
            .setPositiveButton(if (selected) "知道了" else "确定选择") { _, _ ->
                if (!selected) selectPlan(plan, cells)
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    /** 更新应付金额、支付方式高亮与扫码提示 */
    private fun updateSummary() {
        val price = prices.optDouble(selectedPlan, Double.NaN)
        amountText.text = if (price.isNaN()) "" else getString(R.string.pay_amount_due, trimPrice(price))
        wechatBtn.isSelected = method == "wechat"
        alipayBtn.isSelected = method == "alipay"
        qrTip.text = if (price.isNaN()) "" else getString(
            R.string.pay_qr_tip,
            getString(if (method == "wechat") R.string.pay_method_wechat else R.string.pay_method_alipay),
            trimPrice(price)
        )
    }

    /** 显示当前支付方式对应的收款码：云端 URL 优先，其次内置图片 */
    private fun renderQr() {
        val url = if (method == "wechat") {
            LicenseManager.status.payQrWechat.ifBlank { LicenseManager.status.payQr }
        } else {
            LicenseManager.status.payQrAlipay.ifBlank { LicenseManager.status.payQr }
        }
        val localId = resources.getIdentifier(
            if (method == "wechat") "pay_qr_wechat" else "pay_qr_alipay",
            "drawable",
            packageName
        )

        if (url.isNotBlank()) {
            qrHint.visibility = View.GONE
            qrImage.visibility = View.VISIBLE
            Thread {
                val bmp = try {
                    java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    if (bmp != null) {
                        qrImage.setImageBitmap(bmp)
                    } else {
                        qrImage.visibility = View.GONE
                        qrHint.visibility = View.VISIBLE
                    }
                }
            }.start()
        } else if (localId != 0) {
            qrImage.setImageResource(localId)
            qrImage.visibility = View.VISIBLE
            qrHint.visibility = View.GONE
        } else {
            qrImage.visibility = View.GONE
            qrHint.visibility = View.VISIBLE
        }
    }

    /** 提交订单：云端记录待确认订单，作者确认收款后自动开通 */
    private fun submitOrder() {
        if (orderId != null) {
            // 已提交过，手动刷新一次状态
            orderHandler.removeCallbacks(orderPoller)
            orderHandler.post(orderPoller)
            return
        }
        orderBtn.isEnabled = false
        orderStatusText.text = getString(R.string.login_working)
        CloudApi.post(
            JSONObject()
                .put("action", "create_order")
                .put("deviceId", DeviceInfo.uuid)
                .put("plan", selectedPlan)
                .put("method", method)
                .apply {
                    FreeCastApp.username(this@PaymentActivity)?.let { put("username", it) }
                    put("deviceModel", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
                }
        ) { json ->
            orderBtn.isEnabled = true
            val id = json?.optString("orderId").orEmpty()
            if (json?.optBoolean("ok") == true && id.isNotEmpty()) {
                orderId = id
                orderStatusText.text = getString(
                    R.string.pay_order_created,
                    id,
                    trimPrice(json.optDouble("amount", 0.0))
                )
                orderHandler.removeCallbacks(orderPoller)
                orderHandler.postDelayed(orderPoller, 3000L)
            } else {
                orderStatusText.text = getString(R.string.pay_order_unavailable)
            }
        }
    }

    override fun onDestroy() {
        orderHandler.removeCallbacks(orderPoller)
        super.onDestroy()
    }

    private fun activate() {
        val key = keyInput.text.toString().trim().uppercase()
        if (key.length < 6) {
            keyInput.error = getString(R.string.activation_error_format)
            return
        }
        submit.isEnabled = false
        statusText.text = getString(R.string.login_working)
        CloudApi.post(
            JSONObject()
                .put("action", "activate")
                .put("key", key)
                .put("deviceId", DeviceInfo.uuid)
        ) { json ->
            when {
                json?.optBoolean("ok") == true -> {
                    submit.isEnabled = true
                    val plan = LicenseManager.planLabel(json.optString("plan"))
                    Toast.makeText(this, getString(R.string.pay_success, plan), Toast.LENGTH_LONG).show()
                    LicenseManager.refresh { finish() }
                }

                json == null -> {
                    submit.isEnabled = true
                    statusText.text = getString(R.string.activation_error_network)
                }

                json?.optString("error") == "used" -> {
                    submit.isEnabled = true
                    statusText.text = getString(R.string.activation_error_used)
                }

                // 不是激活码：可能是好友的邀请码，尝试兑换奖励
                else -> redeemInvite(key)
            }
        }
    }

    /** 激活码栏填写邀请码：登录状态下兑换邀请奖励，邀请人与当前账号各得 3 天 */
    private fun redeemInvite(code: String) {
        val token = FreeCastApp.sessionToken(this)
        if (token.isNullOrBlank()) {
            submit.isEnabled = true
            statusText.text = getString(R.string.pay_invite_need_login)
            return
        }
        CloudApi.post(
            JSONObject()
                .put("action", "redeem_invite")
                .put("token", token)
                .put("code", code)
                .put("deviceId", DeviceInfo.uuid)
                .put("androidId", androidId())
                .put("deviceBrand", Build.BRAND ?: "")
                .put("deviceModel", Build.MODEL ?: "")
                .put("deviceFingerprint", Build.FINGERPRINT ?: "")
        ) { json ->
            submit.isEnabled = true
            if (json?.optBoolean("ok") == true) {
                Toast.makeText(this, R.string.pay_invite_reward_ok, Toast.LENGTH_LONG).show()
                LicenseManager.refresh { finish() }
            } else {
                statusText.text = when (json?.optString("error")) {
                    "unauthorized" -> getString(R.string.pay_invite_need_login)
                    else -> getString(R.string.pay_invite_reward_fail)
                }
            }
        }
    }

    private fun androidId(): String = try {
        android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun trimPrice(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
