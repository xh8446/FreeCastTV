package com.freecast.tv

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** APK 安装：先校验并复制到内部缓存，再显式拉起系统安装器，PackageInstaller 兜底。 */
object ApkInstaller {

    private const val APK_MIME = "application/vnd.android.package-archive"

    fun canInstall(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        } catch (_: Throwable) {}
    }

    /** 返回 null 表示已交给系统安装流程；否则返回可直接展示给用户的原因。 */
    fun install(activity: Activity, file: File): String? {
        if (!canInstall(activity)) return "未授予安装未知应用权限"
        if (!file.isFile || file.length() <= 0L) return "安装包不存在或为空"

        // 推送目录在应用专属外部存储，部分电视/车机的系统安装器读不到；
        // 统一复制到与应用内更新相同的 cache/update，并使用纯 ASCII 文件名。
        val staged = try {
            stageApk(activity, file)
        } catch (t: Throwable) {
            Trace.error("ApkInstaller", t)
            return "准备安装包失败：${t.message ?: t.javaClass.simpleName}"
        }

        // 先让 PackageManager 解析一次。上传损坏时直接报真实原因，不再假装已启动安装。
        try {
            val info = activity.packageManager.getPackageArchiveInfo(staged.absolutePath, 0)
            if (info == null) return "APK 文件无效或传输不完整（${file.length()} 字节）"
            Trace.log("ApkInstaller", "apk verified package=${info.packageName}, size=${staged.length()}")
        } catch (t: Throwable) {
            Trace.error("ApkInstaller", t)
            return "APK 校验失败：${t.message ?: t.javaClass.simpleName}"
        }

        val errors = StringBuilder()
        val attempts: List<Pair<String, () -> Unit>> = listOf(
            // 早期可安装版本使用 ACTION_INSTALL_PACKAGE 优先；VIEW 在部分设备会被第三方组件吞掉
            "install_package" to { installWithAction(activity, staged, Intent.ACTION_INSTALL_PACKAGE) },
            "view" to { installWithAction(activity, staged, Intent.ACTION_VIEW) },
            "public_view" to { installFromPublicDir(activity, staged) },
            "session_activity" to { installWithSession(activity, staged, useActivityCallback = true) },
            "session" to { installWithSession(activity, staged, useActivityCallback = false) }
        )
        for ((name, action) in attempts) {
            try {
                action()
                Trace.log("ApkInstaller", "install via $name, path=${staged.absolutePath}")
                Toast.makeText(activity, "正在启动安装…", Toast.LENGTH_SHORT).show()
                return null
            } catch (t: Throwable) {
                Trace.error("ApkInstaller", t)
                if (errors.isNotEmpty()) errors.append("; ")
                errors.append(name).append(": ").append(t.javaClass.simpleName)
                t.message?.let { errors.append("(").append(it.take(100)).append(")") }
            }
        }
        return errors.toString()
    }

    private fun stageApk(activity: Activity, source: File): File {
        val dir = File(activity.cacheDir, "update").apply { mkdirs() }
        val dest = File(dir, "install-${System.currentTimeMillis()}.apk")
        source.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (dest.length() != source.length()) {
            dest.delete()
            throw IllegalStateException("复制不完整 ${dest.length()}/${source.length()}")
        }
        return dest
    }

    /** 显式选择系统安装器，避免文件管理器/应用商店截获 Intent 后没有界面。 */
    private fun installWithAction(activity: Activity, file: File, action: String) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(action).apply {
            if (action == Intent.ACTION_INSTALL_PACKAGE) data = uri else setDataAndType(uri, APK_MIME)
            // 与早期可用版本一致：部分 TV 安装器需要独立任务才能显示到前台
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Intent.ACTION_INSTALL_PACKAGE) putExtra(Intent.EXTRA_RETURN_RESULT, false)
            clipData = ClipData.newRawUri("apk", uri)
        }
        activity.startActivity(resolveSystemInstaller(activity, intent))
    }

    private fun resolveSystemInstaller(activity: Activity, intent: Intent): Intent {
        // 查询结果只用于优先选择系统安装器；为空时仍允许隐式启动，
        // 因为 Android 11+ 的包可见性可能把真实安装器从查询结果中过滤掉。
        val handlers = activity.packageManager.queryIntentActivities(intent, 0)
        if (handlers.isEmpty()) return intent

        val knownPackages = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.oplus.packageinstaller",
            "com.coloros.packageinstaller",
            "com.vivo.packageinstaller",
            "com.huawei.packageinstaller",
            "com.hihonor.packageinstaller"
        )
        val target = handlers.firstOrNull { it.activityInfo.packageName in knownPackages }
            ?: handlers.firstOrNull {
                (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            }
            ?: handlers.first()
        intent.setClassName(target.activityInfo.packageName, target.activityInfo.name)
        Trace.log("ApkInstaller", "installer=${target.activityInfo.packageName}/${target.activityInfo.name}")
        return intent
    }

    /** 部分定制电视只接受公共 Downloads 中的 APK。 */
    private fun installFromPublicDir(activity: Activity, file: File) {
        val name = "freecast-install-${System.currentTimeMillis()}.apk"
        val uri: Uri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, APK_MIME)
            }
            val resolver = activity.contentResolver
            uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载目录安装包")
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("无法写入下载目录安装包")
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            uri = Uri.fromFile(dest)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("apk", uri)
        }
        activity.startActivity(resolveSystemInstaller(activity, intent))
    }

    /** PackageInstaller 兜底；安装状态由 Receiver/Activity 明确反馈。 */
    private fun installWithSession(activity: Activity, file: File, useActivityCallback: Boolean) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        output.flush()
                        session.fsync(output)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = Intent().apply {
                    setClass(
                        activity,
                        if (useActivityCallback) InstallLauncherActivity::class.java else InstallResultReceiver::class.java
                    )
                    putExtra(
                        if (useActivityCallback) InstallLauncherActivity.EXTRA_SESSION_ID else InstallResultReceiver.EXTRA_SESSION_ID,
                        sessionId
                    )
                }
                val pending = if (useActivityCallback) {
                    PendingIntent.getActivity(activity, sessionId, callback, flags)
                } else {
                    PendingIntent.getBroadcast(activity, sessionId, callback, flags)
                }
                session.commit(pending.intentSender)
                Trace.log("ApkInstaller", "session committed id=$sessionId activity=$useActivityCallback")
            }
        } catch (t: Throwable) {
            try { installer.abandonSession(sessionId) } catch (_: Throwable) {}
            throw t
        }
    }

    fun showSessionResult(context: Context, intent: Intent, sessionId: Int) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Trace.log("ApkInstaller", "session result id=$sessionId status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    Toast.makeText(context, "系统未提供安装确认界面", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "安装完成", Toast.LENGTH_SHORT).show()
            }
            else -> {
                if (sessionId >= 0) {
                    try { context.packageManager.packageInstaller.abandonSession(sessionId) } catch (_: Throwable) {}
                }
                Toast.makeText(context, "安装失败：${message ?: "状态码 $status"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    companion object { const val EXTRA_SESSION_ID = "session_id" }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, intent.getIntExtra(EXTRA_SESSION_ID, -1))
        try {
            ApkInstaller.showSessionResult(context, intent, id)
        } catch (t: Throwable) {
            Trace.error("InstallResultReceiver", t)
            if (id >= 0) {
                try { context.packageManager.packageInstaller.abandonSession(id) } catch (_: Throwable) {}
            }
            Toast.makeText(context, "安装失败：${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}

class InstallLauncherActivity : Activity() {
    companion object { const val EXTRA_SESSION_ID = "session_id" }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        try {
            ApkInstaller.showSessionResult(this, intent, id)
        } catch (t: Throwable) {
            Trace.error("InstallLauncherActivity", t)
            Toast.makeText(this, "安装界面启动失败：${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
