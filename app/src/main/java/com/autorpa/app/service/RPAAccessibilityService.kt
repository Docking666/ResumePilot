package com.autorpa.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autorpa.app.engine.Action
import com.autorpa.app.engine.ActionExecutor
import kotlinx.coroutines.*

/**
 * 核心无障碍服务：AutoRPA 的"手脚"
 * 职责：
 *   1. 录制模式：监听用户操作 -> ActionRecorder
 *   2. 回放模式：执行 ActionSequence -> ActionExecutor
 *   3. 截图模式：为 LLM 提供屏幕截图
 */
class RPAAccessibilityService : AccessibilityService() {

    lateinit var recorder: ActionRecorder
    lateinit var executor: ActionExecutor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 执行状态
    var isReplaying = false
        private set
    var isRecording = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        recorder = ActionRecorder(this)
        executor = ActionExecutor(this)
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