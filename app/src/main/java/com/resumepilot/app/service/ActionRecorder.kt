package com.resumepilot.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.resumepilot.app.engine.Action
import com.resumepilot.app.engine.TrajectoryStep
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 动作录制器：监听用户操作，记录动作轨迹
 * 类似 AutoCMD 的录制功能，但记录的是语义级操作
 */
class ActionRecorder(private val service: AccessibilityService) {

    private var isRecording = false
    private val recordedSteps = mutableListOf<TrajectoryStep>()
    private var stepIndex = 0
    private var lastEventTime = 0L
    private var lastClickX = -1
    private var lastClickY = -1
    private var lastEventType = -1

    // 去重阈值（毫秒）
    private val DEBOUNCE_MS = 100L
    // 滑动检测阈值（像素）
    private val SWIPE_THRESHOLD = 50

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startRecording() {
        isRecording = true
        stepIndex = 0
        recordedSteps.clear()
        lastClickX = -1
        lastClickY = -1
    }

    fun stopRecording(): List<TrajectoryStep> {
        isRecording = false
        return recordedSteps.toList()
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * 处理无障碍事件，记录用户操作
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRecording) return

        val now = System.currentTimeMillis()
        if (now - lastEventTime < DEBOUNCE_MS) return
        lastEventTime = now

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                val rect = Rect()
                node.getBoundsInScreen(rect)

                val x = rect.centerX()
                val y = rect.centerY()

                // 去重：相同位置不重复记录
                if (abs(x - lastClickX) < 10 && abs(y - lastClickY) < 10) return
                lastClickX = x
                lastClickY = y

                val description = buildString {
                    node.text?.let { append("文本: $it, ") }
                    node.contentDescription?.let { append("描述: $it, ") }
                    append("位置: ($x, $y)")
                }

                val action = Action.Click(
                    x = x, y = y,
                    description = description
                )
                recordStep(action, node)
                node.recycle()
            }

            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val node = event.source ?: return
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val action = Action.LongClick(
                    x = rect.centerX(), y = rect.centerY(),
                    description = "长按: ${node.text ?: node.contentDescription ?: ""}"
                )
                recordStep(action, node)
                node.recycle()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = event.source ?: return
                val text = node.text?.toString() ?: return
                if (text.isNotEmpty() && text.length < 200) {
                    val action = Action.Type(
                        text = text,
                        description = "输入: $text"
                    )
                    recordStep(action, node)
                }
                node.recycle()
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val action = Action.Scroll(
                    direction = Action.ScrollDirection.DOWN,
                    description = "滚动"
                )
                recordStep(action, null)
            }
        }
    }

    private fun recordStep(action: Action, node: AccessibilityNodeInfo?) {
        val step = TrajectoryStep(
            stepIndex = stepIndex++,
            action = action,
            uiTreeSnapshot = node?.let { captureNodeInfo(it) }
        )
        recordedSteps.add(step)
    }

    /**
     * 捕获控件树快照
     */
    private fun captureNodeInfo(node: AccessibilityNodeInfo): String {
        return buildString {
            append("className=${node.className}, ")
            append("text=${node.text}, ")
            append("contentDesc=${node.contentDescription}, ")
            append("viewId=${node.viewIdResourceName}, ")
            val rect = Rect()
            node.getBoundsInScreen(rect)
            append("bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
        }
    }

    /**
     * 获取当前屏幕的完整控件树（用于 LLM 分析）
     */
    fun getUITreeSnapshot(): String {
        val root = service.rootInActiveWindow ?: return ""

        val sb = StringBuilder()
        dumpNodeTree(root, sb, 0)
        root.recycle()
        return sb.toString()
    }

    private fun dumpNodeTree(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 6) return  // 限制深度

        val indent = "  ".repeat(depth)
        val rect = Rect()
        node.getBoundsInScreen(rect)

        sb.append("$indent- ")
        node.text?.let { sb.append("text=\"$it\", ") }
        node.contentDescription?.let { sb.append("desc=\"$it\", ") }
        node.viewIdResourceName?.let { sb.append("id=\"$it\", ") }
        sb.append("class=${node.className?.split(".")?.last()}, ")
        sb.append("clickable=${node.isClickable}, ")
        sb.append("bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
        sb.append("\n")

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNodeTree(it, sb, depth + 1) }
        }
    }
}

/**
 * 动作执行器：将 Action 转为 AccessibilityService 手势执行
 */
class ActionExecutor(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isExecuting = false
    private var shouldStop = false

    fun stop() {
        shouldStop = true
    }

    fun isBusy(): Boolean = isExecuting

    /**
     * 执行单个动作
     */
    suspend fun execute(action: Action): Boolean = withContext(Dispatchers.Main) {
        if (shouldStop) return@withContext false
        isExecuting = true

        try {
            when (action) {
                is Action.Click -> {
                    val x = action.x + (Math.random() * action.randomOffset * 2 - action.randomOffset).toInt()
                    val y = action.y + (Math.random() * action.randomOffset * 2 - action.randomOffset).toInt()
                    performClick(x, y)
                }
                is Action.LongClick -> {
                    performLongClick(action.x, action.y, action.duration)
                }
                is Action.Swipe -> {
                    performSwipe(action.fromX, action.fromY, action.toX, action.toY, action.duration)
                }
                is Action.Type -> {
                    performType(action.text)
                }
                Action.Back -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(300)
                    true
                }
                Action.Home -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    delay(300)
                    true
                }
                is Action.Wait -> {
                    delay(action.millis)
                    true
                }
                is Action.FindAndClick -> {
                    findAndClick(action)
                }
                is Action.Scroll -> {
                    performScroll(action.direction)
                }
                is Action.LaunchApp -> {
                    val intent = service.packageManager.getLaunchIntentForPackage(action.packageName)
                    if (intent != null) {
                        service.startActivity(intent)
                        delay(action.waitMillis)
                        true
                    } else false
                }
                is Action.RunScript -> {
                    // 脚本嵌套由外部调度
                    true
                }
                is Action.LLMDecision -> {
                    // LLM 决策由外部调度
                    true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            isExecuting = false
        }
    }

    private fun performClick(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun performLongClick(x: Int, y: Int, duration: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun performType(text: String): Boolean {
        // 逐个字符输入，通过 AccessibilityNodeInfo 的 ACTION_PASTE 粘贴
        val root = service.rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root

        // 尝试找到可编辑的节点
        val targetNode = if (focused.isEditable) focused else findEditableNode(root)
        if (targetNode == null || !targetNode.isEditable) {
            // 降级方案：逐个字符输入
            for (char in text) {
                if (shouldStop) break
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, char.toString())
                }
                targetNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Thread.sleep(50 + (Math.random() * 100).toLong())
            }
            targetNode?.recycle()
            root.recycle()
            return true
        }

        // 通过 Clipboard + ACTION_PASTE 粘贴
        val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("text", text)
        clipboard.setPrimaryClip(clip)
        targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.getId())
        targetNode.recycle()
        root.recycle()
        return true
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    private fun findAndClick(action: Action.FindAndClick): Boolean {
        val root = service.rootInActiveWindow ?: return false

        // 按文本查找
        if (action.text != null) {
            val node = findNodeByText(root, action.text)
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                node.recycle()
                return performClick(rect.centerX(), rect.centerY())
            }
        }

        // 按 ID 查找
        if (action.id != null) {
            val node = findNodeByViewId(root, action.id)
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                node.recycle()
                return performClick(rect.centerX(), rect.centerY())
            }
        }

        root.recycle()
        return false
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        return root.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        return root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

    private fun performScroll(direction: Action.ScrollDirection): Boolean {
        // 通过查找可滚动的节点来执行滚动
        val root = service.rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        root.recycle()

        if (scrollable != null) {
            val actionType = when (direction) {
                Action.ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                Action.ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                Action.ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                Action.ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            scrollable.performAction(actionType)
            return true
        }
        return false
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val result = findScrollableNode(child)
                if (result != null) return result
            }
        }
        return null
    }
}