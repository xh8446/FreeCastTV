package com.freecast.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 许可/试用状态（全部以云端为准，重装、改时间都无法绕过）：
 * - 未激活设备：试用 5 次投屏，每次最多 20 分钟，累计最多 100 分钟
 * - 激活后：按会员档位计时，到期时间由服务器计算
 */
object LicenseManager {

    /** 单次试用投屏上限：20 分钟 */
    const val PER_CAST_SECONDS = 20 * 60

    /** 云端未下发价格时的默认价目 */
    const val DEFAULT_PRICES =
        """{"day1":3,"month1":6,"month3":15,"month6":30,"year1":50,"forever":99}"""

    data class Status(
        val loaded: Boolean = false,
        val memberActive: Boolean = false,
        val plan: String = "",
        val expiresAt: String = "",
        val daysLeft: Int = -1,
        val castsLeft: Int = 5,
        val secondsLeft: Int = 6000,
        val castsMax: Int = 5,
        val secondsMax: Int = 6000,
        val perCastSeconds: Int = PER_CAST_SECONDS,
        val prices: String = "",
        val payQr: String = "",
        val payQrWechat: String = "",
        val payQrAlipay: String = "",
        val licenseMaxDays: Int = 400,
        /** 是否处于装机后 5 天免费期（无水印、不限时长、每天 5 次） */
        val trialFree: Boolean = false,
        val freeDaysLeft: Int = 0,
        val todayLeft: Int = 5,
        val todayMax: Int = 5,
    )

    @Volatile
    var status = Status()
        private set

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // 调试期：所有设备一律重置为未激活试用状态
        if (!BuildFlags.PAYMENT_ENABLED) {
            status = Status(loaded = true, memberActive = false)
        }
    }

    /** 实际使用的价目：云端六档齐全时优先，否则用内置的官方价目 */
    val effectivePrices: String
        get() {
            val p = status.prices
            if (p.isBlank()) return DEFAULT_PRICES
            val o = try {
                JSONObject(p)
            } catch (_: Exception) {
                return DEFAULT_PRICES
            }
            val complete = planOrder.all { o.optDouble(it.first, Double.NaN).let { v -> !v.isNaN() } }
            return if (complete) p else DEFAULT_PRICES
        }

    private val handler = Handler(Looper.getMainLooper())

    val planOrder = listOf(
        "day1" to "1 天",
        "month1" to "1 个月",
        "month3" to "3 个月",
        "month6" to "6 个月",
        "year1" to "12 个月",
        "forever" to "永久",
    )

    fun planLabel(plan: String): String =
        planOrder.firstOrNull { it.first == plan }?.second ?: plan

    fun refresh(done: (() -> Unit)? = null) {
        // 调试期不查云端会员状态，始终保持未激活试用
        if (!BuildFlags.PAYMENT_ENABLED) {
            status = Status(loaded = true, memberActive = false)
            done?.let { handler.post(it) }
            return
        }
        val req = JSONObject().put("action", "license").put("deviceId", DeviceInfo.uuid)
        // 会员绑定账号：重装/换设备后，云端凭账号找回已购会员
        appContext?.let { c ->
            FreeCastApp.username(c)?.let { req.put("username", it) }
            FreeCastApp.sessionToken(c)?.let { req.put("token", it) }
        }
        CloudApi.post(req) { json ->
            if (json?.optBoolean("ok") == true) {
                val m = json.optJSONObject("member")
                val t = json.optJSONObject("trial")
                // 额度以产品规则为准（5 次 / 100 分钟），用量计数仍以云端为准
                val castsMax = maxOf(t?.optInt("castsMax", 0) ?: 0, 5)
                val secondsMax = maxOf(t?.optInt("secondsMax", 0) ?: 0, 6000)
                val usedCasts = t?.optInt("castsUsed", -1) ?: -1
                val usedSeconds = t?.optInt("secondsUsed", -1) ?: -1
                status = Status(
                    loaded = true,
                    memberActive = m?.optBoolean("active") == true,
                    plan = m?.optString("plan") ?: "",
                    expiresAt = m?.optString("expiresAt") ?: "",
                    daysLeft = m?.optInt("daysLeft", -1) ?: -1,
                    castsLeft = if (usedCasts >= 0) (castsMax - usedCasts).coerceAtLeast(0)
                    else (t?.optInt("castsLeft", castsMax) ?: castsMax),
                    secondsLeft = if (usedSeconds >= 0) (secondsMax - usedSeconds).coerceAtLeast(0)
                    else (t?.optInt("secondsLeft", secondsMax) ?: secondsMax),
                    castsMax = castsMax,
                    secondsMax = secondsMax,
                    perCastSeconds = PER_CAST_SECONDS,
                    prices = json.optString("prices"),
                    payQr = json.optString("payQr"),
                    payQrWechat = json.optString("payQrWechat"),
                    payQrAlipay = json.optString("payQrAlipay"),
                    licenseMaxDays = json.optInt("licenseMaxDays", 400),
                    trialFree = t?.optBoolean("free") == true,
                    freeDaysLeft = t?.optInt("freeDaysLeft", 0) ?: 0,
                    todayLeft = t?.optInt("todayLeft", 5) ?: 5,
                    todayMax = t?.optInt("todayMax", 5) ?: 5,
                )
                appContext?.let { FreeCastApp.setMemberCached(it, status.memberActive) }
            }
            done?.let { handler.post(it) }
        }
    }

    /**
     * 一次投屏开始：
     * - 5 天免费期内：云端校验当天次数，超限回调 onDailyLimit（无水印、不限时长）
     * - 免费期结束后：按旧规则（5 次 / 100 分钟）扣减，超限回调 onBlocked
     */
    fun onCastStarted(onBlocked: () -> Unit, onDailyLimit: (() -> Unit)? = null) {
        if (status.memberActive) return
        // 调试期只在本地扣减，不写云端
        if (!BuildFlags.PAYMENT_ENABLED) {
            status = status.copy(castsLeft = (status.castsLeft - 1).coerceAtLeast(0))
            if (status.castsLeft <= 0 || status.secondsLeft <= 0) {
                handler.post(onBlocked)
            }
            return
        }
        CloudApi.post(
            JSONObject().put("action", "trial_use").put("deviceId", DeviceInfo.uuid)
        ) { json ->
            if (json?.optBoolean("ok") != true) return@post
            if (json.optBoolean("member")) {
                status = status.copy(memberActive = true)
                return@post
            }
            val blocked = json.optBoolean("blocked")
            if (json.optBoolean("free")) {
                status = status.copy(
                    trialFree = true,
                    todayLeft = json.optInt("todayLeft", status.todayLeft),
                    freeDaysLeft = json.optInt("freeDaysLeft", status.freeDaysLeft),
                )
                if (blocked) handler.post { onDailyLimit?.invoke() ?: onBlocked() }
                return@post
            }
            // 免费期已结束，转入旧试用规则
            status = status.copy(
                trialFree = false,
                castsLeft = json.optInt("castsLeft", status.castsLeft),
                secondsLeft = json.optInt("secondsLeft", status.secondsLeft),
            )
            if (blocked) handler.post(onBlocked)
        }
    }

    /** 试用期播放时长上报（秒），服务端累计；免费期内不上报 */
    fun reportTime(seconds: Int) {
        if (status.memberActive || status.trialFree) return
        if (!BuildFlags.PAYMENT_ENABLED) {
            status = status.copy(secondsLeft = (status.secondsLeft - seconds).coerceAtLeast(0))
            return
        }
        CloudApi.post(
            JSONObject()
                .put("action", "trial_time")
                .put("deviceId", DeviceInfo.uuid)
                .put("seconds", seconds)
        ) { json ->
            if (json?.optBoolean("ok") == true && !json.optBoolean("member") && !json.optBoolean("free")) {
                status = status.copy(secondsLeft = (status.secondsLeft - seconds).coerceAtLeast(0))
            }
        }
    }
}
