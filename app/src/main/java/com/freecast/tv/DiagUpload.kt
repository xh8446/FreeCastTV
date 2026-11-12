package com.freecast.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 启动后把本机 trace.log 尾部上报到服务端（admin_notify 表，type=dev_log），
 * 用于远程排查 TV 黑屏等问题：作者用 admin_device_logs 接口即可查看。
 * 每小时最多一次，失败静默，绝不影响正常使用。
 */
object DiagUpload {

    private const val COOLDOWN_MS = 60 * 60 * 1000L

    @Volatile
    private var lastAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    fun maybeUpload(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAt < COOLDOWN_MS) return
        lastAt = now
        // 延迟几秒：等首屏日志写完、避免和启动期网络请求抢带宽
        handler.postDelayed({
            Thread {
                try {
                    val appCtx = ctx.applicationContext
                    val log = Trace.readAll(appCtx) ?: return@Thread
                    val ver = try {
                        appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: ""
                    } catch (_: Throwable) { "" }
                    CloudApi.post(
                        JSONObject()
                            .put("action", "report_log")
                            .put("deviceId", DeviceInfo.uuid)
                            .put("flavor", AppMode.flavor(appCtx))
                            .put("ver", ver)
                            .put("log", log.takeLast(7000))
                    ) { }
                } catch (_: Throwable) {
                }
            }.start()
        }, 3000L)
    }
}
