package com.freecast.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.freecast.tv.push.PushServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/** 远程推送：手机浏览器扫码上传文件，本机列表查看进度并打开/删除。 */
class PushActivity : Activity() {

    private lateinit var qrImage: ImageView
    private lateinit var urlText: TextView
    private lateinit var ipsText: TextView
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var adapter: Adapter
    private val handler = Handler(Looper.getMainLooper())
    private var items: List<PushServer.Item> = emptyList()
    private var altText = ""
    private lateinit var backBtn: Button

    private var lastSignature = ""

    /** 二维码只在 URL 变化时重渲染，避免 onResume 频繁闪烁 */
    private var lastQrUrl: String? = null
    private var cachedQrBitmap: Bitmap? = null

    private val refresh = object : Runnable {
        override fun run() {
            items = PushServer.items()
            // 仅在文件列表结构变化时重建列表，避免每 0.5 秒刷新导致遥控器焦点被重置
            val signature = items.joinToString("|") { "${it.name}:${it.active}:${it.sizeBytes}" }
            if (signature != lastSignature) {
                lastSignature = signature
                adapter.notifyDataSetChanged()
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } else {
                refreshVisibleRows()
            }
            val event = PushServer.lastEvent
            ipsText.text = if (event.isEmpty()) altText else "$altText\n最近活动：$event"
            if (currentFocus == null) ensureFocus()
            handler.postDelayed(this, 500L)
        }
    }

    /** 不重建列表，仅就地刷新可见行的进度文字 */
    private fun refreshVisibleRows() {
        for (i in 0 until list.childCount) {
            val position = list.firstVisiblePosition + i
            if (position in items.indices) {
                adapter.getView(position, list.getChildAt(i), list)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_push)
        AppMode.applyStatusBarTopPadding(this)

        qrImage = findViewById(R.id.push_qr)
        urlText = findViewById(R.id.push_url)
        ipsText = findViewById(R.id.push_ips)
        list = findViewById(R.id.push_list)
        emptyText = findViewById(R.id.push_empty)
        adapter = Adapter()
        list.adapter = adapter
        // 遥控器焦点直接落在每行内的「打开」/「删除」按钮
        list.itemsCanFocus = true
        backBtn = findViewById(R.id.push_back)
        backBtn.setOnClickListener { finish() }

        applyAdaptiveLayout()
    }

    /** 手机竖屏改为上下布局，二维码按可用空间缩放，避免显示不全 */
    private fun applyAdaptiveLayout() {
        try {
            val dm = resources.displayMetrics
            val portrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            val root = findViewById<LinearLayout>(R.id.push_body)
            val left = findViewById<LinearLayout>(R.id.push_left)
            val right = findViewById<LinearLayout>(R.id.push_right)
            if (portrait) {
                // 竖屏：二维码区块在上，文件列表在下，否则列表宽度会算成 0 而看不到文件
                root.orientation = LinearLayout.VERTICAL
                left.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                right.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                left.setPadding(dp(16), dp(12), dp(16), dp(8))
            } else {
                root.orientation = LinearLayout.HORIZONTAL
                left.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                )
                right.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1.2f
                )
            }
            val availW = if (portrait) {
                dm.widthPixels - dp(64)
            } else {
                dm.widthPixels / 2 - dp(96)
            }
            val availH = if (portrait) {
                (dm.heightPixels * 0.38f).toInt()
            } else {
                (dm.heightPixels * 0.52f).toInt()
            }
            val size = minOf(availW, availH, dp(260)).coerceAtLeast(dp(132))
            qrImage.layoutParams = qrImage.layoutParams.apply {
                width = size
                height = size
            }
            // 小屏手机上适当缩小说明与网址字号
            if (resources.configuration.screenWidthDp < 360) {
                urlText.textSize = 15f
            }
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        val started = PushServer.start(applicationContext)
        if (!started) {
            Toast.makeText(this, R.string.push_server_down, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        renderQr()
        items = PushServer.items()
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        handler.post(refresh)
        // 有文件时把焦点交给「打开」按钮，遥控器可直接操作；没有则落在返回按钮
        handler.postDelayed({ ensureFocus() }, 350L)
    }

    /** 列表为空或无焦点时，把遥控器焦点放到首个「打开」按钮上 */
    private fun ensureFocus(attempt: Int = 0) {
        if (currentFocus != null) return
        if (items.isEmpty()) {
            backBtn.requestFocus()
            return
        }
        list.setSelection(0)
        val row = list.getChildAt(0)
        val target = row?.findViewById<Button>(R.id.item_open)
            ?: row?.findViewById<Button>(R.id.item_delete)
        if (target != null && target.visibility == View.VISIBLE) {
            target.requestFocus()
        } else if (attempt < 6) {
            // 列表尚未完成布局时稍后重试，避免焦点直接落到返回按钮
            handler.postDelayed({ ensureFocus(attempt + 1) }, 100L)
        } else {
            backBtn.requestFocus()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        // 不再在这里关闭服务：切换界面后端口保持不变，上传也不会中断
        super.onPause()
    }

    private fun renderQr() {
        val ip = PushServer.localIp()
        if (ip.isEmpty()) {
            urlText.setText(R.string.push_server_down)
            lastQrUrl = null
            cachedQrBitmap = null
            qrImage.setImageBitmap(null)
            return
        }
        val url = "http://$ip:${PushServer.port}/"
        urlText.text = url
        // 有多块网卡时列出备用地址，主地址不通可手动换用
        val alts = DeviceInfo.ips.filter { it != ip }
        altText = if (alts.isEmpty()) "" else
            "备用地址：" + alts.joinToString("　") { "http://$it:${PushServer.port}/" }
        ipsText.text = altText
        // 端口/IP 未变，复用缓存
        if (url == lastQrUrl && cachedQrBitmap != null) {
            qrImage.setImageBitmap(cachedQrBitmap)
            return
        }
        lastQrUrl = url
        try {
            val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512)
            val bmp = Bitmap.createBitmap(bits.width, bits.height, Bitmap.Config.RGB_565)
            val white = Color.WHITE
            val black = Color.BLACK
            val pixels = IntArray(bits.width * bits.height)
            for (y in 0 until bits.height) {
                for (x in 0 until bits.width) {
                    pixels[y * bits.width + x] = if (bits.get(x, y)) black else white
                }
            }
            bmp.setPixels(pixels, 0, bits.width, 0, 0, bits.width, bits.height)
            cachedQrBitmap = bmp
            qrImage.setImageBitmap(bmp)
        } catch (_: Exception) {
            qrImage.visibility = View.GONE
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_push, parent, false)
            val item = items[position]
            val name = view.findViewById<TextView>(R.id.item_name)
            val progress = view.findViewById<ProgressBar>(R.id.item_progress)
            val status = view.findViewById<TextView>(R.id.item_status)
            val open = view.findViewById<Button>(R.id.item_open)
            val delete = view.findViewById<Button>(R.id.item_delete)

            name.text = item.name
            // 遥控器：整行可聚焦，确认键打开、长按删除
            view.setOnClickListener { if (!item.active) openFile(item.file) }
            view.setOnLongClickListener {
                if (!item.active) {
                    confirmDelete(item)
                    true
                } else false
            }
            if (item.active) {
                progress.visibility = View.VISIBLE
                val pct = if (item.total > 0) (item.received * 100 / item.total).toInt() else 0
                progress.progress = pct
                val speed = if (item.elapsedMs > 0) item.received * 1000 / item.elapsedMs else 0
                val remain = if (speed > 0 && item.total > 0) {
                    ((item.total - item.received) / speed).toInt()
                } else {
                    -1
                }
                status.text = getString(
                    R.string.push_receiving_detail,
                    pct,
                    formatSize(item.received),
                    if (item.total > 0) formatSize(item.total) else "--",
                    formatSize(speed) + "/s",
                    formatRemain(remain)
                )
                open.visibility = View.GONE
                delete.visibility = View.GONE
            } else {
                progress.visibility = View.GONE
                status.text = getString(R.string.push_received, formatSize(item.sizeBytes))
                open.visibility = View.VISIBLE
                delete.visibility = View.VISIBLE
                open.setOnClickListener { openFile(item.file) }
                delete.setOnClickListener {
                    confirmDelete(item)
                }
            }
            // 放在设置点击监听之后：确保整行不会抢占遥控器焦点，
            // 焦点只在「打开」「删除」按钮之间移动（触屏点击仍可用）
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            return view
        }
    }

    /** 遥控器/触屏通用的删除确认，避免长按误删 */
    private fun confirmDelete(item: PushServer.Item) {
        android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.push_delete)
            .setMessage(item.name)
            .setPositiveButton(R.string.push_delete) { _, _ ->
                item.file?.delete()
                items = PushServer.items()
                adapter.notifyDataSetChanged()
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun openFile(file: File?) {
        if (file == null || !file.exists()) return
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

        // APK 文件特殊处理：走系统安装流程
        if (file.extension.equals("apk", ignoreCase = true)) {
            openApk(file)
            return
        }

        // 依次尝试：精确 mime → 通配 mime → application/octet-stream
        val mime = mimeOf(file)
        val fallbacks = listOf(mime, baseMime(mime), "*/*")

        for (m in fallbacks) {
            if (tryOpenWithMime(uri, m)) return
        }
        // 最后尝试 ACTION_SEND 分享，总能找个应用
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (share.resolveActivity(packageManager) != null) {
            startActivity(Intent.createChooser(share, "选择应用打开"))
        } else {
            val path = file.absolutePath
            Toast.makeText(
                this,
                "没有可打开此文件的应用，文件已保存到 $path",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun tryOpenWithMime(uri: Uri, mime: String): Boolean {
        if (mime.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /** 把 "video/*" 保留，"application/pdf" 保留；其余返回 "*/*" */
    private fun baseMime(mime: String): String {
        if (mime.endsWith("/*")) return mime
        val slash = mime.indexOf('/')
        if (slash > 0) return mime.substring(0, slash + 1) + "*"
        return mime
    }

    private fun openApk(file: File) {
        // Android 8+ 未允许「安装未知应用」时，系统会拦截安装，先引导用户去开启
        if (!ApkInstaller.canInstall(this)) {
            android.app.AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.apk_allow_title)
                .setMessage(R.string.apk_allow_message)
                .setPositiveButton(R.string.apk_allow_go) { _, _ ->
                    ApkInstaller.openUnknownSourcesSettings(this)
                }
                .setNegativeButton(R.string.settings_close, null)
                .show()
            return
        }
        // 依次尝试系统 PackageInstaller / 厂商安装器，并按实际错误提示
        val error = ApkInstaller.install(this, file)
        if (error != null) {
            Toast.makeText(this, getString(R.string.install_failed_detail, error), Toast.LENGTH_LONG).show()
        }
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts", "flv" -> "video/*"
        "mp3", "aac", "flac", "wav", "ogg", "m4a" -> "audio/*"
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image/*"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    private fun formatRemain(sec: Int): String {
        if (sec < 0) return "--"
        if (sec < 60) return "${sec}s"
        return "${sec / 60}m${sec % 60}s"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(java.util.Locale.US, "%.2fGB", gb)
            mb >= 1 -> String.format(java.util.Locale.US, "%.1fMB", mb)
            else -> String.format(java.util.Locale.US, "%.0fKB", kb)
        }
    }
}
