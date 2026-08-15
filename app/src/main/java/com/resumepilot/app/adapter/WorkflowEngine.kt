package com.resumepilot.app.adapter

import com.resumepilot.app.engine.Action
import com.resumepilot.app.llm.LLMClient
import com.resumepilot.app.llm.LLMDecision
import com.resumepilot.app.service.RPAAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 工作流引擎——执行 PlatformTemplate 中定义的工作流
 *
 * 核心能力：
 * 1. 执行模板中的步骤序列
 * 2. 步骤失败时自动修复（LLM 视觉兜底）
 * 3. 收集执行过程中的数据（岗位列表、投递结果）
 * 4. 注入随机延迟防检测
 */
class WorkflowEngine(
    private val accessibilityService: RPAAccessibilityService?,
    private val llmClient: LLMClient?
) {

    /**
     * 执行工作流
     */
    suspend fun execute(
        template: PlatformTemplate,
        workflowName: String,
        params: Map<String, String> = emptyMap()
    ): WorkflowResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val workflow = template.workflows[workflowName]

        if (workflow == null) {
            return@withContext WorkflowResult(
                success = false,
                workflowName = workflowName,
                errorMessage = "工作流 '$workflowName' 未在模板中定义"
            )
        }

        if (accessibilityService == null) {
            return@withContext WorkflowResult(
                success = false,
                workflowName = workflowName,
                errorMessage = "无障碍服务未连接"
            )
        }

        var completedSteps = 0
        var wasRepaired = false
        val collectedData = mutableMapOf<String, Any>()

        for (step in workflow.steps) {
            var stepSuccess = false
            var retryCount = 0

            while (!stepSuccess && retryCount <= step.maxRetries) {
                // 注入随机延迟防检测
                if (retryCount > 0) {
                    delay(Random.nextLong(1000, 3000))
                }

                stepSuccess = executeStep(step, params, template)

                if (!stepSuccess) {
                    retryCount++
                    // 尝试自动修复
                    if (retryCount <= step.maxRetries && llmClient != null) {
                        val repaired = autoRepair(step, template)
                        if (repaired) {
                            wasRepaired = true
                            stepSuccess = true
                        }
                    }
                }
            }

            if (!stepSuccess) {
                return@withContext WorkflowResult(
                    success = false,
                    workflowName = workflowName,
                    stepsCompleted = completedSteps,
                    totalSteps = workflow.steps.size,
                    failedStep = step,
                    errorMessage = "步骤 ${step.id} (${step.action}) 执行失败，已重试 ${retryCount} 次",
                    durationMs = System.currentTimeMillis() - startTime,
                    repaired = wasRepaired
                )
            }

            completedSteps++

            // 步骤间随机延迟（防检测）
            delay(Random.nextLong(step.waitAfter, step.waitAfter + 500))
        }

        WorkflowResult(
            success = true,
            workflowName = workflowName,
            stepsCompleted = completedSteps,
            totalSteps = workflow.steps.size,
            durationMs = System.currentTimeMillis() - startTime,
            repaired = wasRepaired,
            collectedData = collectedData
        )
    }

    /**
     * 执行单个步骤
     */
    private suspend fun executeStep(
        step: WorkflowStep,
        params: Map<String, String>,
        template: PlatformTemplate
    ): Boolean {
        val action = stepToAction(step, params, template) ?: return false
        return accessibilityService?.executeAction(action) ?: false
    }

    /**
     * 将 WorkflowStep 转为可执行的 Action
     * 支持参数替换：{keyword} → params["keyword"]
     */
    private fun stepToAction(
        step: WorkflowStep,
        params: Map<String, String>,
        template: PlatformTemplate
    ): Action? {
        return when (step.action) {
            "find_and_click" -> {
                val target = step.target ?: return null
                val mapping = template.elementMapping[target]
                Action.FindAndClick(
                    text = mapping?.let { extractText(it) }
                        ?: step.text,
                    fallbackOcr = true
                )
            }
            "click" -> {
                if (step.target != null) {
                    Action.FindAndClick(
                        text = step.target,
                        fallbackOcr = true
                    )
                } else {
                    null
                }
            }
            "type" -> {
                val text = resolveParam(step.text ?: "", params)
                Action.Type(text = text)
            }
            "scroll" -> {
                val direction = if (step.direction == "up")
                    Action.ScrollDirection.UP
                else
                    Action.ScrollDirection.DOWN
                Action.Scroll(direction = direction)
            }
            "wait" -> Action.Wait(millis = step.millis ?: 500)
            "launch_app" -> Action.LaunchApp(
                packageName = template.appPackage,
                waitMillis = step.millis ?: 3000
            )
            "back" -> Action.Back
            "home" -> Action.Home
            else -> null
        }
    }

    /**
     * 解析参数占位符：替换 {keyword} 为 params["keyword"]
     */
    private fun resolveParam(text: String, params: Map<String, String>): String {
        var result = text
        params.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        return result
    }

    /**
     * 从 elementMapping 的 value 中提取文本定位信息
     */
    private fun extractText(mappingValue: String): String? {
        // 格式: "text=搜索" 或 "view_id=com.xxx:id/btn"
        if (mappingValue.startsWith("text=")) {
            return mappingValue.substringAfter("text=")
        }
        return null
    }

    /**
     * LLM 自动修复：当模板步骤失效时
     * 1. 截图当前屏幕
     * 2. 问 LLM 如何找到目标元素
     * 3. 执行 LLM 的决策
     */
    private suspend fun autoRepair(
        failedStep: WorkflowStep,
        template: PlatformTemplate
    ): Boolean {
        if (llmClient == null || accessibilityService == null) return false

        return try {
            // 获取当前屏幕截图和控件树
            val uiTree = accessibilityService.getUITree()

            val decision = llmClient.decideNextAction(
                taskDescription = "当前在执行${template.platformName}的自动化流程，"
                        + "步骤 ${failedStep.id} (${failedStep.action}) 失败。"
                        + "目标: ${failedStep.target ?: failedStep.text}",
                screenshotBase64 = "", // 截图需要外部传入
                uiTree = uiTree
            )

            when (decision) {
                is LLMDecision.Action -> {
                    accessibilityService.executeAction(decision.action)
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 批量执行工作流（搜索 → 投递 循环）
     */
    suspend fun executeBatch(
        template: PlatformTemplate,
        keyword: String,
        city: String = "",
        greetingText: String,
        maxApplications: Int = 30
    ): List<WorkflowResult> {
        val results = mutableListOf<WorkflowResult>()

        // 1. 搜索岗位
        val searchResult = execute(
            template = template,
            workflowName = "search_jobs",
            params = mapOf("keyword" to keyword, "city" to city)
        )
        results.add(searchResult)

        if (!searchResult.success) {
            results.add(WorkflowResult(
                success = false,
                workflowName = "batch_apply",
                errorMessage = "搜索失败，无法继续投递"
            ))
            return results
        }

        // 2. 逐个投递
        for (i in 0 until maxApplications) {
            val applyResult = execute(
                template = template,
                workflowName = "apply_job",
                params = mapOf("greeting_text" to greetingText)
            )
            results.add(applyResult)

            if (!applyResult.success) {
                // 投递失败，可能已到底或网络问题，继续尝试
                delay(1000)
                continue
            }

            // 投递间隔防检测
            delay(Random.nextLong(30_000, 90_000))
        }

        return results
    }
}