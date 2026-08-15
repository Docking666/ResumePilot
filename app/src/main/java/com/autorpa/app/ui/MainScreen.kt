package com.autorpa.app.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autorpa.app.AutoRPAApp
import com.autorpa.app.data.db.ScriptEntity
import com.autorpa.app.data.db.ExecutionLogEntity
import com.autorpa.app.engine.AutoRPAOrchestrator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val app = AutoRPAApp.instance
    val scope = rememberCoroutineScope()
    val scripts = remember { mutableStateListOf<ScriptEntity>() }
    val logs = remember { mutableStateListOf<ExecutionLogEntity>() }
    var selectedTab by remember { mutableIntStateOf(0) }

    // 加载数据
    LaunchedEffect(Unit) {
        scripts.clear()
        scripts.addAll(app.database.scriptDao().getAllScripts())
        logs.clear()
        logs.addAll(app.database.scriptDao().getRecentLogs())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("AutoRPA", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.AddCircle, contentDescription = null) },
                    label = { Text("引导") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = null) },
                    label = { Text("执行") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text("看板") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                    label = { Text("脚本") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("简历") },
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> SetupGuideScreen()
                1 -> ExecutionScreen()
                2 -> DashboardScreen()
                3 -> ScriptListScreen(scripts, onRefresh = {
                    scope.launch {
                        scripts.clear()
                        scripts.addAll(app.database.scriptDao().getAllScripts())
                    }
                })
                4 -> ResumeScreen()
                5 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun RecordingScreen() {
    var isRecording by remember { mutableStateOf(false) }
    var taskDescription by remember { mutableStateOf("") }
    var isExploring by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var generatedScript by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 状态指示器
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isExploring || isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isExploring -> "LLM 探索中…"
                    isRecording -> "录制中…"
                    else -> "待机"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }

        // 任务描述输入
        OutlinedTextField(
            value = taskDescription,
            onValueChange = { taskDescription = it },
            label = { Text("任务描述") },
            placeholder = { Text("例如: 打开BOSS直聘，搜索Java开发岗位并投递简历") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            enabled = !isExploring && !isRecording
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 录制按钮
            OutlinedButton(
                onClick = { isRecording = !isRecording },
                enabled = !isExploring,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isRecording) "停止录制" else "开始录制")
            }

            // LLM 探索按钮
            Button(
                onClick = {
                    isExploring = true
                    logText += "[开始探索] $taskDescription\n"
                    // 实际调用 Orchestrator
                },
                enabled = taskDescription.isNotBlank() && !isExploring,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("LLM 探索")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 生成脚本按钮
        if (isExploring) {
            Button(
                onClick = {
                    isExploring = false
                    generatedScript = "name: \"自动投递脚本\"\nsteps:\n  - action: launch_app\n    package: \"com.hpbr.bosszhipin\"\n    wait: 3000\n  - action: wait\n    millis: 2000\n"
                    logText += "[生成完成] 脚本已保存\n"
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("停止探索并生成脚本")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 日志输出
        Text("执行日志", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Text(
                text = logText.ifEmpty { "等待操作..." },
                modifier = Modifier.padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 生成的脚本预览
        if (generatedScript.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("生成脚本预览", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Text(
                    text = generatedScript,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ScriptListScreen(
    scripts: List<ScriptEntity>,
    onRefresh: () -> Unit
) {
    if (scripts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("暂无脚本", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Text("先录制或探索一个任务", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(scripts) { script ->
                ScriptCard(script, onRefresh)
            }
        }
    }
}

@Composable
fun ScriptCard(script: ScriptEntity, onRefresh: () -> Unit) {
    val app = AutoRPAApp.instance
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* 打开详情 */ },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (script.llmGenerated) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            "AI",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            if (script.description.isNotEmpty()) {
                Text(
                    text = script.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "执行 ${script.runCount} 次 · 成功率 ${if (script.runCount > 0) (script.successCount * 100 / script.runCount) else 0}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { /* 回放 */ },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "回放", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { /* 删除 */ },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LogScreen(logs: List<ExecutionLogEntity>) {
    if (logs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("暂无执行日志", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(logs) { log ->
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (log.success)
                            MaterialTheme.colorScheme.surface
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (log.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (log.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(log.scriptName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${log.stepsCompleted}/${log.stepsTotal} 步 · ${formatTimestamp(log.startedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ====== 简历管理界面 ======

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeScreen() {
    val app = AutoRPAApp.instance
    val scope = rememberCoroutineScope()
    val resume by app.resumeManager.activeResume.collectAsState()
    var showUploadDialog by remember { mutableStateOf(false) }
    var greetingText by remember { mutableStateOf("") }
    var jobTitle by remember { mutableStateOf("") }
    var jobCompany by remember { mutableStateOf("") }
    var jobRequirements by remember { mutableStateOf("") }
    var matchScore by remember { mutableStateOf<Int?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 简历状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("简历管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                if (resume != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("已上传简历", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("姓名: ${resume!!.name}", style = MaterialTheme.typography.bodySmall)
                    Text("求职意向: ${resume!!.title.ifEmpty { "未设置" }}", style = MaterialTheme.typography.bodySmall)
                    Text("技能: ${resume!!.skills.joinToString(", ").take(50)}...", style = MaterialTheme.typography.bodySmall)
                    Text("工作经历: ${resume!!.workExperience.size} 段", style = MaterialTheme.typography.bodySmall)
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("尚未上传简历", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 上传简历按钮
        OutlinedButton(
            onClick = { showUploadDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (resume != null) "重新上传简历" else "上传简历")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 岗位信息和打招呼语生成
        Text("岗位匹配与打招呼语", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = jobTitle,
            onValueChange = { jobTitle = it },
            label = { Text("目标职位") },
            placeholder = { Text("例如: Java开发工程师") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = jobCompany,
            onValueChange = { jobCompany = it },
            label = { Text("目标公司") },
            placeholder = { Text("例如: 字节跳动") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = jobRequirements,
            onValueChange = { jobRequirements = it },
            label = { Text("岗位要求/描述") },
            placeholder = { Text("粘贴岗位描述，方便AI生成精准的打招呼语") },
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            maxLines = 5
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 生成打招呼语
        Button(
            onClick = {
                if (resume != null) {
                    isGenerating = true
                    scope.launch {
                        try {
                            val matcher = com.autorpa.app.resume.JobMatcher(
                                app.preferences.getLLMConfig()
                            )
                            val job = com.autorpa.app.resume.JobDescription(
                                title = jobTitle,
                                company = jobCompany,
                                description = jobRequirements,
                                requirements = jobRequirements.split(Regex("[，,;；\\n]")).filter { it.isNotBlank() }
                            )
                            val greeting = matcher.generateGreeting(
                                resume = resume!!,
                                job = job,
                                style = com.autorpa.app.resume.GreetingStyle.PROFESSIONAL
                            )
                            greetingText = greeting.content
                            // 计算匹配度
                            val match = matcher.matchJob(resume!!, job)
                            matchScore = match.overallScore
                        } catch (e: Exception) {
                            greetingText = "生成失败: ${e.message}"
                        } finally {
                            isGenerating = false
                        }
                    }
                }
            },
            enabled = resume != null && jobTitle.isNotBlank() && !isGenerating,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isGenerating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("AI 生成打招呼语")
        }

        // 匹配度展示
        matchScore?.let { score ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("岗位匹配度: ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "$score%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        score >= 70 -> MaterialTheme.colorScheme.primary
                        score >= 40 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
        }

        // 打招呼语结果
        if (greetingText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("生成结果", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = greetingText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { /* 复制到剪贴板 */ }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制")
                        }
                        Button(onClick = { /* 自动投递 */ }) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("自动投递")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 上传对话框
    if (showUploadDialog) {
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("上传简历") },
            text = {
                Column {
                    Text("支持 PDF、DOCX、TXT 格式的简历文件")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("上传后 AI 会自动解析简历内容，用于:", style = MaterialTheme.typography.bodySmall)
                    Text("• 岗位匹配度分析", style = MaterialTheme.typography.bodySmall)
                    Text("• 智能生成打招呼语", style = MaterialTheme.typography.bodySmall)
                    Text("• 自动定制简历", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showUploadDialog = false
                    scope.launch {
                        app.resumeManager.setResume(
                            com.autorpa.app.resume.ResumeData(
                                name = "张三",
                                title = "Java开发工程师",
                                summary = "5年后端开发经验，精通Java、Spring Boot、微服务架构",
                                skills = listOf("Java", "Spring Boot", "MySQL", "Redis", "Kafka", "Docker", "Kubernetes"),
                                workExperience = listOf(
                                    com.autorpa.app.resume.WorkExperience(
                                        company = "某互联网公司",
                                        title = "高级Java工程师",
                                        startDate = "2021",
                                        endDate = "至今",
                                        description = "负责核心业务系统架构设计和开发",
                                        highlights = listOf("主导了微服务拆分", "系统QPS提升3倍")
                                    )
                                )
                            )
                        )
                    }
                }) { Text("选择文件") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showUploadDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
fun SettingsScreen() {
    val app = AutoRPAApp.instance
    val scope = rememberCoroutineScope()
    var llmProvider by remember { mutableStateOf("OpenAI") }
    var apiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("gpt-4o") }
    var baseUrl by remember { mutableStateOf("") }

    // 加载配置
    LaunchedEffect(Unit) {
        val config = app.preferences.getLLMConfig()
        llmProvider = config.provider.displayName
        apiKey = config.apiKey
        modelName = config.modelName
        baseUrl = config.baseUrl
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("LLM 配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        item {
            OutlinedTextField(
                value = llmProvider,
                onValueChange = { llmProvider = it },
                label = { Text("模型供应商") },
                modifier = Modifier.fillMaxWidth(),
                enabled = false
            )
            Text(
                "支持: OpenAI / Anthropic / 阿里千问 / DeepSeek / Gemini",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }

        item {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
        }

        item {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL (可选)") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如: https://api.openai.com/v1") }
            )
        }

        item {
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名称") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如: gpt-4o, claude-3-5-sonnet") }
            )
        }

        item {
            Button(
                onClick = {
                    scope.launch {
                        app.preferences.saveLLMConfig(
                            com.autorpa.app.llm.LLMConfig(
                                provider = com.autorpa.app.llm.LLMProvider.fromName(llmProvider),
                                apiKey = apiKey,
                                baseUrl = baseUrl,
                                modelName = modelName
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存配置")
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text("权限管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        item {
            PermissionItem(
                title = "无障碍服务",
                description = "用于执行自动化点击、滑动等操作",
                icon = Icons.Default.Visibility
            )
            PermissionItem(
                title = "悬浮窗权限",
                description = "用于显示录制控制浮标",
                icon = Icons.Default.Widgets
            )
            PermissionItem(
                title = "忽略电池优化",
                description = "确保后台任务持续运行",
                icon = Icons.Default.BatterySaver
            )
            PermissionItem(
                title = "截图权限",
                description = "用于 LLM 分析屏幕内容",
                icon = Icons.Default.Screenshot
            )
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
        Switch(
            checked = true,
            onCheckedChange = null
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}