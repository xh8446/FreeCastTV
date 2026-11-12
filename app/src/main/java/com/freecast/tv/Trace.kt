package com.freecast.tv

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动追踪日志：每一步都落到 /data/data/<pkg>/cache/trace.log，
 * 即使 Activity 在 setContentView / findViewById 阶段就崩了，
 * 下次启动仍能从上一次的最后一条日志判断崩溃点。
 *
 * 用法：Trace.log("阶段名", "做了什么")
 *       Trace.error("阶段名", throwable)
 */
object Trace {

    private const val TAG = "FreeCastTrace"
    private const val MAX_BYTES = 512 * 1024 // 512KB 上限，避免撑爆

    @Volatile
    private var file: File? = null

    @Volatile
    private var enabled = true

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 在 Application.onCreate 尽早调用 */
    fun init(context: Context) {
        if (file != null) return
        try {
            val dir = context.cacheDir ?: return
            file = File(dir, "trace.log")
            // 启动时先清一下（保留上次最后 2KB 方便看到 crash）
            val f = file!!
            if (f.exists() && f.length() > 1024) {
                val text = f.readText()
                val lastK = text.takeLast(1024)
                f.writeText("=== previous boot tail ===\n$lastK\n=== new boot ${Date()} ===\n")
            }
            log("BOOT", "========================================")
            log("BOOT", "pid=${Process.myPid()} thread=${Thread.currentThread().name}")
            log("BOOT", "device=${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
            log("BOOT", "appPkg=${context.packageName}")
        } catch (_: Exception) {
            enabled = false
        }
    }

    fun log(stage: String, msg: String) {
        if (!enabled) return
        val line = "${timeFmt.format(Date())} [$stage] $msg"
        Log.d(TAG, line)
        try {
            val f = file ?: return
            FileWriter(f, true).use { it.write(line + "\n") }
            // 超过上限就截断头部
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.takeLast(MAX_BYTES / 2))
            }
        } catch (_: Exception) {
            enabled = false
        }
    }

    fun error(stage: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        log(stage, "ERROR ${throwable.javaClass.simpleName}: ${throwable.message}")
        log(stage, sw.toString())
    }

    fun readAll(context: Context): String? {
        return try {
            val f = File(context.cacheDir, "trace.log")
            if (!f.exists()) null else f.readText().takeLast(8000)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            File(context.cacheDir, "trace.log").delete()
        } catch (_: Exception) {
        }
    }
}
