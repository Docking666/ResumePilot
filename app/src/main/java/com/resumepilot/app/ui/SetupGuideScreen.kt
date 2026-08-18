package com.resumepilot.app.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.resumepilot.app.ResumePilotApp
import com.resumepilot.app.adapter.GuidePage
import com.resumepilot.app.adapter.PlatformAdapter
import com.resumepilot.app.adapter.PlatformTemplate
import com.resumepilot.app.adapter.TemplateGenerationResult
import com.resumepilot.app.service.RecordingForegroundService
import com.resumepilot.app.service.ScreenshotCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 引导截图界面——用户首次使用时的核心交互界面
 *
 * 流程状态：
 * 1. SELECT_PLATFORM — 选择招聘平台
 * 2. SCREENSHOT_GUIDE — 按引导逐一截图
 * 3. ANALYZING — LLM 分析截图
 * 4. PREVIEW — 预览生成的模板
 * 5. COMPLETE — 完成
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGuideScreen() {
    val app = ResumePilotApp.instance
    val scope = rememberCoroutineScope()
    val adapters = remember { com.resumepilot.app.adapter.PlatformAdapterFactory.getInstance().getAll() }

    // 状态
    var hasValidKey by remember { mutableStateOf(false) }
    var showKeyWarning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 引导流程最后要用 LLM 分析截图生成模板，没有 Key 就只是空跑，提前拦截
        hasValidKey = app.preferences.getLLMConfig().apiKey.isNotBlank()
    }

    var currentStep by remember { mutableIntStateOf(0) }
    var selectedAdapter by remember { mutableStateOf<PlatformAdapter?>(null) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var capturedScreenshots by remember { mutableStateOf<MutableList<Pair<String, String>>>(mutableListOf()) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var generatedTemplate by remember { mutableStateOf<PlatformTemplate?>(null) }
    var analysisResult by remember { mutableStateOf<TemplateGenerationResult?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentStep) {
                            0 -> "选择平台"
                            1 -> "引导截图"
                            2 -> "分析中..."
                            3 -> "模板预览"
                            else -> "完成"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (currentStep > 0 && currentStep < 4) {
                        IconButton(onClick = {
                            if (currentStep == 1 && currentPageIndex > 0) {
                                currentPageIndex--
                            } else {
                                currentStep--
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentStep) {
                0 -> SelectPlatformStep(
                    adapters = adapters,
                    keyWarning = showKeyWarning,
                    onSelect = { adapter ->
                        if (!hasValidKey) {
                            showKeyWarning = true
                            return@onSelect
                        }
                        selectedAdapter = adapter
                        currentStep = 1
                        currentPageIndex = 0
                        capturedScreenshots = mutableListOf()
                    }
                )

                1 -> ScreenshotGuideStep(
                    adapter = selectedAdapter,
                    currentPageIndex = currentPageIndex,
                    totalPages = selectedAdapter?.guideConfig?.pages?.size ?: 0,
                    scope = scope,
                    onScreenshotTaken = { pageKey, base64 ->
                        capturedScreenshots.removeAll { it.first == pageKey }
                        capturedScreenshots.add(pageKey to base64)
                        val pages = selectedAdapter?.guideConfig?.pages ?: emptyList()
                        if (currentPageIndex < pages.size - 1) {
                            currentPageIndex++
                        } else {
                            // 所有截图完成，开始分析
                            currentStep = 2
                            isAnalyzing = true
                            scope.launch {
                                analyzeScreenshots(
                                    app = app,
                                    adapter = selectedAdapter!!,
                                    screenshots = capturedScreenshots,
                                    onResult = { result ->
                                        analysisResult = result
                                        generatedTemplate = result.template
                                        isAnalyzing = false
                                        if (result.success) {
                                            currentStep = 3
                                        } else {
                                            errorMessage = result.errorMessage ?: "模板生成失败"
                                            currentStep = 4
                                        }
                                    }
                                )
                            }
                        }
                    },
                    onSkip = {
                        val pages = selectedAdapter?.guideConfig?.pages ?: emptyList()
                        if (currentPageIndex < pages.size - 1) {
                            currentPageIndex++
                        }
                    }
                )

                2 -> AnalyzingStep(
                    currentPageIndex = capturedScreenshots.size,
                    totalPages = selectedAdapter?.guideConfig?.pages?.size ?: 0
                )

                3 -> TemplatePreviewStep(
                    template = generatedTemplate,
                    adapter = selectedAdapter,
                    onConfirm = {
                        // 保存模板到数据库
                        scope.launch {
                            generatedTemplate?.let { template ->
                                saveTemplate(app, template)
                            }
                            currentStep = 4
                        }
                    },
                    onRetry = {
                        currentStep = 1
                        currentPageIndex = 0
                        capturedScreenshots = mutableListOf()
                        generatedTemplate = null
                    }
                )

                4 -> CompleteStep(
                    adapter = selectedAdapter,
                    template = generatedTemplate,
                    errorMessage = errorMessage,
                    onDone = { currentStep = 0 }
                )
            }
        }
    }
}

// ====== 子步骤界面 ======

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectPlatformStep(
    adapters: List<PlatformAdapter>,
    keyWarning: Boolean = false,
    onSelect: (PlatformAdapter) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "选择要配置的招聘平台",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "首次使用需要截图引导，AI 会自动分析页面结构并生成操作模板",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (keyWarning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                )
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "请先在「设置」页配置 API Key，否则无法用 AI 分析截图生成模板",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 平台列表
        adapters.forEach { adapter ->
            Card(
                onClick = { onSelect(adapter) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Work,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            adapter.platformName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "包名: ${adapter.appPackage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
        }

        if (adapters.isEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    "暂无可用平台适配器",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ScreenshotGuideStep(
    adapter: PlatformAdapter?,
    currentPageIndex: Int,
    totalPages: Int,
    scope: CoroutineScope,
    onScreenshotTaken: (String, String) -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val pages = adapter?.guideConfig?.pages ?: emptyList()
    val currentPage = pages.getOrNull(currentPageIndex)
    var isCapturing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var isAuthorized by remember { mutableStateOf(false) }
    var hasTriggeredAuth by remember { mutableStateOf(false) }
    var hasLaunchedApp by remember { mutableStateOf(false) }
    var appInstalled by remember { mutableStateOf(true) }
    var lastCaptureBase64 by remember { mutableStateOf<String?>(null) }

    val app = ResumePilotApp.instance

    // 截图捕获实例（记住，不随重组重建）
    val screenshotCapture = remember {
        ScreenshotCapture(context.applicationContext)
    }

    // 通知权限（Android 13+ 前台服务通知需要；拒绝不影响服务运行，但最好引导开启）
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    // MediaProjection 授权 Launcher
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Android 14+ 上必须先启动 mediaProjection 前台服务才能 getMediaProjection
            val ok = screenshotCapture.onActivityResult(result.resultCode, result.data)
            if (ok) {
                // 写入全局，供 Orchestrator / MCP ScreenshotTool / WorkflowEngine 复用同一授权会话
                ResumePilotApp.instance.screenshotCapture = screenshotCapture
                isAuthorized = true
                isCapturing = false
                captureError = null
                // 不再自动截图：授权后由用户通过通知栏「截图本页」或在目标 App 内主动截取真实页面
            } else {
                isCapturing = false
                captureError = if (screenshotCapture.requiresForegroundService()) {
                    "屏幕捕获授权失败：未启动前台服务，请重试"
                } else {
                    "屏幕捕获授权失败，请重试"
                }
            }
        } else {
            isCapturing = false
            captureError = "用户拒绝了屏幕捕获授权"
        }
    }

    // 进入引导时尝试拉起目标 App，让引导真正针对目标平台，而不是本应用自身
    LaunchedEffect(Unit) {
        if (!hasLaunchedApp) {
            hasLaunchedApp = true
            val pkg = adapter?.appPackage
            if (!pkg.isNullOrBlank()) {
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        appInstalled = true
                    } else {
                        appInstalled = false
                    }
                } catch (e: Exception) {
                    appInstalled = false
                }
            }
        }
    }

    // 同步当前页 key 到全局，供通知栏「截图本页」按钮定位截的是哪一页
    LaunchedEffect(currentPageIndex) {
        ResumePilotApp.instance.currentGuidePageKey = currentPage?.key ?: ""
    }

    // 截图结果通过全局 flow 回流（通知栏按钮 / 应用内按钮走同一通道）
    val guideCapture by app.guideCaptureFlow.collectAsState()
    LaunchedEffect(guideCapture) {
        val cap = guideCapture
        val key = currentPage?.key
        if (cap != null && key != null && cap.first == key) {
            onScreenshotTaken(key, cap.second)
            lastCaptureBase64 = cap.second
            captureError = null
        }
    }

    // 首次进入自动触发屏幕捕获授权（不再自动截图翻页，避免截到本应用界面）
    LaunchedEffect(hasTriggeredAuth) {
        if (!isAuthorized && !hasTriggeredAuth) {
            hasTriggeredAuth = true
            isCapturing = true
            RecordingForegroundService.start(context)
            // Android 13+ 顺便请求通知权限（非阻塞）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            screenCaptureLauncher.launch(screenshotCapture.createScreenCaptureIntent())
        }
    }

    // 应用内「截图本页」按钮：截当前前台界面并发射到 guideCaptureFlow（通知栏按钮也走 requestCapture）
    fun triggerCapture() {
        val cap = app.screenshotCapture
        if (cap == null || !cap.isAuthorized()) {
            captureError = "请先授权屏幕捕获"
            return
        }
        isCapturing = true
        captureError = null
        scope.launch(Dispatchers.IO) {
            val b64 = cap.captureScreenshot()
            withContext(Dispatchers.Main) {
                isCapturing = false
                if (b64 != null) {
                    app.guideCaptureFlow.value = (currentPage?.key ?: "") to b64
                } else {
                    captureError = "截图失败，请确保屏幕已解锁后重试"
                }
            }
        }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose { screenshotCapture.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 进度指示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            pages.forEachIndexed { index, page ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .padding(horizontal = 2.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                index < currentPageIndex -> MaterialTheme.colorScheme.primary
                                index == currentPageIndex -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            }
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "第 ${currentPageIndex + 1} / ${totalPages} 页",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 引导卡片
        if (currentPage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        currentPage.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        currentPage.instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 目标 App 未安装提示
            if (!appInstalled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "未检测到 ${adapter?.platformName ?: "目标 App"}（包名 ${adapter?.appPackage}），请先在应用商店安装后再继续引导",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // 状态提示（等待授权 / 已授权）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (captureError != null)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在截图...", style = MaterialTheme.typography.bodySmall)
                        } else if (isAuthorized) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("已授权，可截图", style = MaterialTheme.typography.bodySmall)
                        } else if (captureError != null) {
                            Icon(Icons.Default.Error, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(captureError!!, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            // 出错后提供重试按钮
                            TextButton(onClick = {
                                captureError = null
                                isCapturing = true
                                RecordingForegroundService.start(context)
                                screenCaptureLauncher.launch(screenshotCapture.createScreenCaptureIntent())
                            }) {
                                Text("重试", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Icon(Icons.Default.Info, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("请授权屏幕捕获以开始截图", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (isAuthorized) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "在 ${adapter?.platformName ?: "目标 App"} 中导航到本页后，下拉通知栏点击「📸 截图本页」即可截取真实页面",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(8.dp))
                        // 展示已截取的页面，便于确认截到了正确的界面
                        lastCaptureBase64?.let { b64 ->
                            val bmp = remember(b64) { base64ToImageBitmap(b64) }
                            bmp?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = "已捕获的截图",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { triggerCapture() },
                            enabled = !isCapturing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCapturing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("截图中...")
                            } else {
                                Text("截图本页（应用内）")
                            }
                        }
                    }
                }
            }

            if (!currentPage.required) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onSkip, enabled = !isCapturing) {
                    Text("跳过此页（可选）")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 提示
        Text(
            "截图仅用于 AI 分析页面结构，不会上传到其他服务",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            textAlign = TextAlign.Center
        )
    }
}

/** 将 base64 PNG 解码为 Compose 可用的 ImageBitmap（用于引导页展示已截取的页面） */
private fun base64ToImageBitmap(b64: String): ImageBitmap? {
    return try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bmp?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun AnalyzingStep(
    currentPageIndex: Int,
    totalPages: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "AI 正在分析截图...",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "正在理解页面结构和可交互元素",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = if (totalPages > 0) currentPageIndex.toFloat() / totalPages else 0f,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "已分析 $currentPageIndex / $totalPages 张截图",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatePreviewStep(
    template: PlatformTemplate?,
    adapter: PlatformAdapter?,
    onConfirm: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "模板预览",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "AI 已根据截图分析生成操作模板，请确认",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (template != null) {
            // 模板摘要卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${template.platformName} v${template.version}", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("截图分析: ${template.screenshots.size} 张页面", style = MaterialTheme.typography.bodySmall)
                    Text("工作流: ${template.workflows.size} 个", style = MaterialTheme.typography.bodySmall)
                    Text("元素映射: ${template.elementMapping.size} 个", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 工作流列表
            Text("工作流列表", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))

            template.workflows.forEach { (name, workflow) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(workflow.description, style = MaterialTheme.typography.bodySmall)
                        Text("${workflow.steps.size} 步 · 参数: ${workflow.requiredParams.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            }

            // 验证结果
            Spacer(modifier = Modifier.height(12.dp))
            val validation = adapter?.validateTemplate(template)
            if (validation != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (validation.isValid)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        validation.message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("重新截图")
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("确认并保存")
            }
        }
    }
}

@Composable
private fun CompleteStep(
    adapter: PlatformAdapter?,
    template: PlatformTemplate?,
    errorMessage: String,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (template != null) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("配置完成！", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${adapter?.platformName} 的操作模板已生成",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "现在可以返回首页，选择 ${adapter?.platformName} 开始自动投递",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        } else {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("生成失败", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                errorMessage.ifEmpty { "未知错误，请重试" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text("完成")
        }
    }
}

// ====== 业务逻辑 ======

private suspend fun analyzeScreenshots(
    app: ResumePilotApp,
    adapter: PlatformAdapter,
    screenshots: List<Pair<String, String>>,
    onResult: (TemplateGenerationResult) -> Unit
) {
    try {
        val config = app.preferences.getLLMConfig()
        val llmClient = com.resumepilot.app.llm.LLMClient(config)

        val capturedScreenshots = screenshots.map { (key, base64) ->
            com.resumepilot.app.adapter.CapturedScreenshot(
                pageKey = key,
                imageBase64 = base64
            )
        }

        val result = adapter.generateTemplate(llmClient, capturedScreenshots)
        onResult(result)
    } catch (e: Exception) {
        onResult(com.resumepilot.app.adapter.TemplateGenerationResult(
            success = false,
            errorMessage = "分析异常: ${e.message}"
        ))
    }
}

private suspend fun saveTemplate(
    app: ResumePilotApp,
    template: PlatformTemplate
) {
    val entity = com.resumepilot.app.data.db.TemplateEntity(
        id = template.id,
        platformName = template.platformName,
        appPackage = template.appPackage,
        version = template.version,
        templateJson = template.toJson(),
        workflowCount = template.workflows.size,
        screenshotCount = template.screenshots.size,
        createdAt = template.createdAt,
        updatedAt = template.updatedAt,
        runCount = 0,
        repairCount = 0
    )
    app.database.templateDao().insertTemplate(entity)
}