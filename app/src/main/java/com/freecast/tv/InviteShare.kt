package com.freecast.tv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 分享免费投：取当前账号的邀请码与分享链接，弹窗展示二维码、保存分享图片、
 * 复制链接 / 分享给好友。好友扫码下载并注册或在激活码栏填写邀请码，双方各得 3 天。
 */
object InviteShare {

    private const val STORAGE_REQUEST = 1001

    fun show(activity: Activity, token: String?, onNeedLogin: (() -> Unit)? = null) {
        val tk = token?.trim().orEmpty()
        if (tk.isEmpty()) {
            AlertDialog.Builder(activity, R.style.DialogTheme)
                .setTitle(R.string.invite_share_title)
                .setMessage(R.string.invite_need_login)
                .setPositiveButton(R.string.invite_go_login) { _, _ ->
                    if (onNeedLogin != null) {
                        onNeedLogin()
                    } else {
                        activity.startActivity(Intent(activity, LoginActivity::class.java))
                    }
                }
                .setNegativeButton(R.string.settings_close, null)
                .show()
            return
        }

        Toast.makeText(activity, R.string.invite_loading, Toast.LENGTH_SHORT).show()
        CloudApi.post(
            JSONObject()
                .put("action", "invite_info")
                .put("token", tk)
                .put("deviceId", DeviceInfo.uuid)
        ) { json ->
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (json?.optBoolean("ok") != true) {
                Toast.makeText(activity, R.string.invite_load_failed, Toast.LENGTH_LONG).show()
                return@post
            }
            val code = json.optString("code")
            val url = json.optString("url")
            val invited = json.optInt("invited")
            val days = json.optInt("days")
            if (url.isBlank()) {
                Toast.makeText(activity, R.string.invite_load_failed, Toast.LENGTH_LONG).show()
                return@post
            }

            val view = activity.layoutInflater.inflate(R.layout.dialog_invite_share, null)
            val qrImage = view.findViewById<ImageView>(R.id.invite_qr)
            val bmp = qrBitmap(url, dp(activity, 220))
            if (bmp != null) {
                qrImage.setImageBitmap(bmp)
            } else {
                qrImage.visibility = View.GONE
            }
            view.findViewById<TextView>(R.id.invite_code).text =
                activity.getString(R.string.invite_code_label, code)
            view.findViewById<TextView>(R.id.invite_stats).text =
                activity.getString(R.string.invite_stats, invited, days)
            view.findViewById<TextView>(R.id.invite_link).text = url

            val saveBtn = view.findViewById<Button>(R.id.invite_save)
            val dialog = AlertDialog.Builder(activity, R.style.DialogTheme)
                .setView(view)
                .setNegativeButton(R.string.settings_close, null)
                .create()

            // 保存图片：生成含图标、名称、二维码、邀请码、奖励说明与下载链接的分享图
            saveBtn.setOnClickListener {
                if (Build.VERSION.SDK_INT in 23..28 && activity.checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        STORAGE_REQUEST
                    )
                    Toast.makeText(activity, R.string.invite_need_storage, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val card = buildShareCard(activity, url, code)
                if (card != null && saveBitmap(activity, card)) {
                    Toast.makeText(activity, R.string.invite_saved, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, R.string.invite_save_failed, Toast.LENGTH_LONG).show()
                }
            }
            view.findViewById<Button>(R.id.invite_copy).setOnClickListener {
                copy(activity, url)
                Toast.makeText(activity, R.string.invite_link_copied, Toast.LENGTH_SHORT).show()
            }
            view.findViewById<Button>(R.id.invite_share).setOnClickListener {
                shareText(activity, url, code)
            }

            dialog.show()
            // 遥控器默认焦点落在「保存图片」
            saveBtn.post { saveBtn.requestFocus() }
        }
    }

    fun copy(activity: Activity, text: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("invite", text))
        } catch (_: Throwable) {
        }
    }

    fun shareText(activity: Activity, url: String, code: String) {
        val text = activity.getString(R.string.invite_share_text, url, code)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.invite_share_title)))
        } catch (_: Throwable) {
        }
    }

    /** 二维码：白色底、黑色码点，适合直接展示或扫码 */
    fun qrBitmap(text: String, size: Int): Bitmap? = try {
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val w = bits.width
        val h = bits.height
        val white = Color.WHITE
        val black = Color.BLACK
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (bits.get(x, y)) black else white
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    } catch (_: Throwable) {
        null
    }

    /** 生成可保存/转发的分享海报：图标 + 软件名 + 二维码 + 邀请码 + 奖励说明 + 下载链接 */
    private fun buildShareCard(activity: Activity, url: String, code: String): Bitmap? {
        return try {
            val w = 900
            val h = 1420
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // 深蓝渐变背景
            paint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF0B1F3A.toInt(), 0xFF174A86.toInt()),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null

            // 软件图标 + 名称
            val icon = appIcon(activity)
            var textTop = 96f
            if (icon != null) {
                val size = 120
                val left = (w - size) / 2
                icon.setBounds(left, 70, left + size, 70 + size)
                icon.draw(canvas)
                textTop = 220f
            }
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 52f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(activity.getString(R.string.app_name), w / 2f, textTop + 46f, paint)

            // 二维码白底卡片
            val qrSize = 560
            val qrTop = 330f
            paint.color = Color.WHITE
            canvas.drawRoundRect(
                (w - qrSize) / 2f - 24f, qrTop - 24f,
                (w + qrSize) / 2f + 24f, qrTop + qrSize + 24f,
                24f, 24f, paint
            )
            qrBitmap(url, qrSize)?.let { qr ->
                canvas.drawBitmap(qr, ((w - qrSize) / 2f), qrTop, null)
            }

            // 邀请码
            paint.color = 0xFFFFD700.toInt()
            paint.textSize = 46f
            canvas.drawText(
                activity.getString(R.string.invite_card_code, code),
                w / 2f, qrTop + qrSize + 96f, paint
            )

            // 奖励说明
            paint.color = Color.WHITE
            paint.textSize = 32f
            paint.typeface = android.graphics.Typeface.DEFAULT
            val reward = activity.getString(R.string.invite_card_reward)
            var y = qrTop + qrSize + 158f
            for (line in reward.split("\n")) {
                canvas.drawText(line, w / 2f, y, paint)
                y += 46f
            }

            // 下载链接（手动换行，兼容 Android 5.0+）
            paint.color = 0xFFB8CCE8.toInt()
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER
            var remain = activity.getString(R.string.invite_card_link, url)
            val maxWidth = (w - 120).toFloat()
            var lineY = y + 36f
            while (remain.isNotEmpty() && lineY < h - 40f) {
                val count = paint.breakText(remain, true, maxWidth, null)
                if (count <= 0) break
                canvas.drawText(remain.substring(0, count), w / 2f, lineY, paint)
                remain = remain.substring(count)
                lineY += 34f
            }

            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun appIcon(activity: Activity): Drawable? = try {
        activity.packageManager.getApplicationIcon(activity.packageName)
    } catch (_: Throwable) {
        null
    }

    /** 保存到系统相册；Android 10+ 走 MediaStore，旧系统写入 Pictures 目录 */
    private fun saveBitmap(activity: Activity, bitmap: Bitmap): Boolean {
        val name = "freecast_invite_${System.currentTimeMillis()}.png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/FreeCast"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                val ok = resolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ok
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "FreeCast"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                MediaScannerConnection.scanFile(
                    activity, arrayOf(file.absolutePath), arrayOf("image/png"), null
                )
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
