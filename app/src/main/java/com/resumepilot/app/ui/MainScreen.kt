package com.resumepilot.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resumepilot.app.ResumePilotApp
import com.resumepilot.app.data.db.ScriptEntity
import com.resumepilot.app.data.db.ExecutionLogEntity
import com.resumepilot.app.engine.ResumePilotOrchestrator
import com.resumepilot.app.llm.LLMProvider
import com.resumepilot.app.llm.mcp.MCPGatewayManager
import com.resumepilot.app.service.RPAAccessibilityService
import com.resumepilot.app.service.RecordingForegroundService
import com.resumepilot.app.service.ScreenshotCapture
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val app = ResumePilotApp.instance
    val scope = rememberCoroutineScope()
    val scripts = remember { mutableStateListOf<ScriptEntity>() }
    val logs = remember { mutableStateListOf<ExecutionLogEntity>() }
    var selectedTab by remember { mutableIntStateOf(0) }

    // 崩溃诊断横幅：若上一次运行发生过未捕获异常，启动时展示，便于定位"点 Tab 闪退"类问题
    var crashBanner by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashBanner = app.readLatestCrash()
    }

    // 加载数据（防止数据库异常导致整个 App 崩溃）
    LaunchedEffect(Unit) {
        try {
            scripts.clear()
            scripts.addAll(app.database.scriptDao().getAllScripts())
            logs.clear()
            logs.addAll(app.database.scriptDao().getRecentLogs())
        } catch (e: Throwable) {
            android.util.Log.e("MainScreen", "加载脚本/日志失败", e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("简历投递助手", fontWeight = FontWeight.Bold)
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 崩溃诊断横幅：展示最近一次未捕获异常（便于定位"点 Tab 闪退"类问题）
            crashBanner?.let {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "上次崩溃：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
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
}

@Composable
fun RecordingScreen() {
    val app = ResumePilotApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var taskDescription by remember { mutableStateOf("") }
    var isExploring by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var generatedScript by remember { mutableStateOf("") }
    var hasScreenshotAuth by remember { mutableStateOf(app.screenshotCapture?.isAuthorized() == true) }
    var pendingStart by remember { mutableStateOf(false) }

    // 截图捕获实例（授权后写入全局，供 Orchestrator / MCP ScreenshotTool 复用）
    val screenshotCapture = remember { ScreenshotCapture(context.applicationContext) }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ok = screenshotCapture.onActivityResult(result.resultCode, result.data)
        if (ok) {
            app.screenshotCapture = screenshotCapture
            hasScreenshotAuth = true
            logText += "[授权成功] 屏幕捕获已就绪\n"
        } else {
            logText += "[错误] 屏幕捕获授权失败\n"
        }
    }

    // 编排器：复用全局截图器与应用级协程作用域（切后台/Activity 销毁不中断）
    val orchestrator = remember {
        ResumePilotOrchestrator(
            context = context.applicationContext,
            accessibilityService = RPAAccessibilityService.instance,
            db = app.database,
            preferences = app.preferences,
            screenshotCapture = screenshotCapture,
            scope = app.appScope
        )
    }

    // 订阅编排器状态与日志
    val orchStatus by orchestrator.status.collectAsState()
    val orchLog by orchestrator.log.collectAsState()
    LaunchedEffect(orchLog) {
        if (orchLog.isNotEmpty()) logText = orchLog
    }
    LaunchedEffect(orchStatus) {
        isExploring = orchStatus == ResumePilotOrchestrator.OrchestratorStatus.EXPLORING
    }

    fun startExplore() {
        val svc = RPAAccessibilityService.instance
        if (svc == null) {
            logText += "[错误] 无障碍服务未开启，请先在系统设置中开启「简历投递助手」\n"
            return
        }
        // 刷新编排器持有的服务实例（防止组合时快照过期）
        orchestrator.updateAccessibilityService(svc)
        scope.launch {
            val config = app.preferences.getLLMConfig()
            if (config.apiKey.isBlank()) {
                logText += "[错误] 未配置 LLM API Key，请先到「设置」页填写\n"
                return@launch
            }
            orchestrator.updateLLMConfig(config)
            RecordingForegroundService.start(context)
            logText = ""
            orchestrator.startExploring(taskDescription)
        }
    }

    // 屏幕捕获授权完成后自动开始探索
    LaunchedEffect(hasScreenshotAuth, pendingStart) {
        if (hasScreenshotAuth && pendingStart) {
            pendingStart = false
            startExplore()
        }
    }

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
                    isGenerating -> "正在生成脚本…"
                    isExploring -> "LLM 探索中…"
                    isRecording -> "录制中…"
                    else -> "待机"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }

        // 屏幕捕获授权状态
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (hasScreenshotAuth) Icons.Default.CheckCircle else Icons.Default.CameraAlt,
                contentDescription = null,
                tint = if (hasScreenshotAuth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (hasScreenshotAuth) "屏幕捕获已授权（LLM 视觉可用）"
                else "LLM 视觉需要屏幕捕获授权，探索时将自动请求",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

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
            // 录制按钮（通过无障碍服务录制真实操作轨迹）
            OutlinedButton(
                onClick = {
                    val svc = RPAAccessibilityService.instance
                    if (isRecording) {
                        val steps = svc?.stopRecording()
                        logText += "[录制完成] 共 ${steps?.size ?: 0} 步\n"
                        isRecording = false
                    } else {
                        if (svc == null) {
                            logText += "[错误] 无障碍服务未开启\n"
                        } else {
                            svc.startRecording()
                            isRecording = true
                            logText += "[开始录制] 你的操作将被记录，完成后再点一次停止\n"
                        }
                    }
                },
                enabled = !isExploring,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isRecording) "停止录制" else "录制操作")
            }

            // LLM 探索按钮
            Button(
                onClick = {
                    if (hasScreenshotAuth) {
                        startExplore()
                    } else {
                        // 未授权：先启动前台服务并请求屏幕捕获授权，授权后自动开始
                        pendingStart = true
                        RecordingForegroundService.start(context)
                        screenCaptureLauncher.launch(screenshotCapture.createScreenCaptureIntent())
                    }
                },
                enabled = taskDescription.isNotBlank() && !isExploring && !isGenerating,
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
                    scope.launch {
                        isGenerating = true
                        orchestrator.stop()
                        val scriptName = taskDescription.take(20).ifBlank { "自动探索脚本" }
                        logText += "[生成] 正在生成可复用脚本...\n"
                        val yaml = orchestrator.generateScript(scriptName).await()
                        if (!yaml.isNullOrEmpty()) {
                            generatedScript = yaml
                            // 保存到数据库（脚本 Tab 可见、可回放）
                            app.database.scriptDao().insertScript(
                                ScriptEntity(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = scriptName,
                                    description = taskDescription,
                                    yamlContent = yaml,
                                    sourceTrajectoryId = null,
                                    llmGenerated = true,
                                    appPackage = null,
                                    createdAt = System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis(),
                                    runCount = 0,
                                    successCount = 0,
                                    tags = "[]"
                                )
                            )
                            logText += "[完成] 脚本已保存到「脚本」Tab\n"
                        } else {
                            logText += "[错误] 脚本生成失败：无轨迹数据\n"
                        }
                        isGenerating = false
                        RecordingForegroundService.stop(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
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
    val app = ResumePilotApp.instance
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
    val app = ResumePilotApp.instance
    val scope = rememberCoroutineScope()
    val resume by app.resumeManager.activeResume.collectAsState()
    var showUploadDialog by remember { mutableStateOf(false) }
    var greetingText by remember { mutableStateOf("") }
    var jobTitle by remember { mutableStateOf("") }
    var jobCompany by remember { mutableStateOf("") }
    var jobRequirements by remember { mutableStateOf("") }
    var matchScore by remember { mutableStateOf<Int?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                uploadStatus = "正在解析简历..."
                val result = app.resumeManager.uploadResume(uri)
                uploadStatus = if (result.isSuccess) {
                    "简历上传成功: ${result.getOrNull()?.name}"
                } else {
                    "上传失败: ${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

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

        // 上传状态
        uploadStatus?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (status.startsWith("简历上传成功"))
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (status.startsWith("简历上传成功")) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (status.startsWith("简历上传成功"))
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
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
                            val matcher = com.resumepilot.app.resume.JobMatcher(
                                app.preferences.getLLMConfig()
                            )
                            val job = com.resumepilot.app.resume.JobDescription(
                                title = jobTitle,
                                company = jobCompany,
                                description = jobRequirements,
                                requirements = jobRequirements.split(Regex("[，,;；\\n]")).filter { it.isNotBlank() }
                            )
                            val greeting = matcher.generateGreeting(
                                resume = resume!!,
                                job = job,
                                style = com.resumepilot.app.resume.GreetingStyle.PROFESSIONAL
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
                    filePickerLauncher.launch(arrayOf(
                        "application/pdf",
                        "text/plain",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    ))
                }) { Text("选择文件") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showUploadDialog = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val app = ResumePilotApp.instance
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
            // LLM 供应商下拉选择
            var expanded by remember { mutableStateOf(false) }
            val providers = LLMProvider.entries.toList()

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = llmProvider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("模型供应商") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                llmProvider = provider.displayName
                                modelName = when (provider) {
                                    LLMProvider.OPENAI -> "gpt-4o"
                                    LLMProvider.ANTHROPIC -> "claude-3-5-sonnet"
                                    LLMProvider.ALIYUN -> "qwen-vl-plus"
                                    LLMProvider.DEEPSEEK -> "deepseek-chat"
                                    LLMProvider.GEMINI -> "gemini-1.5-pro"
                                }
                                expanded = false
                            }
                        )
                    }
                }
            }
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
                            com.resumepilot.app.llm.LLMConfig(
                                provider = com.resumepilot.app.llm.LLMProvider.fromName(llmProvider),
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
            // 权限引导：点击各项跳转系统设置（电池优化/悬浮窗/无障碍）
            val activity = LocalContext.current as? com.resumepilot.app.MainActivity
            PermissionItem(
                title = "无障碍服务",
                description = "用于执行自动化点击、滑动等操作",
                icon = Icons.Default.Visibility,
                onClick = { activity?.openAccessibilitySettings() }
            )
            PermissionItem(
                title = "悬浮窗权限",
                description = "用于显示录制控制浮标",
                icon = Icons.Default.Widgets,
                onClick = { activity?.requestOverlayPermission() }
            )
            PermissionItem(
                title = "忽略电池优化",
                description = "确保后台任务持续运行",
                icon = Icons.Default.BatterySaver,
                onClick = { activity?.requestBatteryOptimization() }
            )
            PermissionItem(
                title = "截图权限",
                description = "用于 LLM 分析屏幕内容，在「引导」或「执行」页自动请求",
                icon = Icons.Default.Screenshot
            )
        }

        // ====== MCP 工具管理 ======
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text("MCP 工具管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        item {
            MCPStatusCard()
        }
    }
}

@Composable
fun MCPStatusCard() {
    val mcpManager = MCPGatewayManager.getInstance()
    val isReady = remember { mcpManager.isReady() }
    val registry = remember { mcpManager.registry }
    val tools by registry.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 网关状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isReady) "MCP 网关已就绪" else "MCP 网关未初始化",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 已注册工具列表
            Text(
                "已注册工具 (${tools.getAll().size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (tools.getAll().isEmpty()) {
                Text(
                    "暂无工具，启动无障碍服务后自动注册",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            } else {
                tools.getAll().forEach { tool ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                tool.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                tool.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** 提供 onClick 时整行可点击，用于引导用户去系统设置开启对应权限 */
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
                if (onClick != null) "$description（点击前往设置）" else description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
        Icon(
            if (onClick != null) Icons.Default.ChevronRight else Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}