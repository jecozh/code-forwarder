package com.jaco.codeforwarder

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class SmsNotificationListener : NotificationListenerService() {

    private val client = OkHttpClient()
    private var lastHash: String = ""

    private val allowedPackages = setOf(
        // SMS
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.xiaomi.mms",
        "com.miui.sms",
        // Email
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.netease.mail",
        "com.tencent.androidqqmail",
        "com.alibaba.cloudmail",
        "com.android.email"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        if (!prefs.getBoolean("forward_enabled", true)) return
        if (sbn.packageName !in allowedPackages) return
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (flags and Notification.FLAG_NO_CLEAR != 0) return

        val extras = sbn.notification.extras
        val from = extras.getCharSequence("android.title")?.toString() ?: ""
        val content = extras.getCharSequence("android.text")?.toString() ?: ""
        if (from.isBlank() || content.isBlank()) return

        val hash = "$from:$content".hashCode().toString()
        if (hash == lastHash) return
        lastHash = hash

        Log.d("SmsListener", "SMS from=$from content=${content.take(50)}")
        forwardToWebhook(from, content, sbn.postTime)
    }

    private fun forwardToWebhook(from: String, body: String, timestamp: Long) {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val url = prefs.getString("webhook_url", null) ?: return
        val method = prefs.getString("webhook_method", "POST") ?: "POST"
        val template = prefs.getString("webhook_payload",
            """{"from": "{{from}}", "content": "{{content}}", "timestamp": {{timestamp}}}""") ?: return
        val regex = prefs.getString("code_regex", "") ?: ""

        val code = if (regex.isNotBlank()) {
            val result = try { Regex(regex).find(body)?.groupValues?.getOrNull(1) } catch (_: Exception) { null }
            result ?: return  // 正则有值但没匹配到，不转发
        } else ""

        val payload = template
            .replace("{{from}}", from.replace("\\", "\\\\").replace("\"", "\\\""))
            .replace("{{content}}", body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
            .replace("{{timestamp}}", timestamp.toString())
            .replace("{{code}}", code)

        val finalUrl = url
            .replace("{{from}}", java.net.URLEncoder.encode(from, "UTF-8"))
            .replace("{{content}}", java.net.URLEncoder.encode(body, "UTF-8"))
            .replace("{{timestamp}}", timestamp.toString())
            .replace("{{code}}", java.net.URLEncoder.encode(code, "UTF-8"))

        val requestBuilder = Request.Builder().url(finalUrl)
        when (method) {
            "GET" -> requestBuilder.get()
            else -> requestBuilder.post(payload.toRequestBody("application/json".toMediaType()))
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SmsListener", "Webhook failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d("SmsListener", "Webhook success: ${response.code}")
                response.close()
            }
        })
    }
}
