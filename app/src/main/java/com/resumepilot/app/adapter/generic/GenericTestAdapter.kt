package com.resumepilot.app.adapter.generic

import com.resumepilot.app.adapter.*
import com.resumepilot.app.llm.LLMClient

/**
 * 通用测试适配器（任选 App 即可走通"截图→翻页→AI 分析"全流程）。
 *
 * 用途：当目标招聘 App（如 BOSS直聘）在模拟器/测试环境中被平台检测为"设备异常"、
 * 无法真实使用时，可用本适配器选「设置」「浏览器」等任意 App 验证引导截图与模板
 * 生成链路是否工作，而不必依赖具体招聘平台。
 *
 * appPackage 留空 → 引导页不会自动拉起任何 App，由用户手动打开任意应用后截图。
 */
class GenericTestAdapter : PlatformAdapter {

    override val platformName = "通用测试（任选App）"
    override val appPackage = ""   // 空包名：不自动拉起，用户手动打开任意 App
    override val adapterVersion = 1

    override val guideConfig = GuideConfig(
        platformName = platformName,
        appPackage = appPackage,
        pages = listOf(
            GuidePage(
                key = "page_home",
                title = "首页 / 主界面",
                instruction = "请打开任意一款 App（例如「设置」或系统浏览器），停留在它的主界面/首页，然后下拉通知栏点「📸 截图本页」",
                description = "任意 App 的主界面，用于验证截图与翻页链路",
                order = 1
            ),
            GuidePage(
                key = "page_list",
                title = "列表页",
                instruction = "在该 App 中进入任意一个列表界面（如设置列表、书签列表、联系人列表），截图",
                description = "任意可滚动的列表界面",
                order = 2
            ),
            GuidePage(
                key = "page_detail",
                title = "详情页（可选）",
                instruction = "点击列表中的一项进入详情页，截图；若不方便也可直接「跳过此页」",
                description = "任意详情/内容界面",
                required = false,
                order = 3
            )
        )
    )

    override val predefinedWorkflows = emptyList<WorkflowDef>()

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
        return ValidationResult(
            isValid = true,
            message = "通用测试模板（无需特定元素映射）"
        )
    }
}
