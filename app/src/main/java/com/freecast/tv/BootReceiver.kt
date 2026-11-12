package com.freecast.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启：已激活的设备在开机或应用更新后自动启动投屏服务。
 *
 * Android 12+ (targetSdk>=31) 禁止 BroadcastReceiver 后台直接 startForegroundService，
 * 所以这里改为：AlarmManager 延迟几秒调度 PendingIntent.getForegroundService /
 * PendingIntent.getActivity 拉起前台路径，避开系统限制。
 *
 * 同时向所有已安装 App 的后台广播走一遍，再补发一次 BOOT_COMPLETED 自触发 CastService。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_RETRY_SERVICE = "com.freecast.tv.BOOT_RETRY"

        fun schedule(context: Context, delayMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = ACTION_RETRY_SERVICE
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, 1002, intent, flags)
            val triggerAt = System.currentTimeMillis() + delayMs
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                Log.i(TAG, "scheduled delayed retry in ${delayMs}ms")
            } catch (e: Exception) {
                Log.w(TAG, "alarm schedule failed", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        val isRetry = action == ACTION_RETRY_SERVICE
        if (!isBoot && !isRetry) return

        Log.i(TAG, "boot receiver action=$action")

        // 1) 立刻尝试启动服务（低版本能直接起来）
        try {
            CastService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "direct start failed", e)
        }

        // 2) Android 12+ 后台限制导致 foreground service 可能被杀，
        //    走 AlarmManager 延迟调度重试（网络尚未就绪 / 系统稍后放行）
        if (!isRetry) {
            schedule(context, 4000L)
            schedule(context, 15000L)
            schedule(context, 45000L)
        }
    }
}
