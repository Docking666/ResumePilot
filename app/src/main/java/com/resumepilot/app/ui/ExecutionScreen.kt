package com.resumepilot.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resumepilot.app.ResumePilotApp
import com.resumepilot.app.adapter.PlatformTemplate
import com.resumepilot.app.adapter.WorkflowEngine
import com.resumepilot.app.data.db.TemplateEntity
import com.resumepilot.app.llm.LLMClient
import com.resumepilot.app.resume.GreetingMessage
import com.resumepilot.app.resume.GreetingStyle
import com.resumepilot.app.resume.JobDescription
import com.resumepilot.app.resume.JobMatcher
import com.resumepilot.app.service.RPAAccessibilityService
import com.resumepilot.app.service.RecordingForegroundService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 执行界面——日常使用的核心操作界面
 *
 * 功能：
 * 1. 显示已生成的平台模板列表
 * 2. 输入搜索关键词和打招呼语
 * 3. 一键执行自动投递流水线
 * 4. 实时显示执行进度和日志
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionScreen() {
    val app = ResumePilotApp.instance
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf<List<TemplateEntity>>(emptyList()) }
    var selectedTemplate by remember { mutableStateOf<TemplateEntity?>(null) }
    var keyword by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var greetingText by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }
    var executionLog by remember { mutableStateOf("") }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }

    // 加载模板列表
    LaunchedEffect(Unit) {
        templates = app.database.templateDao().getActiveTemplates()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动投递", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        if (templates.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AddCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("尚未配置平台模板", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Text(
                        "请先到「引导」Tab 配置招聘平台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 平台选择
                item {
                    Text("选择平台", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    templates.forEach { template ->
                        Card(
                            onClick = {
                                selectedTemplate = if (selectedTemplate?.id == template.id) null else template
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedTemplate?.id == template.id)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedTemplate?.id == template.id,
                                    onClick = { selectedTemplate = template }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(template.platformName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "v${template.version} · ${template.workflowCount}个工作流 · ${template.runCount}次执行",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                                Icon(
                                    if (template.platformName == "BOSS直聘") Icons.Default.Work else Icons.Default.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // 参数输入
                if (selectedTemplate != null) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("搜索参数", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            label = { Text("关键词") },
                            placeholder = { Text("例如: Java开发") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isExecuting
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            label = { Text("城市 (可选)") },
                            placeholder = { Text("例如: 北京") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isExecuting
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = greetingText,
                            onValueChange = { greetingText = it },
                            label = { Text("打招呼语 (可选)") },
                            placeholder = { Text("留空则由 AI 根据简历自动生成") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            maxLines = 4,
                            enabled = !isExecuting
                        )

                        // 自动生成打招呼语按钮
                        if (greetingText.isBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val resume = app.resumeManager.getActiveResume()
                                        if (resume != null) {
                                            try {
                                                val config = app.preferences.getLLMConfig()
                                                val matcher = JobMatcher(config)
                                                val job = JobDescription(
                                                    title = keyword,
                                                    company = "",
                                                    requirements = listOf(keyword)
                                                )
                                                val greeting = matcher.generateGreeting(
                                                    resume = resume,
                                                    job = job,
                                                    style = GreetingStyle.PROFESSIONAL
                                                )
                                                greetingText = greeting.content
                                            } catch (e: Exception) {
                                                greetingText = "您好，我对贵公司正在招聘的$keyword 岗位非常感兴趣，希望能进一步沟通。"
                                            }
                                        }
                                    }
                                },
                                enabled = app.resumeManager.getActiveResume() != null
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("AI 自动生成打招呼语")
                            }
                        }
                    }

                    // 执行按钮
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isExecuting = true
                                executionLog = ""
                                showResult = false
                                scope.launch {
                                    executeWorkflow(
                                        app = app,
                                        templateEntity = selectedTemplate!!,
                                        keyword = keyword,
                                        greetingText = greetingText,
                                        onLog = { executionLog += it + "\n" },
                                        onComplete = { success ->
                                            isExecuting = false
                                            resultSuccess = success
                                            showResult = true
                                        }
                                    )
                                }
                            },
                            enabled = selectedTemplate != null && keyword.isNotBlank() && !isExecuting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (isExecuting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("执行中...")
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("开始投递", fontSize = 16.sp)
                            }
                        }
                    }

                    // 执行日志
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("执行日志", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = executionLog.ifEmpty { "等待操作..." },
                                modifier = Modifier.padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // 结果提示
                    if (showResult) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (resultSuccess)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (resultSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (resultSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (resultSuccess) "执行完成" else "执行失败",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====== 业务逻辑 ======

private suspend fun executeWorkflow(
    app: ResumePilotApp,
    templateEntity: TemplateEntity,
    keyword: String,
    greetingText: String,
    onLog: (String) -> Unit,
    onComplete: (Boolean) -> Unit
) {
    // 从无障碍服务获取实例（服务未启用时为 null）
    val accessibilityService = RPAAccessibilityService.instance

    // 执行期间启动前台服务 + WakeLock，防止切后台后进程被杀/休眠；
    // 结束后在 finally 中停止。
    val keepAlive = accessibilityService != null
    if (keepAlive) {
        RecordingForegroundService.start(app)
        onLog("[${timestamp()}] 已启用后台保活（前台服务 + 唤醒锁）")
    }

    try {
        val template = PlatformTemplate.fromJson(templateEntity.templateJson)
        val config = app.preferences.getLLMConfig()
        val llmClient = LLMClient(config)

        onLog("[${timestamp()}] 开始执行: ${template.platformName}")
        onLog("[${timestamp()}] 关键词: $keyword")
        onLog("[${timestamp()}] 打招呼语: ${greetingText.take(50)}...")

        // 获取最终的打招呼语
        val finalGreeting = if (greetingText.isNotBlank()) {
            greetingText
        } else {
            val resume = app.resumeManager.getActiveResume()
            if (resume != null) {
                try {
                    val matcher = JobMatcher(config)
                    val job = JobDescription(title = keyword, requirements = listOf(keyword))
                    val greeting = matcher.generateGreeting(resume, job, GreetingStyle.PROFESSIONAL)
                    onLog("[${timestamp()}] AI 已生成打招呼语")
                    greeting.content
                } catch (e: Exception) {
                    "您好，我对贵公司正在招聘的$keyword 岗位非常感兴趣，希望能进一步沟通。"
                }
            } else {
                "您好，我对贵公司正在招聘的$keyword 岗位非常感兴趣，希望能进一步沟通。"
            }
        }

        // 执行工作流
        onLog("[${timestamp()}] 正在搜索岗位...")

        // 无障碍服务未连接时，引导用户开启
        if (accessibilityService == null) {
            onLog("[${timestamp()}] ⚠️ 无障碍服务未连接，请确保已开启")
            onLog("[${timestamp()}] 投递功能需要无障碍服务才能自动执行")
            onLog("[${timestamp()}] 请在设置中开启简历投递助手的无障碍服务权限")
            onComplete(false)
            return
        }

        val engine = WorkflowEngine(accessibilityService, llmClient)

        // 执行搜索工作流
        val searchResult = engine.execute(
            template = template,
            workflowName = "search_jobs",
            params = mapOf("keyword" to keyword, "city" to "")
        )

        if (!searchResult.success) {
            onLog("[${timestamp()}] ❌ 搜索失败: ${searchResult.errorMessage}")
            onComplete(false)
            return
        }

        onLog("[${timestamp()}] ✅ 搜索完成，开始投递...")

        // 执行投递工作流
        val applyResult = engine.execute(
            template = template,
            workflowName = "apply_job",
            params = mapOf("greeting_text" to finalGreeting)
        )

        if (applyResult.success) {
            onLog("[${timestamp()}] ✅ 投递成功!")
            if (applyResult.repaired) {
                onLog("[${timestamp()}] 🔧 模板已自动修复并更新")
            }
            // 更新模板统计
            app.database.templateDao().updateRunStats(
                templateEntity.id,
                if (applyResult.repaired) 1 else 0
            )
            onComplete(true)
        } else {
            onLog("[${timestamp()}] ❌ 投递失败: ${applyResult.errorMessage}")
            onComplete(false)
        }
    } catch (e: Exception) {
        onLog("[${timestamp()}] ❌ 异常: ${e.message ?: "未知错误"}")
        onComplete(false)
    } finally {
        // 无论成功失败都释放前台服务
        if (keepAlive) {
            RecordingForegroundService.stop(app)
        }
    }
}

private fun timestamp(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}