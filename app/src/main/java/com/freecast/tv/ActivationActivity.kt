package com.freecast.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 首次运行激活页：
 * 密钥提交到云端服务器校验，服务端保证每个密钥全局只能使用一次
 * （跨设备、卸载重装都生效）。
 */
class ActivationActivity : Activity() {

    companion object {
        private const val SERVER_URL =
            "https://freecast-d5grp41hy510d7e56-1255619356.ap-shanghai.app.tcloudbase.com"
    }

    private lateinit var input: EditText
    private lateinit var confirm: Button

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        input = findViewById(R.id.key_input)
        confirm = findViewById(R.id.key_confirm)

        confirm.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        val key = input.text.toString().trim()
        if (!ActivationKeys.isValidFormat(key)) {
            input.error = getString(R.string.activation_error_format)
            toast(R.string.activation_error_format)
            return
        }
        confirm.isEnabled = false
        Thread {
            val resp = try {
                val body = JSONObject()
                    .put("key", key)
                    .put("deviceId", DeviceInfo.uuid)
                    .apply {
                        FreeCastApp.username(this@ActivationActivity)?.let { put("username", it) }
                        FreeCastApp.sessionToken(this@ActivationActivity)?.let { put("token", it) }
                    }
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$SERVER_URL/activate")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { it.body?.string() ?: "" }
            } catch (_: Exception) {
                null
            }
            runOnUiThread { onResult(key, resp) }
        }.start()
    }

    private fun onResult(key: String, resp: String?) {
        confirm.isEnabled = true
        val json = try {
            resp?.let { JSONObject(it) }
        } catch (_: Exception) {
            null
        }
        when {
            json?.optBoolean("ok") == true -> {
                // 本地记录已激活，之后不再询问
                FreeCastApp.activate(this, key)
                toast(R.string.activation_success)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }

            json?.optString("error") == "used" -> {
                input.error = getString(R.string.activation_error_used)
                toast(R.string.activation_error_used)
            }

            json?.optString("error") == "invalid" -> {
                input.error = getString(R.string.activation_error_format)
                toast(R.string.activation_error_format)
            }

            else -> toast(R.string.activation_error_network)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
