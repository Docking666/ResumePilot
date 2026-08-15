package com.autorpa.app.autoscript

import com.autorpa.app.engine.Action
import com.autorpa.app.engine.Trajectory
import com.autorpa.app.llm.LLMClient
import com.autorpa.app.llm.LLMConfig

/**
 * AutoRPA 脚本生成器
 * 核心功能：将 LLM 探索轨迹 → 可复用的 RPA 脚本（YAML）
 *
 * 流程：
 *   Explore → LLM 看屏幕做决策 → 记录轨迹 →
 *   Generate → LLM 分析轨迹生成 YAML 脚本 →
 *   Verify → 执行脚本验证 → 修复 → 交付
 */
class ScriptGenerator(private val llmConfig: LLMConfig) {

    private val llmClient = LLMClient(llmConfig)

    /**
     * 从轨迹生成 YAML 脚本
     * 硬编码坐标 → 软编码语义操作
     */
    suspend fun generateFromTrajectory(trajectory: Trajectory): String {
        // 1. 用 LLM 生成脚本
        val yamlScript = llmClient.generateScriptFromTrajectory(
            taskDescription = trajectory.taskDescription,
            trajectoryJson = trajectory.toJson(),
            appPackage = trajectory.appPackageName
        )

        // 2. 后处理：确保格式正确
        return postProcess(yamlScript)
    }

    /**
     * 从动作列表直接生成脚本（不依赖 LLM）
     */
    fun generateFromActions(
        name: String,
        description: String,
        actions: List<Action>,
        appPackage: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("# AutoRPA 自动生成脚本\n")
        sb.append("# 生成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n\n")
        sb.append("name: \"$name\"\n")
        sb.append("description: \"$description\"\n")
        if (appPackage != null) {
            sb.append("app: \"$appPackage\"\n")
        }
        sb.append("version: 1\n")
        sb.append("steps:\n")

        actions.forEachIndexed { index, action ->
            when (action) {
                is Action.Click -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: click\n")
                    sb.append("    x: ${action.x}\n")
                    sb.append("    y: ${action.y}\n")
                    if (action.description.isNotEmpty()) {
                        sb.append("    description: \"${action.description}\"\n")
                    }
                    sb.append("    random_offset: ${action.randomOffset}\n")
                }
                is Action.LongClick -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: long_click\n")
                    sb.append("    x: ${action.x}\n")
                    sb.append("    y: ${action.y}\n")
                    sb.append("    duration: ${action.duration}\n")
                }
                is Action.Swipe -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: swipe\n")
                    sb.append("    from_x: ${action.fromX}\n")
                    sb.append("    from_y: ${action.fromY}\n")
                    sb.append("    to_x: ${action.toX}\n")
                    sb.append("    to_y: ${action.toY}\n")
                    sb.append("    duration: ${action.duration}\n")
                }
                is Action.Type -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: type\n")
                    sb.append("    text: \"${action.text}\"\n")
                }
                Action.Back -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: back\n")
                }
                Action.Home -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: home\n")
                }
                is Action.Wait -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: wait\n")
                    sb.append("    millis: ${action.millis}\n")
                }
                is Action.FindAndClick -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: find_and_click\n")
                    action.text?.let { sb.append("    text: \"$it\"\n") }
                    action.id?.let { sb.append("    view_id: \"$it\"\n") }
                    sb.append("    fallback_ocr: ${action.fallbackOcr}\n")
                }
                is Action.Scroll -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: scroll\n")
                    sb.append("    direction: ${action.direction.name.lowercase()}\n")
                    sb.append("    times: ${action.times}\n")
                }
                is Action.LaunchApp -> {
                    sb.append("  - id: step_${index}\n")
                    sb.append("    action: launch_app\n")
                    sb.append("    package: \"${action.packageName}\"\n")
                    sb.append("    wait: ${action.waitMillis}\n")
                }
                else -> {} // 其他类型暂不处理
            }
            sb.append("    wait_after: 500\n")  // 默认每个动作后等待
        }

        return sb.toString()
    }

    /**
     * 验证 YAML 脚本格式
     */
    fun validate(yamlContent: String): ValidationResult {
        return try {
            // 基础校验
            if (!yamlContent.contains("name:")) {
                return ValidationResult(false, "缺少 name 字段")
            }
            if (!yamlContent.contains("steps:")) {
                return ValidationResult(false, "缺少 steps 字段")
            }

            // 检查每步是否有 action 字段
            val lines = yamlContent.lines()
            var inSteps = false
            var hasAction = false
            var stepCount = 0

            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed == "steps:" -> inSteps = true
                    inSteps && trimmed.startsWith("- id:") -> {
                        if (hasAction) stepCount++
                        hasAction = false
                    }
                    inSteps && trimmed.startsWith("action:") -> hasAction = true
                }
            }
            if (hasAction) stepCount++

            if (stepCount == 0) {
                return ValidationResult(false, "steps 中没有有效的动作")
            }

            ValidationResult(true, "校验通过，共 $stepCount 步")
        } catch (e: Exception) {
            ValidationResult(false, "格式错误: ${e.message}")
        }
    }

    /**
     * YAML 脚本 → Action 列表
     */
    fun parseToActions(yamlContent: String): List<Action> {
        val actions = mutableListOf<Action>()
        val lines = yamlContent.lines()
        var inSteps = false
        var currentAction: MutableMap<String, String>? = null

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed == "steps:" -> inSteps = true
                inSteps && trimmed.startsWith("- id:") -> {
                    currentAction?.let { parseAction(it)?.let { a -> actions.add(a) } }
                    currentAction = mutableMapOf()
                }
                inSteps && currentAction != null && trimmed.contains(":") -> {
                    val colonIndex = trimmed.indexOf(':')
                    if (colonIndex > 0) {
                        val key = trimmed.substring(0, colonIndex).trim()
                        val value = trimmed.substring(colonIndex + 1).trim().removeSurrounding("\"")
                        currentAction[key] = value
                    }
                }
            }
        }
        currentAction?.let { parseAction(it)?.let { a -> actions.add(a) } }

        return actions
    }

    private fun parseAction(map: Map<String, String>): Action? {
        val type = map["action"] ?: return null
        return when (type.lowercase()) {
            "click" -> Action.Click(
                x = map["x"]?.toIntOrNull() ?: 0,
                y = map["y"]?.toIntOrNull() ?: 0,
                description = map["description"] ?: "",
                randomOffset = map["random_offset"]?.toIntOrNull() ?: 5
            )
            "long_click" -> Action.LongClick(
                x = map["x"]?.toIntOrNull() ?: 0,
                y = map["y"]?.toIntOrNull() ?: 0,
                duration = map["duration"]?.toLongOrNull() ?: 1000
            )
            "swipe" -> Action.Swipe(
                fromX = map["from_x"]?.toIntOrNull() ?: 0,
                fromY = map["from_y"]?.toIntOrNull() ?: 0,
                toX = map["to_x"]?.toIntOrNull() ?: 0,
                toY = map["to_y"]?.toIntOrNull() ?: 0,
                duration = map["duration"]?.toLongOrNull() ?: 300
            )
            "type" -> Action.Type(text = map["text"] ?: "")
            "back" -> Action.Back
            "home" -> Action.Home
            "wait" -> Action.Wait(millis = map["millis"]?.toLongOrNull() ?: 1000)
            "find_and_click" -> Action.FindAndClick(
                text = map["text"],
                id = map["view_id"],
                fallbackOcr = map["fallback_ocr"]?.toBooleanStrictOrNull() ?: true
            )
            "scroll" -> Action.Scroll(
                direction = if (map["direction"] == "up")
                    Action.ScrollDirection.UP else Action.ScrollDirection.DOWN,
                times = map["times"]?.toIntOrNull() ?: 1
            )
            "launch_app" -> Action.LaunchApp(
                packageName = map["package"] ?: "",
                waitMillis = map["wait"]?.toLongOrNull() ?: 3000
            )
            else -> null
        }
    }

    private fun postProcess(yaml: String): String {
        // 清理 markdown 代码块标记
        return yaml
            .replace("```yaml", "")
            .replace("```yml", "")
            .replace("```", "")
            .trim()
    }

    data class ValidationResult(
        val isValid: Boolean,
        val message: String
    )
}