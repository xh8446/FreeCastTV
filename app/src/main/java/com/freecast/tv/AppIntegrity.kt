package com.freecast.tv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.security.MessageDigest

/**
 * 安装包完整性校验：
 * 1. 签名证书必须与正式证书一致（被二次打包/重签名则拒绝运行）
 * 2. 检测调试器 / ptrace 附加
 * 3. 校验失败直接结束进程，避免被动态修改、替换代码后继续使用
 *
 * 说明：这是提高破解成本的防护，不能做到绝对不可破解。
 */
object AppIntegrity {

    /** 正式签名证书 SHA-256（keystore/freecast.jks, alias freecast） */
    private const val CERT_SHA256 =
        "7C36C81270E23B3934BC6793B4E221B2F8608EF97328805047907778154EEC94"

    fun verify(context: Context): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return false
        if (isTracerAttached()) return false
        return certMatches(context)
    }

    private fun certMatches(context: Context): Boolean = try {
        val pm = context.packageManager
        val pkg = context.packageName
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        }
        val bytes = signer?.toByteArray() ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        digest.joinToString("") { "%02X".format(it) } == CERT_SHA256
    } catch (_: Throwable) {
        false
    }

    /** /proc/self/status 里 TracerPid 非 0 说明被调试器/ptrace 挂载 */
    private fun isTracerAttached(): Boolean = try {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("TracerPid:") }
        (line?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0) > 0
    } catch (_: Throwable) {
        false
    }
}
