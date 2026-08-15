package com.resumepilot.app.adapter.boss

import com.resumepilot.app.adapter.*
import com.resumepilot.app.llm.LLMClient
import com.resumepilot.app.llm.LLMDecision
import com.google.gson.Gson

/**
 * BOSS直聘平台适配器
 *
 * 预置 BOSS直聘 App 的引导截图配置和业务知识。
 * 模板由 LLM 首次分析截图时自动生成，非硬编码。
 */
class BossAdapter : PlatformAdapter {

    override val platformName = "BOSS直聘"
    override val appPackage = "com.hpbr.bosszhipin"
    override val adapterVersion = 1

    override val guideConfig = GuideConfig(
        platformName = platformName,
        appPackage = appPackage,
        pages = listOf(
            GuidePage(
                key = "home",
                title = "首页",
                instruction = "请打开 BOSS直聘，停留在首页，确保可以看到底部Tab栏（职位/消息/我的）",
                description = "BOSS直聘的首页，顶部有搜索框，中间是职位推荐列表，底部有Tab导航栏",
                order = 1
            ),
            GuidePage(
                key = "search_result",
                title = "搜索结果页",
                instruction = "请在搜索框输入任意关键词（如'Java'），进入搜索结果页，截图",
                description = "搜索结果的职位列表页，包含职位卡片（标题、公司、薪资、地点）和筛选条件",
                order = 2
            ),
            GuidePage(
                key = "job_detail",
                title = "职位详情页",
                instruction = "请点击任意一个职位，进入详情页，截图",
                description = "职位详情页，包含公司信息、职位描述、薪资范围、技能要求和底部操作栏（投递/收藏）",
                order = 3
            ),
            GuidePage(
                key = "chat_greeting",
                title = "打招呼页",
                instruction = "请点击'投递'或'打招呼'按钮，进入聊天/打招呼页面，截图",
                description = "打招呼/聊天页面，包含文本输入框、发送按钮、快捷打招呼选项",
                order = 4
            ),
            GuidePage(
                key = "chat_list",
                title = "聊天列表页",
                instruction = "请返回消息Tab，进入聊天列表页（已投递过的职位列表），截图",
                description = "聊天列表页，显示已投递过的职位和聊天记录列表",
                required = false,
                order = 5
            )
        )
    )

    override val predefinedWorkflows = listOf(
        WorkflowDef(
            name = "search_jobs",
            description = "搜索岗位",
            requiredParams = listOf("keyword", "city"),
            steps = listOf(
                WorkflowStep(id = "step_1", action = "launch_app", waitAfter = 3000),
                WorkflowStep(id = "step_2", action = "wait", millis = 2000),
                WorkflowStep(id = "step_3", action = "find_and_click", target = "搜索框", waitAfter = 1000),
                WorkflowStep(id = "step_4", action = "type", text = "{keyword}", waitAfter = 1000),
                WorkflowStep(id = "step_5", action = "find_and_click", target = "搜索按钮", waitAfter = 2000),
                WorkflowStep(id = "step_6", action = "scroll", direction = "down", waitAfter = 500)
            ),
            timeoutMs = 60_000
        ),
        WorkflowDef(
            name = "apply_job",
            description = "投递当前职位",
            requiredParams = listOf("greeting_text"),
            steps = listOf(
                WorkflowStep(id = "step_1", action = "find_and_click", target = "投递按钮", maxRetries = 2, waitAfter = 2000),
                WorkflowStep(id = "step_2", action = "find_and_click", target = "打招呼输入框", waitAfter = 1000),
                WorkflowStep(id = "step_3", action = "type", text = "{greeting_text}", waitAfter = 1000),
                WorkflowStep(id = "step_4", action = "find_and_click", target = "发送按钮", maxRetries = 2, waitAfter = 1500),
                WorkflowStep(id = "step_5", action = "back", waitAfter = 1000)
            ),
            timeoutMs = 60_000
        ),
        WorkflowDef(
            name = "next_job",
            description = "返回列表并进入下一个职位",
            steps = listOf(
                WorkflowStep(id = "step_1", action = "scroll", direction = "down", waitAfter = 500),
                WorkflowStep(id = "step_2", action = "find_and_click", target = "下一个职位", waitAfter = 2000)
            )
        )
    )

    override suspend fun generateTemplate(
        llmClient: LLMClient,
        screenshots: List<CapturedScreenshot>
    ): TemplateGenerationResult {
        return try {
            val generator = com.resumepilot.app.adapter.TemplateGenerator(llmClient)
            val analyses = mutableListOf<Pair<GuidePage, ScreenshotAnalysis>>()

            for (screenshot in screenshots) {
                val guidePage = guideConfig.pages.find { it.key == screenshot.pageKey }
                if (guidePage != null) {
                    val analysis = generator.analyzeScreenshot(screenshot, guidePage)
                    analyses.add(guidePage to analysis)
                }
            }

            if (analyses.isEmpty()) {
                return TemplateGenerationResult(
                    success = false,
                    errorMessage = "没有有效的截图分析结果"
                )
            }

            val template = generator.generateTemplate(
                platformName = platformName,
                appPackage = appPackage,
                analyses = analyses
            )

            TemplateGenerationResult(
                success = true,
                template = template,
                completedPages = analyses.size,
                totalPages = guideConfig.pages.size
            )
        } catch (e: Exception) {
            TemplateGenerationResult(
                success = false,
                errorMessage = "模板生成失败: ${e.message}"
            )
        }
    }

    override fun validateTemplate(template: PlatformTemplate): ValidationResult {
        val warnings = mutableListOf<String>()

        // 检查必要的工作流
        if (!template.workflows.containsKey("search_jobs")) {
            warnings.add("缺少 search_jobs 工作流")
        }
        if (!template.workflows.containsKey("apply_job")) {
            warnings.add("缺少 apply_job 工作流")
        }

        // 检查必要的元素映射
        val requiredElements = listOf("搜索框", "投递按钮", "发送按钮")
        for (element in requiredElements) {
            if (!template.elementMapping.containsKey(element)) {
                warnings.add("缺少元素映射: $element")
            }
        }

        return ValidationResult(
            isValid = warnings.size <= 2,  // 允许少量缺失，LLM 可以兜底
            message = if (warnings.isEmpty()) "模板验证通过" else "模板需要补充: ${warnings.joinToString("; ")}",
            warnings = warnings
        )
    }
}