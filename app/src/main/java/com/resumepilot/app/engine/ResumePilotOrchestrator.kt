package com.resumepilot.app.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.resumepilot.app.autoscript.ScriptGenerator
import com.resumepilot.app.data.db.*
import com.resumepilot.app.llm.LLMClient
import com.resumepilot.app.llm.LLMConfig
import com.resumepilot.app.llm.LLMDecision
import com.resumepilot.app.service.RPAAccessibilityService
import com.resumepilot.app.service.ExecutionResult
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream

/**
 * ResumePilot 总调度器
 * 协调 LLM "大脑" 和 RPA "手脚" 的完整工作流
 *
 * 工作模式：
 *   1. EXPLORE: LLM 看屏幕做决策，记录轨迹
 *   2. GENERATE: 从轨迹生成可复用的 RPA 脚本
 *   3. REPLAY: 直接执行已有的 RPA 脚本（零 LLM 调用）
 *   4. HYBRID: 脚本执行 + LLM 异常处理混合
 */
class ResumePilotOrchestrator(
    private val context: Context,
    private val accessibilityService: RPAAccessibilityService?,
    private val db: AppDatabase,
    private val preferences: com.resumepilot.app.data.PreferencesManager
) {
    private var llmClient: LLMClient? = null
    private var scriptGenerator: ScriptGenerator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    // 状态流
    private val _status = MutableStateFlow(OrchestratorStatus.IDLE)
    val status: StateFlow<OrchestratorStatus> = _status

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log

    // 当前轨迹（探索模式）
    private val currentTrajectorySteps = mutableListOf<TrajectoryStep>()
    private var currentTaskDescription = ""
    private var currentAppPackage: String? = null

    // 截图工具
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var screenWidth = 0
    private var screenHeight = 0

    enum class OrchestratorStatus {
        IDLE,
        EXPLORING,      // LLM 探索模式
        GENERATING,     // 正在生成脚本
        REPLAYING,      // 脚本回放模式
        WAITING_CONFIRM, // 等待用户确认
        ERROR
    }

    fun initMediaProjection() {
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val metrics = context.getSystemService(Context.WINDOW_SERVICE).let {
            (it as WindowManager).defaultDisplay
        }
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.getRealMetrics(displayMetrics)
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(displayMetrics)
        }
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
    }

    fun updateLLMConfig(config: LLMConfig) {
        llmClient = LLMClient(config)
        scriptGenerator = ScriptGenerator(config)
    }

    private fun isVisionModel(): Boolean =
        llmClient?.config?.provider?.supportsVision ?: true

    /**
     * 模式1: EXPLORE — LLM 自主探索，记录轨迹
     */
    fun startExploring(taskDescription: String) = scope.launch {
        if (llmClient == null || accessibilityService == null) {
            _status.value = OrchestratorStatus.ERROR
            _log.value = "LLM 未配置或无障碍服务未连接"
            return@launch
        }

        _status.value = OrchestratorStatus.EXPLORING
        currentTaskDescription = taskDescription
        currentTrajectorySteps.clear()
        val previousActions = mutableListOf<String>()
        var stepCount = 0
        val maxSteps = preferences.maxStepsPerTask.first()

        appendLog("开始探索任务: $taskDescription" +
                if (isVisionModel()) "（视觉模式）" else "（纯文本模式，依赖控件树）")

        while (stepCount < maxSteps) {
            if (_status.value != OrchestratorStatus.EXPLORING) break

            // 1. 截图（仅视觉模型需要）
            val screenshot = if (isVisionModel()) {
                captureScreenshot().also {
                    if (it == null) {
                        appendLog("截图失败，降级为纯文本模式")
                    }
                }
            } else null

            // 2. 获取控件树
            val uiTree = accessibilityService.getUITree()

            // 3. LLM 决策下一步（纯文本模型内部会自动走 MCP 工具路径）
            val decision = llmClient!!.decideNextAction(
                taskDescription = taskDescription,
                screenshotBase64 = screenshot,
                uiTree = uiTree,
                previousActions = previousActions
            )

            when (decision) {
                is LLMDecision.TaskComplete -> {
                    appendLog("任务完成！")
                    break
                }
                is LLMDecision.Action -> {
                    val action = decision.action
                    appendLog("步骤 ${stepCount + 1}: ${action::class.simpleName} - ${action.description()}")

                    // 4. RPA 执行
                    val success = accessibilityService.executeAction(action)

                    // 5. 记录轨迹
                    currentTrajectorySteps.add(TrajectoryStep(
                        stepIndex = stepCount,
                        action = action,
                        success = success,
                        errorMessage = if (!success) "执行失败" else null
                    ))
                    previousActions.add("${action::class.simpleName}: ${action.description()}")

                    // 6. 等待
                    val waitMs = preferences.defaultWaitMs.first()
                    delay(waitMs)

                    stepCount++
                    if (!success) {
                        appendLog("动作执行失败，尝试恢复")
                        delay(1000)
                    }
                }
                is LLMDecision.NeedHelp -> {
                    _status.value = OrchestratorStatus.WAITING_CONFIRM
                    appendLog("需要用户帮助: ${decision.reason}")
                    break
                }
                is LLMDecision.Error -> {
                    appendLog("LLM 错误: ${decision.message}")
                    break
                }
            }
        }

        if (_status.value == OrchestratorStatus.EXPLORING) {
            _status.value = OrchestratorStatus.IDLE
            appendLog("探索结束，共 ${currentTrajectorySteps.size} 步")
        }
    }

    /**
     * 模式2: GENERATE — 从轨迹生成 RPA 脚本
     */
    fun generateScript(scriptName: String): Deferred<String?> = scope.async {
        _status.value = OrchestratorStatus.GENERATING
        appendLog("正在生成 RPA 脚本...")

        try {
            if (currentTrajectorySteps.isEmpty()) {
                appendLog("没有轨迹数据")
                _status.value = OrchestratorStatus.IDLE
                return@async null
            }

            val trajectory = Trajectory(
                taskDescription = currentTaskDescription,
                steps = currentTrajectorySteps.toList(),
                appPackageName = currentAppPackage
            )

            // 保存轨迹到数据库
            db.scriptDao().insertTrajectory(TrajectoryEntity(
                id = trajectory.id,
                taskDescription = trajectory.taskDescription,
                stepsJson = trajectory.toJson(),
                appPackage = currentAppPackage,
                createdAt = trajectory.createdAt,
                duration = trajectory.duration,
                stepCount = trajectory.steps.size
            ))

            // 生成脚本
            val yaml = if (llmClient != null) {
                // LLM 生成（智能）
                scriptGenerator?.generateFromTrajectory(trajectory) ?: ""
            } else {
                // 直接转换（无需 LLM）
                scriptGenerator?.generateFromActions(
                    name = scriptName,
                    description = currentTaskDescription,
                    actions = trajectory.steps.map { it.action },
                    appPackage = currentAppPackage
                ) ?: ""
            }

            // 保存脚本到数据库
            if (yaml.isNotEmpty()) {
                db.scriptDao().insertScript(ScriptEntity(
                    id = trajectory.id,
                    name = scriptName,
                    description = currentTaskDescription,
                    yamlContent = yaml,
                    sourceTrajectoryId = trajectory.id,
                    llmGenerated = llmClient != null,
                    appPackage = currentAppPackage,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    runCount = 0,
                    successCount = 0,
                    tags = "[]"
                ))
                appendLog("脚本生成成功: $scriptName")
            }

            _status.value = OrchestratorStatus.IDLE
            return@async yaml
        } catch (e: Exception) {
            appendLog("脚本生成失败: ${e.message}")
            _status.value = OrchestratorStatus.ERROR
            return@async null
        }
    }

    /**
     * 模式3: REPLAY — 执行已有脚本（零 LLM 调用）
     */
    fun replayScript(scriptId: String) = scope.launch {
        if (accessibilityService == null) {
            _status.value = OrchestratorStatus.ERROR
            return@launch
        }

        val scriptEntity = db.scriptDao().getScriptById(scriptId) ?: return@launch
        val scriptGen = scriptGenerator ?: return@launch

        _status.value = OrchestratorStatus.REPLAYING
        appendLog("开始回放脚本: ${scriptEntity.name}")

        // 解析 YAML 为 Action 列表
        val actions = scriptGen.parseToActions(scriptEntity.yamlContent)
        appendLog("共 ${actions.size} 步")

        // 记录执行日志
        val logId = db.scriptDao().insertLog(ExecutionLogEntity(
            scriptId = scriptEntity.id,
            scriptName = scriptEntity.name,
            startedAt = System.currentTimeMillis(),
            completedAt = null,
            success = false,
            errorMessage = null,
            stepsCompleted = 0,
            stepsTotal = actions.size
        ))

        // 执行
        val result = accessibilityService.executeActions(actions)

        // 更新日志和统计
        db.scriptDao().updateLog(
            id = logId,
            completedAt = System.currentTimeMillis(),
            success = result.success,
            error = result.failedAction?.toString(),
            stepsCompleted = result.completedSteps
        )
        db.scriptDao().updateRunCount(scriptEntity.id, if (result.success) 1 else 0)

        appendLog(if (result.success) "回放成功！" else "回放失败，第 ${result.completedSteps + 1} 步出错")
        _status.value = OrchestratorStatus.IDLE
    }

    /**
     * 模式4: HYBRID — 脚本执行中遇到异常时，LLM 介入修复
     */
    fun replayWithLLMFallback(scriptId: String) = scope.launch {
        if (accessibilityService == null || llmClient == null) {
            _status.value = OrchestratorStatus.ERROR
            return@launch
        }

        val scriptEntity = db.scriptDao().getScriptById(scriptId) ?: return@launch
        val scriptGen = scriptGenerator ?: return@launch
        val client = llmClient!!

        _status.value = OrchestratorStatus.REPLAYING
        appendLog("混合模式回放: ${scriptEntity.name}")

        var actions = scriptGen.parseToActions(scriptEntity.yamlContent)
        var retryCount = 0
        val maxRetries = 3

        while (retryCount < maxRetries) {
            val result = accessibilityService.executeActions(actions)

            if (result.success) {
                appendLog("混合模式回放成功！")
                break
            }

            // 执行失败，LLM 介入修复
            retryCount++
            appendLog("第 $retryCount 次失败，LLM 尝试修复...")

            val screenshot = if (isVisionModel()) captureScreenshot() else null
            val uiTree = accessibilityService.getUITree()

            val decision = client.decideNextAction(
                taskDescription = "修复自动化流程，当前在执行: ${scriptEntity.name}，第 ${result.completedSteps + 1} 步失败",
                screenshotBase64 = screenshot,
                uiTree = uiTree,
                previousActions = listOf("失败步骤: ${result.failedAction}")
            )

            when (decision) {
                is LLMDecision.Action -> {
                    // 用 LLM 的决策替换失败的动作
                    val remainingActions = actions.drop(result.completedSteps + 1)
                    actions = listOf(decision.action) + remainingActions
                    appendLog("LLM 修复: ${decision.action.description()}")
                }
                is LLMDecision.TaskComplete -> {
                    appendLog("LLM 认为任务已完成")
                    break
                }
                else -> {
                    appendLog("LLM 无法修复: $decision")
                    break
                }
            }
        }

        _status.value = OrchestratorStatus.IDLE
    }

    fun stop() {
        scope.launch {
            _status.value = OrchestratorStatus.IDLE
            accessibilityService?.stopReplaying()
        }
    }

    private fun captureScreenshot(): String? {
        // 通过 AccessibilityService 的 takeScreenshot API
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bitmap = accessibilityService?.let {
                    // 使用 MediaProjection 截图（需要用户授权）
                    null
                }
                // 备选：通过无障碍服务获取截图
                null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun appendLog(message: String) {
        _log.value += "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $message\n"
    }

    private fun Action.description(): String {
        return when (this) {
            is Action.Click -> "点击($x, $y) ${description}"
            is Action.Type -> "输入: $text"
            is Action.Swipe -> "滑动: ($fromX,$fromY)→($toX,$toY)"
            is Action.FindAndClick -> "查找并点击: $text"
            is Action.Scroll -> "滚动: ${direction}"
            Action.Back -> "返回"
            Action.Home -> "主页"
            is Action.Wait -> "等待 ${millis}ms"
            is Action.LaunchApp -> "打开应用: $packageName"
            else -> toString()
        }
    }
}

/**
 * 脚本引擎：YAML 脚本解析 + 执行 + 状态管理
 */
class ScriptEngine(
    private val accessibilityService: RPAAccessibilityService?,
    private val scriptGenerator: ScriptGenerator
) {
    private var currentScript: String? = null
    private var currentActions: List<Action> = emptyList()

    fun loadScript(yamlContent: String): Boolean {
        val validation = scriptGenerator.validate(yamlContent)
        if (!validation.isValid) return false
        currentScript = yamlContent
        currentActions = scriptGenerator.parseToActions(yamlContent)
        return true
    }

    fun getActions(): List<Action> = currentActions
    fun getStepCount(): Int = currentActions.size

    suspend fun execute(): ExecutionResult? {
        if (accessibilityService == null || currentActions.isEmpty()) return null
        return accessibilityService.executeActions(currentActions)
    }
}