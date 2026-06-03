package com.jaco.codeforwarder

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        if (prefs.getBoolean("exclude_from_recents", false)) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.firstOrNull()?.setExcludeFromRecents(true)
        }

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WebhookScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebhookScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var url by remember { mutableStateOf(prefs.getString("webhook_url", "") ?: "") }
    var method by remember { mutableStateOf(prefs.getString("webhook_method", "POST") ?: "POST") }
    var payload by remember {
        mutableStateOf(
            prefs.getString("webhook_payload", """{"from": "{{from}}", "content": "{{content}}", "timestamp": {{timestamp}}}""") ?: ""
        )
    }
    var methodExpanded by remember { mutableStateOf(false) }
    val methods = listOf("GET", "POST")

    // 监听生命周期，从设置页返回时刷新权限状态
    var notificationEnabled by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val listeners = android.provider.Settings.Secure.getString(
                    context.contentResolver, "enabled_notification_listeners"
                ) ?: ""
                notificationEnabled = listeners.contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var testFrom by remember { mutableStateOf("10086") }
    var testContent by remember { mutableStateOf("【测试】您的验证码为185462，5分钟内有效。") }
    var testResult by remember { mutableStateOf("") }

    var forwardEnabled by remember { mutableStateOf(prefs.getBoolean("forward_enabled", true)) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("验证码转发", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("从短信和邮件通知中提取验证码并转发", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (!notificationEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("需要开启通知访问权限", style = MaterialTheme.typography.titleSmall)
                        Text("通过监听短信和邮件通知来获取验证码并转发", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = {
                            context.startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                        }) {
                            Text("去开启")
                        }
                    }
                }
            }

            // 设置区
            var excludeFromRecents by remember { mutableStateOf(prefs.getBoolean("exclude_from_recents", false)) }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("启用转发", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = forwardEnabled, onCheckedChange = {
                        forwardEnabled = it
                        prefs.edit().putBoolean("forward_enabled", it).apply()
                    }, modifier = Modifier.scale(0.85f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("从最近任务中隐藏", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = excludeFromRecents, onCheckedChange = {
                        excludeFromRecents = it
                        prefs.edit().putBoolean("exclude_from_recents", it).apply()
                        if (it) {
                            (context as? ComponentActivity)?.finishAndRemoveTask()
                        }
                    }, modifier = Modifier.scale(0.85f))
                }
            }

            // 提取配置区
            Text("提取", style = MaterialTheme.typography.titleMedium)
            Text("未匹配则不转发，留空则转发所有通知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            var codeRegex by remember { mutableStateOf(prefs.getString("code_regex", """(\d{4,8})""") ?: "") }
            OutlinedTextField(
                value = codeRegex,
                onValueChange = { codeRegex = it },
                label = { Text("验证码提取正则") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 转发配置区
            Text("转发", style = MaterialTheme.typography.titleMedium)
            Text("支持变量: {{from}} {{content}} {{timestamp}} {{code}}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Webhook URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownMenuBox(expanded = methodExpanded, onExpandedChange = { methodExpanded = it }) {
                OutlinedTextField(
                    value = method,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("请求类型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = methodExpanded, onDismissRequest = { methodExpanded = false }) {
                    methods.forEach {
                        DropdownMenuItem(text = { Text(it) }, onClick = { method = it; methodExpanded = false })
                    }
                }
            }

            if (method != "GET") {
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    placeholder = { Text("""{"from": "{{from}}", "code": "{{code}}"}""") },
                    label = { Text("Payload") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
                )
            }

            var showTestDialog by remember { mutableStateOf(false) }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showTestDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("测试")
                }
                Button(onClick = {
                    prefs.edit()
                        .putString("webhook_url", url)
                        .putString("webhook_method", method)
                        .putString("webhook_payload", payload)
                        .putString("code_regex", codeRegex)
                        .apply()
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) {
                    Text("保存配置")
                }
            }

            if (showTestDialog) {
                AlertDialog(
                    onDismissRequest = { showTestDialog = false },
                    title = { Text("发送测试") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = testFrom,
                                onValueChange = { testFrom = it },
                                label = { Text("模拟发送人") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = testContent,
                                onValueChange = { testContent = it },
                                label = { Text("模拟短信内容") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (testResult.isNotEmpty()) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(testResult, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (url.isBlank()) {
                                testResult = "请先配置 Webhook URL"
                                return@Button
                            }

                            val code = if (codeRegex.isNotBlank()) {
                                try { Regex(codeRegex).find(testContent)?.groupValues?.getOrNull(1) } catch (_: Exception) { null }
                            } else null

                            if (codeRegex.isNotBlank() && code == null) {
                                testResult = "未匹配到验证码，不转发"
                                return@Button
                            }

                            testResult = "提取验证码: ${code ?: "无"}\n发送中..."
                            val body = payload
                                .replace("{{from}}", testFrom.replace("\\", "\\\\").replace("\"", "\\\""))
                                .replace("{{content}}", testContent.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                                .replace("{{timestamp}}", System.currentTimeMillis().toString())
                                .replace("{{code}}", code ?: "")

                            val finalUrl = url
                                .replace("{{from}}", java.net.URLEncoder.encode(testFrom, "UTF-8"))
                                .replace("{{content}}", java.net.URLEncoder.encode(testContent, "UTF-8"))
                                .replace("{{timestamp}}", System.currentTimeMillis().toString())
                                .replace("{{code}}", java.net.URLEncoder.encode(code ?: "", "UTF-8"))

                            val requestBuilder = Request.Builder().url(finalUrl)
                            when (method) {
                                "GET" -> requestBuilder.get()
                                else -> requestBuilder.post(body.toRequestBody("application/json".toMediaType()))
                            }

                            OkHttpClient().newCall(requestBuilder.build()).enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) { testResult = "提取验证码: ${code ?: "无"}\n发送失败: ${e.message}" }
                                override fun onResponse(call: Call, response: Response) { testResult = "提取验证码: ${code ?: "无"}\n发送成功: ${response.code}"; response.close() }
                            })
                        }) { Text("发送") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTestDialog = false; testResult = "" }) { Text("关闭") }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
