package com.resumepilot.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.resumepilot.app.ResumePilotApp
import com.resumepilot.app.engine.Action
import com.resumepilot.app.llm.mcp.tools.*
import com.resumepilot.app.service.ActionExecutor
import kotlinx.coroutines.*

/**
 * 核心无障碍服务：简历投递助手的"手脚"
 * 职责：
 *   1. 录制模式：监听用户操作 -> ActionRecorder
 *   2. 回放模式：执行 ActionSequence -> ActionExecutor
 *   3. 截图模式：为 LLM 提供屏幕截图
 */
class RPAAccessibilityService : AccessibilityService() {

    lateinit var recorder: ActionRecorder
    lateinit var executor: ActionExecutor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** 当前运行中的无障碍服务实例（未启用时为 null） */
        @Volatile
        var instance: RPAAccessibilityService? = null
            private set
    }

    // 执行状态
    var isReplaying = false
        private set
    var isRecording = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        recorder = ActionRecorder(this)
        executor = ActionExecutor(this)

        // 注册 MCP 工具（为 DeepSeek 等纯文本模型提供视觉能力）
        val app = ResumePilotApp.instance
        val mcpManager = app.mcpGatewayManager
        val service = this
        mcpManager.initialize {
            register(ScreenAnalysisTool(service))
            register(ScreenshotTool(service))
            register(ExecuteActionTool(service))
            register(FindElementTool(service))
            register(ScriptManagerTool(app.database))
            register(ResumeTool(app.resumeManager))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when {
            isRecording -> recorder.onAccessibilityEvent(event)
            // 回放模式不需要监听事件
        }
    }

    override fun onInterrupt() {
        executor.stop()
        isReplaying = false
        isRecording = false
    }

    /**
     * 开始录制
     */
    fun startRecording() {
        isRecording = true
        recorder.startRecording()
    }

    /**
     * 停止录制，返回轨迹
     */
    fun stopRecording() = recorder.stopRecording().also {
        isRecording = false
    }

    /**
     * 执行单个动作
     */
    suspend fun executeAction(action: Action): Boolean {
        return executor.execute(action)
    }

    /**
     * 执行一组动作序列（回放）
     */
    suspend fun executeActions(actions: List<Action>): ExecutionResult {
        isReplaying = true
        val results = mutableListOf<Pair<Action, Boolean>>()

        for ((index, action) in actions.withIndex()) {
            if (!isReplaying) break  // 被中断

            val success = executor.execute(action)
            results.add(action to success)

            if (!success) {
                return ExecutionResult(
                    success = false,
                    completedSteps = index,
                    totalSteps = actions.size,
                    failedAction = action,
                    results = results
                )
            }
        }

        isReplaying = false
        return ExecutionResult(
            success = true,
            completedSteps = results.size,
            totalSteps = actions.size,
            results = results
        )
    }

    fun stopReplaying() {
        executor.stop()
        isReplaying = false
    }

    /**
     * 获取当前屏幕控件树（用于 LLM 分析）
     */
    fun getUITree(): String {
        return recorder.getUITreeSnapshot()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        scope.cancel()
        super.onDestroy()
    }
}

data class ExecutionResult(
    val success: Boolean,
    val completedSteps: Int,
    val totalSteps: Int,
    val failedAction: Action? = null,
    val results: List<Pair<Action, Boolean>>
)