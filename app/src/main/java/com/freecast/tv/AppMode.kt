package com.freecast.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/**
 * 运行时判断当前设备是 TV 还是触屏。
 *
 * 判定规则（任一命中即为 TV）：
 * 1. 声明 Leanback 能力（Google TV / Android TV 认证设备）
 * 2. UiModeManager 返回 TELEVISION（绝大多数电视盒子都成立）
 * 3. 没有声明触摸屏（盒子/车机常见形态）
 *
 * 首次判定结果持久化，避免不同 Context 下结果漂移。
 */
object AppMode {
    private const val PREFS = "app_mode"
    private const val KEY_IS_TV = "is_tv"

    fun isTv(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_IS_TV)) return prefs.getBoolean(KEY_IS_TV, false)

        val pm = ctx.packageManager
        val hasLeanback = try {
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        } catch (_: Throwable) { false }
        val hasTouch = try {
            pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        } catch (_: Throwable) { false }
        val uiTv = try {
            val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Throwable) { false }

        // TV 判定：Leanback 或系统 UI 模式为电视；无触摸屏作为兜底
        val isTvDevice = hasLeanback || uiTv || !hasTouch
        prefs.edit().putBoolean(KEY_IS_TV, isTvDevice).apply()
        Trace.log("AppMode", "detected isTv=$isTvDevice leanback=$hasLeanback uiTv=$uiTv touch=$hasTouch brand=${Build.BRAND} model=${Build.MODEL}")
        return isTvDevice
    }

    /** 用户在设置里手动切换模式（部分盒子判定失败时的逃生通道） */
    fun setTvManually(ctx: Context, tv: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IS_TV, tv).apply()
    }

    fun flavor(ctx: Context): String = if (isTv(ctx)) "tv" else "touch"

    val isEmulator: Boolean by lazy {
        (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("SDK") || Build.MODEL.contains("sdk_gphone"))
    }
}
