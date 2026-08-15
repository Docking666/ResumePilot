package com.autorpa.app.adapter.liepin

import com.autorpa.app.adapter.*
import com.autorpa.app.llm.LLMClient

/**
 * 猎聘平台适配器
 *
 * 猎聘 App 特点：
 * - 投递流程与 BOSS直聘不同，需要先沟通再投递
 * - 有"立即沟通"和"投递简历"两种方式
 * - 聊天界面更接近微信风格
 */
class LiepinAdapter : PlatformAdapter {

    override val platformName = "猎聘"
    override val appPackage = "com.liepin.liepin"
    override val adapterVersion = 1

    override val guideConfig = GuideConfig(
        platformName = platformName,
        appPackage = appPackage,
        pages = listOf(
            GuidePage(
                key = "home",
                title = "首页",
                instruction = "请打开猎聘 App，停留在首页，确保可以看到底部Tab栏（职位/消息/发现/我的）",
                description = "猎聘首页，顶部有搜索框和职位分类入口，中部是推荐职位信息流，底部有Tab导航栏",
                order = 1
            ),
            GuidePage(
                key = "search_result",
                title = "搜索结果页",
                instruction = "请在搜索框输入任意关键词（如'Java'），进入搜索结果页后截图",
                description = "搜索结果列表页，包含职位卡片（标题、公司名、薪资、学历要求、工作经验）和筛选条件（地区、薪资、学历）",
                order = 2
            ),
            GuidePage(
                key = "job_detail",
                title = "职位详情页",
                instruction = "请点击任意职位卡片，进入详情页后截图",
                description = "职位详情页，包含公司信息、职位描述、任职要求、薪资福利，底部有操作栏（立即沟通/投递简历/收藏）",
                order = 3
            ),
            GuidePage(
                key = "chat_greeting",
                title = "沟通页",
                instruction = "请点击'立即沟通'或'投递简历'按钮，进入聊天页面后截图",
                description = "聊天/沟通页面，包含文本输入框、发送按钮、表情/附件入口、快捷回复选项",
                order = 4
            ),
            GuidePage(
                key = "chat_list",
                title = "消息列表",
                instruction = "请返回消息Tab，进入消息列表页，截图",
                description = "消息列表页，显示已沟通过的职位记录和HR回复状态",
                required = false,
                order = 5
            )
        )
    )

    override val predefinedWorkflows = listOf(
        WorkflowDef(
            name = "search_jobs",
            description = "搜索岗位（猎聘）",
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
            description = "立即沟通（猎聘投递方式）",
            requiredParams = listOf("greeting_text"),
            steps = listOf(
                WorkflowStep(id = "step_1", action = "find_and_click", target = "立即沟通按钮", maxRetries = 2, waitAfter = 2000),
                WorkflowStep(id = "step_2", action = "wait", millis = 2000),
                WorkflowStep(id = "step_3", action = "find_and_click", target = "输入框", waitAfter = 1000),
                WorkflowStep(id = "step_4", action = "type", text = "{greeting_text}", waitAfter = 1000),
                WorkflowStep(id = "step_5", action = "find_and_click", target = "发送按钮", maxRetries = 2, waitAfter = 1500),
                WorkflowStep(id = "step_6", action = "back", waitAfter = 1000)
            ),
            timeoutMs = 60_000
        ),
        WorkflowDef(
            name = "next_job",
            description = "返回列表并进入下一个职位",
            steps = listOf(
                WorkflowStep(id = "step_1", action = "back", waitAfter = 1000),
                WorkflowStep(id = "step_2", action = "scroll", direction = "down", waitAfter = 500),
                WorkflowStep(id = "step_3", action = "find_and_click", target = "下一个职位", waitAfter = 2000)
            )
        )
    )

    override suspend fun generateTemplate(
        llmClient: LLMClient,
        screenshots: List<CapturedScreenshot>
    ): TemplateGenerationResult {
        return try {
            val generator = TemplateGenerator(llmClient)
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
                    success = false, errorMessage = "没有有效的截图分析结果"
                )
            }

            val template = generator.generateTemplate(
                platformName = platformName,
                appPackage = appPackage,
                analyses = analyses
            )

            TemplateGenerationResult(
                success = true, template = template,
                completedPages = analyses.size, totalPages = guideConfig.pages.size
            )
        } catch (e: Exception) {
            TemplateGenerationResult(
                success = false, errorMessage = "模板生成失败: ${e.message}"
            )
        }
    }

    override fun validateTemplate(template: PlatformTemplate): ValidationResult {
        val warnings = mutableListOf<String>()
        if (!template.workflows.containsKey("search_jobs")) warnings.add("缺少 search_jobs 工作流")
        if (!template.workflows.containsKey("apply_job")) warnings.add("缺少 apply_job 工作流")
        val required = listOf("搜索框", "立即沟通按钮", "发送按钮")
        required.forEach { el ->
            if (!template.elementMapping.containsKey(el)) warnings.add("缺少元素映射: $el")
        }
        return ValidationResult(
            isValid = warnings.size <= 2,
            message = if (warnings.isEmpty()) "模板验证通过" else "模板需要补充: ${warnings.joinToString("; ")}",
            warnings = warnings
        )
    }
}