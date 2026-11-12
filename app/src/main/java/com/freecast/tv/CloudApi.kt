package com.freecast.tv

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 云端接口：激活、账号、版本与远程配置统一走同一个云函数。 */
object CloudApi {

    const val BASE_URL =
        "https://freecast-d5grp41hy510d7e56-1255619356.ap-shanghai.app.tcloudbase.com/activate"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun post(body: JSONObject, onResult: (JSONObject?) -> Unit) {
        Thread {
            val json = try {
                val request = Request.Builder()
                    .url(BASE_URL)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank()) null else JSONObject(text)
                }
            } catch (_: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post { onResult(json) }
        }.start()
    }
}
