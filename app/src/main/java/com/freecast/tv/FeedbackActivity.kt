package com.freecast.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/**
 * 帮助与反馈：联系方式 + 情况说明 + 附件（图片/视频）。
 * 附件单个不超过 3MB，总计不超过 10MB，每个设备 24 小时内最多提交 3 次。
 */
class FeedbackActivity : Activity() {

    private lateinit var contactInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var filesText: TextView
    private lateinit var statusText: TextView
    private lateinit var submitBtn: Button
    private lateinit var attachBtn: Button

    private val picked = mutableListOf<Uri>()
    private var uploading = false

    private val maxPerFile = 3 * 1024 * 1024L
    private val maxTotal = 10 * 1024 * 1024L
    private val maxFiles = 6
    private val chunkSize = 600 * 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)
        AppMode.applyStatusBarTopPadding(this)

        contactInput = findViewById(R.id.feedback_contact)
        contentInput = findViewById(R.id.feedback_content)
        filesText = findViewById(R.id.feedback_files)
        statusText = findViewById(R.id.feedback_status)
        submitBtn = findViewById(R.id.feedback_submit)
        attachBtn = findViewById(R.id.feedback_attach)

        findViewById<Button>(R.id.feedback_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.feedback_qq).setOnClickListener { openAuthorQq() }
        attachBtn.setOnClickListener { pickFiles() }
        submitBtn.setOnClickListener { submit() }
        renderFiles()
    }

    /** 联系作者：弹窗显示 QQ 号，可一键复制或关闭（不强制跳转 QQ） */
    private fun openAuthorQq() {
        val qq = "84461502"
        android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(R.string.pay_contact_author)
            .setMessage(getString(R.string.pay_contact_message, qq))
            .setPositiveButton("复制QQ") { _, _ ->
                try {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ", qq))
                    Toast.makeText(this, getString(R.string.pay_qq_copied, qq), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.feedback_qq_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun pickFiles() {
        if (picked.size >= maxFiles) {
            Toast.makeText(this, getString(R.string.feedback_files_max, maxFiles), Toast.LENGTH_SHORT).show()
            return
        }
        // 依次尝试多种文件选择器：某些 TV 盒子/低端机型上 ACTION_OPEN_DOCUMENT
        // 可能没有实现，需要回退到 ACTION_GET_CONTENT。
        val candidates = listOf(
            {
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            },
            {
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            },
            {
                val chooser = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                Intent.createChooser(chooser, "选择附件").apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
        )
        for ((idx, builder) in candidates.withIndex()) {
            try {
                startActivityForResult(builder(), REQ_PICK)
                return
            } catch (_: Exception) {
                // 静默失败，尝试下一个
            }
        }
        Toast.makeText(this, R.string.feedback_pick_failed, Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        data.data?.let { uris.add(it) }
        for (u in uris) {
            if (picked.size >= maxFiles) break
            val size = fileSize(u)
            if (size > maxPerFile) {
                Toast.makeText(this, R.string.feedback_file_too_large, Toast.LENGTH_LONG).show()
                continue
            }
            picked.add(u)
        }
        if (totalSize() > maxTotal) {
            Toast.makeText(this, R.string.feedback_total_too_large, Toast.LENGTH_LONG).show()
            while (picked.isNotEmpty() && totalSize() > maxTotal) {
                picked.removeAt(picked.size - 1)
            }
        }
        renderFiles()
    }

    private fun fileSize(uri: Uri): Long {
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.coerceAtLeast(0L) ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun fileName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (_: Exception) {
        }
        return name
    }

    private fun totalSize(): Long = picked.sumOf { fileSize(it) }

    private fun renderFiles() {
        if (picked.isEmpty()) {
            filesText.text = getString(R.string.feedback_no_files)
            return
        }
        val sb = StringBuilder()
        picked.forEach { u ->
            sb.append("· ").append(fileName(u)).append("　").append(formatSize(fileSize(u))).append("\n")
        }
        sb.append(getString(R.string.feedback_total, formatSize(totalSize())))
        filesText.text = sb.toString()
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(java.util.Locale.US, "%.0fKB", kb)
        else String.format(java.util.Locale.US, "%.1fMB", kb / 1024)
    }

    private fun submit() {
        if (uploading) return
        val contact = contactInput.text.toString().trim()
        val content = contentInput.text.toString().trim()
        if (contact.isEmpty()) {
            Toast.makeText(this, R.string.feedback_contact_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (content.isEmpty()) {
            Toast.makeText(this, R.string.feedback_content_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (totalSize() > maxTotal) {
            Toast.makeText(this, R.string.feedback_total_too_large, Toast.LENGTH_LONG).show()
            return
        }

        uploading = true
        submitBtn.isEnabled = false
        statusText.text = getString(R.string.feedback_submitting)

        val body = JSONObject()
            .put("action", "feedback_start")
            .put("deviceId", DeviceInfo.uuid)
            .put("contact", contact)
            .put("content", content)
        CloudApi.post(body) { json ->
            if (json?.optBoolean("ok") != true) {
                uploading = false
                submitBtn.isEnabled = true
                statusText.text = if (json?.optString("error") == "limit") {
                    getString(R.string.feedback_limit)
                } else {
                    getString(R.string.feedback_failed)
                }
                return@post
            }
            val fid = json.optInt("feedbackId", 0)
            uploadAttachments(fid, 0) { ok ->
                uploading = false
                submitBtn.isEnabled = true
                if (ok) {
                    finishSubmit(fid, contact, content)
                } else {
                    statusText.text = getString(R.string.feedback_upload_failed)
                }
            }
        }
    }

    private fun finishSubmit(fid: Int, contact: String, content: String) {
        CloudApi.post(
            JSONObject()
                .put("action", "feedback_finish")
                .put("feedbackId", fid)
                .put("deviceId", DeviceInfo.uuid)
                .put("contact", contact)
                .put("content", content)
        ) { }
        picked.clear()
        renderFiles()
        contentInput.setText("")
        statusText.text = getString(R.string.feedback_ok)
        Toast.makeText(this, R.string.feedback_ok, Toast.LENGTH_LONG).show()
    }

    /** 逐个附件、分块上传，单次请求 <1MB，避免超出云函数请求上限 */
    private fun uploadAttachments(fid: Int, index: Int, done: (Boolean) -> Unit) {
        if (index >= picked.size) {
            done(true)
            return
        }
        statusText.text = getString(R.string.feedback_uploading, index + 1, picked.size)
        val uri = picked[index]
        Thread {
            var ok = true
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    ok = false
                } else {
                    var offset = 0
                    var chunkIndex = index * 10_000
                    while (offset < bytes.size && ok) {
                        val end = minOf(offset + chunkSize, bytes.size)
                        val encoded = Base64.encodeToString(bytes.copyOfRange(offset, end), Base64.NO_WRAP)
                        ok = postChunk(fid, chunkIndex, encoded)
                        offset = end
                        chunkIndex++
                    }
                }
            } catch (e: Exception) {
                ok = false
            }
            val success = ok
            runOnUiThread {
                if (success) uploadAttachments(fid, index + 1, done) else done(false)
            }
        }.start()
    }

    private fun postChunk(fid: Int, index: Int, data: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        runOnUiThread {
            CloudApi.post(
                JSONObject()
                    .put("action", "feedback_chunk")
                    .put("feedbackId", fid)
                    .put("index", index)
                    .put("data", data)
            ) { json ->
                ok = json?.optBoolean("ok") == true
                latch.countDown()
            }
        }
        return try {
            latch.await()
            ok
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val REQ_PICK = 1001
    }
}
