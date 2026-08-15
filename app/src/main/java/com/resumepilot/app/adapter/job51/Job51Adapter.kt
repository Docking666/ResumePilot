package com.resumepilot.app.adapter.job51

import com.resumepilot.app.adapter.*
import com.resumepilot.app.llm.LLMClient

/**
 * 51job（前程无忧）平台适配器
 *
 * 51job App 特点：
 * - 界面相对传统，控件结构较稳定
 * - 投递流程：搜索 → 职位列表 → 详情 → 投递简历
 * - 投递后可能需要选择简历版本
 * - 搜索结果页支持多条件筛选
 */
class Job51Adapter : PlatformAdapter {

    override val platformName = "51job"
    override val appPackage = "com.inc.andapp.51job"
    override val adapterVersion = 1

    override val guideConfig = GuideConfig(
        platformName = platformName,
        appPackage = appPackage,
        pages = listOf(
            GuidePage(
                key = "home",
                title = "首页",
                instruction = "请打开 51job App，停留在首页，确保可以看到搜索框和底部Tab栏",
                description = "51job首页，顶部有搜索框和地区选择，中部是职位推荐/功能入口，底部有Tab导航栏（首页/职位/消息/我的）",
                order = 1
            ),
            GuidePage(
                key = "search_result",
                title = "搜索结果页",
                instruction = "请在搜索框输入任意关键词（如'Java'），进入搜索结果页后截图",
                description = "搜索结果列表页，包含职位卡片（标题、公司名、薪资范围、地点、发布时间），顶部有筛选条件（地区、薪资、学历、经验）",
                order = 2
            ),
            GuidePage(
                key = "job_detail",
                title = "职位详情页",
                instruction = "请点击任意职位卡片，进入详情页后截图",
                description = "职位详情页，包含公司介绍、职位描述、任职要求、薪资福利，底部有操作栏（投递简历/收藏/分享）",
                order = 3
            ),
            GuidePage(
                key = "apply_page",
                title = "投递页",
                instruction = "请点击底部'投递简历'按钮，进入投递确认页后截图",
                description = "投递确认页，可能有简历版本选择、是否附言等选项，需要确认投递",
                required = true,
                order = 4
            ),
            GuidePage(
                key = "chat_list",
                title = "消息列表",
                instruction = "请返回消息/投递记录Tab，查看已投递的职位列表，截图",
                description = "投递记录/消息列表页，显示已投递的职位和状态（已投递/已查看/面试邀请/不合适）",
                required = false,
                order = 5
            )
        )
    )

    override val predefinedWorkflows = listOf(
        WorkflowDef(
            name = "search_jobs",
            description = "搜索岗位（51job）",
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
            description = "投递简历（51job）",
            requiredParams = listOf("greeting_text"),
            steps = listOf(
                WorkflowStep(id = "step_1", action = "find_and_click", target = "投递简历按钮", maxRetries = 2, waitAfter = 2000),
                WorkflowStep(id = "step_2", action = "wait", millis = 2000),
                WorkflowStep(id = "step_3", action = "find_and_click", target = "确认投递按钮", maxRetries = 2, waitAfter = 1500),
                WorkflowStep(id = "step_4", action = "wait", millis = 2000),
                WorkflowStep(id = "step_5", action = "back", waitAfter = 1000)
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
        val required = listOf("搜索框", "投递简历按钮", "确认投递按钮")
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