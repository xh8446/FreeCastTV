package com.freecast.tv

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class FreeCastApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 完整性校验：被重签名/二次打包/调试器附加时直接退出
        if (!AppIntegrity.verify(this)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    this, "检测到非官方安装包，已停止运行", android.widget.Toast.LENGTH_LONG
                ).show()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            return
        }

        // 全局崩溃处理器：任何线程的未捕获异常都先落盘
        installGlobalCrashHandler()

        Trace.init(this)
        Trace.log("FreeCastApp", "onCreate start")

        try {
            val prefs = getSharedPreferences("freecast", MODE_PRIVATE)
            Trace.log("FreeCastApp", "prefs opened")

            // v2 设备标识：本地没有 uuid_v2 时重新生成，等于把云端许可重置为「未激活试用」
            var uuid = prefs.getString("uuid_v2", null)
            if (uuid.isNullOrEmpty()) {
                Trace.log("FreeCastApp", "generating new uuid from ANDROID_ID")
                val androidId = try {
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                } catch (e: Exception) {
                    Trace.error("FreeCastApp", e)
                    ""
                }
                uuid = UUID.nameUUIDFromBytes("freecast-tv:v2:$androidId".toByteArray()).toString()
                prefs.edit()
                    .putString("uuid_v2", uuid)
                    .remove("activated")
                    .remove("member_cached")
                    .apply()
                Trace.log("FreeCastApp", "uuid generated: $uuid")
            } else {
                Trace.log("FreeCastApp", "existing uuid: $uuid")
            }
            DeviceInfo.uuid = uuid
            DeviceInfo.friendlyName = getString(R.string.device_name)
            Trace.log("FreeCastApp", "DeviceInfo set: friendlyName=${DeviceInfo.friendlyName}")
        } catch (e: Exception) {
            Trace.error("FreeCastApp", e)
            Log.e("FreeCastApp", "Application init failed", e)
        }

        // CastEngine 在 Application 启动期初始化 ExoPlayer / OkHttp，
        // 如果这里抛异常（极少数设备上的加密/内存问题）不要让整个 app 崩
        try {
            Trace.log("FreeCastApp", "about to call CastEngine.init")
            LicenseManager.init(this)
            CastEngine.init(this)
            Trace.log("FreeCastApp", "CastEngine.init returned OK")
        } catch (e: Throwable) {
            Trace.error("FreeCastApp", e)
            Log.e("FreeCastApp", "CastEngine.init FAILED", e)
        }

        Trace.log("FreeCastApp", "onCreate done")
    }

    /**
     * 把未捕获异常写入 crash.log，让下次启动 MainActivity 时能看到。
     */
    private fun installGlobalCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val msg = "[$time] ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}\n${sw}\n\n"
                Trace.error("UNCAUGHT", throwable)
                val dir = cacheDir
                if (dir != null) {
                    val file = File(dir, "crash.log")
                    FileWriter(file, true).use { it.write(msg) }
                }
            } catch (_: Exception) {
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val PREFS = "freecast"
        private const val KEY_ACTIVATED = "activated"
        private const val KEY_ENTERED = "entered"
        private const val KEY_USED = "used_keys"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_USERNAME = "session_username"
        private const val KEY_MEMBER = "member_cached"

        /** 本次进程内是否已通过登录页（直接试用或登录成功）。进程重启后归零。 */
        @Volatile
        var enteredThisSession = false

        /** 用户主动从播放页返回的时间戳：短时间内禁止「有新投屏就自动跳回播放页」，避免返回被弹回 */
        @Volatile
        var playerExitAt = 0L

        fun isActivated(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVATED, false)

        fun hasEntered(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENTERED, false) || isActivated(context)

        fun markEntered(context: Context) {
            enteredThisSession = true
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENTERED, true).apply()
        }

        /** 最近一次云端查询到的会员状态缓存，用于启动时决定是否先显示登录页 */
        fun isMemberCached(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MEMBER, false)

        fun setMemberCached(context: Context, active: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MEMBER, active).apply()
        }

        /** 未开通会员 / 试用中：每次冷启动都要先经过登录页 */
        fun needLoginOnLaunch(context: Context): Boolean =
            !isMemberCached(context) && !enteredThisSession

        fun sessionToken(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

        fun username(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }

        fun isLoggedIn(context: Context): Boolean = sessionToken(context) != null

        fun saveSession(context: Context, token: String, username: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USERNAME, username)
                .apply()
        }

        fun clearSession(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_TOKEN)
                .remove(KEY_USERNAME)
                .apply()
        }

        fun isKeyUsed(context: Context, key: String): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_USED, emptySet())?.contains(key) == true

        fun activate(context: Context, key: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val used = prefs.getStringSet(KEY_USED, emptySet())?.toMutableSet() ?: mutableSetOf()
            used.add(key)
            prefs.edit()
                .putBoolean(KEY_ACTIVATED, true)
                .putStringSet(KEY_USED, used)
                .apply()
        }

        fun readCrashLog(context: Context): String? {
            return try {
                val file = File(context.cacheDir, "crash.log")
                if (!file.exists()) return null
                val text = file.readText()
                file.delete()
                text.takeLast(4000)
            } catch (_: Exception) {
                null
            }
        }
    }
}
