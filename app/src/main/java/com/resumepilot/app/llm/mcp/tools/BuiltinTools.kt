package com.resumepilot.app.llm.mcp.tools

import com.resumepilot.app.llm.mcp.*
import com.resumepilot.app.service.RPAAccessibilityService

/**
 * 屏幕分析工具：LLM 通过此工具了解当前屏幕内容
 * 综合视觉 + 控件树两路信息
 */
class ScreenAnalysisTool(
    private val accessibilityService: RPAAccessibilityService?
) : MCPTool {

    override val name = "analyze_screen"
    override val description = "分析当前手机屏幕内容，返回结构化描述和可交互元素列表"

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "detail_level" to mapOf(
                "type" to "string",
                "enum" to listOf("low", "medium", "high"),
                "description" to "分析详细程度"
            ),
            "include_ui_tree" to mapOf(
                "type" to "boolean",
                "description" to "是否包含控件树信息"
            )
        )
    )

    override suspend fun execute(params: Map<String, Any>): MCPToolResult {
        val detailLevel = params["detail_level"] as? String ?: "medium"
        val includeUITree = params["include_ui_tree"] as? Boolean ?: true

        if (accessibilityService == null) {
            return MCPToolResult.Error("无障碍服务未连接")
        }

        val uiTree = if (includeUITree) {
            accessibilityService.getUITree()
        } else ""

        // 获取根窗口控件树做结构化分析
        val root = accessibilityService.rootInActiveWindow
        val elements = mutableListOf<Map<String, Any>>()

        root?.let { r ->
            extractClickableElements(r, elements, 0,
                if (detailLevel == "high") 8 else if (detailLevel == "medium") 5 else 3
            )
            r.recycle()
        }

        return MCPToolResult.Success(mapOf(
            "element_count" to elements.size,
            "elements" to elements,
            "ui_tree" to uiTree,
            "detail_level" to detailLevel
        ))
    }

    private fun extractClickableElements(
        node: android.view.accessibility.AccessibilityNodeInfo,
        result: MutableList<Map<String, Any>>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        val entry = mutableMapOf<String, Any>(
            "bounds" to "[${rect.left},${rect.top},${rect.right},${rect.bottom}]",
            "center_x" to rect.centerX(),
            "center_y" to rect.centerY(),
            "class" to (node.className?.split(".")?.last() ?: "Unknown"),
            "clickable" to node.isClickable,
            "scrollable" to node.isScrollable,
            "editable" to node.isEditable,
            "depth" to depth
        )

        node.text?.let { entry["text"] = it.toString() }
        node.contentDescription?.let { entry["content_description"] = it.toString() }
        node.viewIdResourceName?.let { entry["view_id"] = it.toString() }

        // 只保留有意义的元素
        if (node.isClickable || node.isEditable || node.isScrollable ||
            node.text?.isNotEmpty() == true || node.contentDescription?.isNotEmpty() == true
        ) {
            result.add(entry)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                extractClickableElements(it, result, depth + 1, maxDepth)
            }
        }
    }
}

/**
 * 截图工具：获取当前屏幕截图（Base64）
 */
class ScreenshotTool(
    private val accessibilityService: RPAAccessibilityService?
) : MCPTool {

    override val name = "capture_screenshot"
    override val description = "获取当前手机屏幕截图（Base64编码），用于多模态模型视觉理解"

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "quality" to mapOf(
                "type" to "integer",
                "description" to "截图质量 1-100"
            )
        )
    )

    override suspend fun execute(params: Map<String, Any>): MCPToolResult {
        // 截图依赖 MediaProjection 授权：复用全局 ScreenshotCapture（引导/执行页授权后写入）
        val capture = com.resumepilot.app.ResumePilotApp.instance.screenshotCapture
        if (capture == null) {
            return MCPToolResult.Error("截图功能未就绪：请先在「引导」或「执行」页完成屏幕捕获授权")
        }
        if (!capture.isAuthorized()) {
            return MCPToolResult.Error("屏幕捕获未授权或会话已失效，请重新授权")
        }
        val base64 = capture.captureScreenshot()
        if (base64 == null) {
            return MCPToolResult.Error("截图失败：请确保屏幕已解锁且未锁屏")
        }
        return MCPToolResult.Success(mapOf(
            "status" to "ok",
            "format" to "PNG",
            "image_base64" to base64
        ))
    }
}

/**
 * 执行动作工具：LLM 通过此工具控制手机执行操作
 */
class ExecuteActionTool(
    private val accessibilityService: RPAAccessibilityService?
) : MCPTool {

    override val name = "execute_action"
    override val description = "在手机上执行一个操作（点击、滑动、输入、返回等）"

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("action_type"),
        "properties" to mapOf(
            "action_type" to mapOf(
                "type" to "string",
                "enum" to listOf("click", "long_click", "swipe", "type", "back", "home", "scroll", "wait"),
                "description" to "操作类型"
            ),
            "x" to mapOf("type" to "integer", "description" to "点击/滑动起点 X 坐标"),
            "y" to mapOf("type" to "integer", "description" to "点击/滑动起点 Y 坐标"),
            "to_x" to mapOf("type" to "integer", "description" to "滑动终点 X 坐标"),
            "to_y" to mapOf("type" to "integer", "description" to "滑动终点 Y 坐标"),
            "text" to mapOf("type" to "string", "description" to "输入文本内容"),
            "direction" to mapOf(
                "type" to "string",
                "enum" to listOf("up", "down", "left", "right"),
                "description" to "滚动方向"
            ),
            "millis" to mapOf("type" to "integer", "description" to "等待毫秒数"),
            "description" to mapOf("type" to "string", "description" to "操作描述（仅用于日志）")
        )
    )

    override suspend fun execute(params: Map<String, Any>): MCPToolResult {
        if (accessibilityService == null) {
            return MCPToolResult.Error("无障碍服务未连接")
        }

        val actionType = params["action_type"] as? String ?: return MCPToolResult.Error("缺少 action_type")
        val description = params["description"] as? String ?: ""

        val action = when (actionType) {
            "click" -> com.resumepilot.app.engine.Action.Click(
                x = (params["x"] as? Number)?.toInt() ?: 0,
                y = (params["y"] as? Number)?.toInt() ?: 0,
                description = description
            )
            "long_click" -> com.resumepilot.app.engine.Action.LongClick(
                x = (params["x"] as? Number)?.toInt() ?: 0,
                y = (params["y"] as? Number)?.toInt() ?: 0,
                description = description
            )
            "swipe" -> com.resumepilot.app.engine.Action.Swipe(
                fromX = (params["x"] as? Number)?.toInt() ?: 0,
                fromY = (params["y"] as? Number)?.toInt() ?: 0,
                toX = (params["to_x"] as? Number)?.toInt() ?: 0,
                toY = (params["to_y"] as? Number)?.toInt() ?: 0,
                description = description
            )
            "type" -> com.resumepilot.app.engine.Action.Type(
                text = params["text"] as? String ?: "",
                description = description
            )
            "back" -> com.resumepilot.app.engine.Action.Back
            "home" -> com.resumepilot.app.engine.Action.Home
            "scroll" -> com.resumepilot.app.engine.Action.Scroll(
                direction = if ((params["direction"] as? String) == "up")
                    com.resumepilot.app.engine.Action.ScrollDirection.UP
                else com.resumepilot.app.engine.Action.ScrollDirection.DOWN,
                description = description
            )
            "wait" -> com.resumepilot.app.engine.Action.Wait(
                millis = (params["millis"] as? Number)?.toLong() ?: 1000,
                description = description
            )
            else -> return MCPToolResult.Error("不支持的操作类型: $actionType")
        }

        val success = accessibilityService.executeAction(action)
        return if (success) {
            MCPToolResult.Success(mapOf("status" to "ok", "action" to actionType))
        } else {
            MCPToolResult.Error("操作执行失败: $actionType")
        }
    }
}

/**
 * 查找控件工具：通过文本/ID 查找屏幕上可交互的控件
 */
class FindElementTool(
    private val accessibilityService: RPAAccessibilityService?
) : MCPTool {

    override val name = "find_element"
    override val description = "在屏幕上查找符合条件的UI控件，返回控件位置和信息"

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "text" to mapOf("type" to "string", "description" to "按文本查找"),
            "text_contains" to mapOf("type" to "string", "description" to "按文本包含查找"),
            "view_id" to mapOf("type" to "string", "description" to "按 View ID 查找"),
            "clickable_only" to mapOf("type" to "boolean", "description" to "只查找可点击元素")
        )
    )

    override suspend fun execute(params: Map<String, Any>): MCPToolResult {
        if (accessibilityService == null) return MCPToolResult.Error("无障碍服务未连接")

        val root = accessibilityService.rootInActiveWindow ?: return MCPToolResult.Error("无法获取窗口控件树")
        val results = mutableListOf<Map<String, Any>>()
        val clickableOnly = params["clickable_only"] as? Boolean ?: true

        searchNode(root, params, results, clickableOnly, 0)
        root.recycle()

        if (results.isEmpty()) {
            return MCPToolResult.Success(mapOf(
                "found" to false,
                "message" to "未找到匹配的控件",
                "elements" to emptyList<Map<String, Any>>()
            ))
        }

        return MCPToolResult.Success(mapOf(
            "found" to true,
            "count" to results.size,
            "elements" to results
        ))
    }

    private fun searchNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        params: Map<String, Any>,
        results: MutableList<Map<String, Any>>,
        clickableOnly: Boolean,
        depth: Int
    ) {
        if (depth > 10 || results.size >= 10) return
        if (clickableOnly && !node.isClickable && !node.isEditable) {
            // 继续查找子节点
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { searchNode(it, params, results, clickableOnly, depth + 1) }
            }
            return
        }

        var matched = false
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName

        params["text"]?.let { target ->
            if (text == target || desc == target) matched = true
        }
        params["text_contains"]?.let { target ->
            val t = target as String
            if (text?.contains(t, ignoreCase = true) == true ||
                desc?.contains(t, ignoreCase = true) == true) matched = true
        }
        params["view_id"]?.let { target ->
            if (viewId == target) matched = true
        }

        // 无筛选条件则返回所有可点击元素
        if (params.isEmpty() && node.isClickable) matched = true

        if (matched) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            results.add(mapOf(
                "text" to (text ?: ""),
                "content_description" to (desc ?: ""),
                "view_id" to (viewId ?: ""),
                "bounds" to "[${rect.left},${rect.top},${rect.right},${rect.bottom}]",
                "center_x" to rect.centerX(),
                "center_y" to rect.centerY(),
                "clickable" to node.isClickable,
                "scrollable" to node.isScrollable,
                "editable" to node.isEditable,
                "depth" to depth
            ))
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { searchNode(it, params, results, clickableOnly, depth + 1) }
        }
    }
}

/**
 * 脚本管理工具：管理和执行已保存的 RPA 脚本
 */
class ScriptManagerTool(
    private val db: com.resumepilot.app.data.db.AppDatabase
) : MCPTool {

    override val name = "manage_scripts"
    override val description = "管理RPA自动化脚本：列出、查看、删除脚本"

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("operation"),
        "properties" to mapOf(
            "operation" to mapOf(
                "type" to "string",
                "enum" to listOf("list", "get", "delete", "create"),
                "description" to "操作类型"
            ),
            "script_id" to mapOf("type" to "string", "description" to "脚本ID"),
            "name" to mapOf("type" to "string", "description" to "脚本名称"),
            "yaml_content" to mapOf("type" to "string", "description" to "脚本内容")
        )
    )

    override suspend fun execute(params: Map<String, Any>): MCPToolResult {
        return when (params["operation"] as? String) {
            "list" -> {
                val scripts = db.scriptDao().getAllScripts()
                MCPToolResult.Success(mapOf(
                    "scripts" to scripts.map { mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "description" to it.description,
                        "llm_generated" to it.llmGenerated,
                        "run_count" to it.runCount,
                        "success_count" to it.successCount,
                        "created_at" to it.createdAt
                    )},
                    "count" to scripts.size
                ))
            }
            "get" -> {
                val id = params["script_id"] as? String ?: return MCPToolResult.Error("缺少 script_id")
                val script = db.scriptDao().getScriptById(id)
                if (script == null) MCPToolResult.Error("脚本不存在")
                else MCPToolResult.Success(mapOf(
                    "id" to script.id,
                    "name" to script.name,
                    "yaml_content" to script.yamlContent,
                    "run_count" to script.runCount
                ))
            }
            "delete" -> {
                val id = params["script_id"] as? String ?: return MCPToolResult.Error("缺少 script_id")
                val script = db.scriptDao().getScriptById(id) ?: return MCPToolResult.Error("脚本不存在")
                db.scriptDao().deleteScript(script)
                MCPToolResult.Success(mapOf("status" to "deleted"))
            }
            "create" -> {
                val name = params["name"] as? String ?: return MCPToolResult.Error("缺少 name")
                val yaml = params["yaml_content"] as? String ?: return MCPToolResult.Error("缺少 yaml_content")
                db.scriptDao().insertScript(com.resumepilot.app.data.db.ScriptEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    description = "",
                    yamlContent = yaml,
                    sourceTrajectoryId = null,
                    llmGenerated = true,
                    appPackage = null,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    runCount = 0,
                    successCount = 0,
                    tags = "[]"
                ))
                MCPToolResult.Success(mapOf("status" to "created"))
            }
            else -> MCPToolResult.Error("未知操作")
        }
    }
}

/**
 * 简历工具：读取简历信息供 LLM 生成打招呼语
 */
class ResumeTool(
    private val resumeManager: com.resumepilot.app.resume.ResumeManager
) : MCPTool {

    override val name = "read_resume"
    override val description = "读取当前用户的简历信息，包括个人信息、技能、工作经历、项目经验等"

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "section" to mapOf(
                "type" to "string",
                "enum" to listOf("all", "skills", "experience", "education", "projects", "summary"),
                "description" to "要读取的简历部分"
            )
        )
    )

    override suspend fun execute(params: Map<String, Any>): MCPToolResult {
        val resume = resumeManager.getActiveResume() ?: return MCPToolResult.Error("用户尚未上传简历")
        val section = params["section"] as? String ?: "all"

        val data = when (section) {
            "all" -> resume.toMap()
            "skills" -> mapOf("skills" to resume.skills)
            "experience" -> mapOf("experience" to resume.workExperience)
            "education" -> mapOf("education" to resume.education)
            "projects" -> mapOf("projects" to resume.projects)
            "summary" -> mapOf("summary" to resume.summary)
            else -> resume.toMap()
        }

        return MCPToolResult.Success(data)
    }
}