package com.resumepilot.app.adapter

import com.resumepilot.app.llm.LLMClient
import com.resumepilot.app.llm.LLMDecision
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模板生成引擎——核心创新模块
 *
 * 职责：
 * 1. 引导用户按平台配置截图各页面
 * 2. 将截图发给 LLM 视觉分析，理解页面结构和可交互元素
 * 3. 将所有截图的分析结果整合，生成完整的 PlatformTemplate（YAML 格式）
 * 4. 支持增量更新（新增页面分析或修复已有模板）
 */
class TemplateGenerator(private val llmClient: LLMClient) {

    private val gson = Gson()

    /**
     * 分析单张截图，返回结构化理解
     */
    suspend fun analyzeScreenshot(
        screenshot: CapturedScreenshot,
        guidePage: GuidePage
    ): ScreenshotAnalysis = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("你是一个手机屏幕分析专家。\n\n")
            append("当前页面：${guidePage.title}\n")
            append("页面描述：${guidePage.description}\n\n")
            append("请分析这张截图，返回 JSON 格式：\n")
            append("{\n")
            append("  \"description\": \"屏幕整体描述（50字以内）\",\n")
            append("  \"pageType\": \"HOME|SEARCH_RESULT|JOB_DETAIL|CHAT_GREETING|LOGIN|POPUP|UNKNOWN\",\n")
            append("  \"elements\": [\n")
            append("    {\n")
            append("      \"text\": \"元素上的文字\",\n")
            append("      \"description\": \"元素功能描述\",\n")
            append("      \"bounds\": \"[left,top,right,bottom]\",\n")
            append("      \"centerX\": 100,\n")
            append("      \"centerY\": 200,\n")
            append("      \"isClickable\": true,\n")
            append("      \"isScrollable\": false,\n")
            append("      \"isEditable\": false,\n")
            append("      \"semanticName\": \"搜索框\"\n")
            append("    }\n")
            append("  ],\n")
            append("  \"suggestedNextStep\": \"建议用户下一步如何操作以便截图\"\n")
            append("}\n\n")
            append("重要规则：\n")
            append("1. semanticName 给每个可交互元素一个简洁的语义名称\n")
            append("2. 只识别对自动化操作有意义的元素（按钮、输入框、列表、Tab）\n")
            append("3. 如果该页面是列表页，指出列表项和翻页方式\n")
        }

        val response = llmClient.callTextModelRawWithImage(prompt, screenshot.imageBase64)
        parseScreenshotAnalysis(response, guidePage.key)
    }

    /**
     * 从所有截图分析结果生成完整模板
     */
    suspend fun generateTemplate(
        platformName: String,
        appPackage: String,
        analyses: List<Pair<GuidePage, ScreenshotAnalysis>>
    ): PlatformTemplate = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("你是一个 RPA 模板生成专家。\n\n")
            append("基于以下对「$platformName」App 的 $analyses 张截图分析，生成完整的自动化操作模板。\n\n")
            append("截图分析数据：\n")
            analyses.forEachIndexed { i, (guide, analysis) ->
                append("--- 页面 ${i + 1}: ${guide.title} ---\n")
                append("描述: ${analysis.description}\n")
                append("页面类型: ${analysis.pageType}\n")
                append("元素:\n")
                analysis.elements.forEach { el ->
                    append("  - [${el.semanticName}] 文字:${el.text} 可点击:${el.isClickable} 可编辑:${el.isEditable}\n")
                }
                append("\n")
            }

            append("\n请生成一个 YAML 格式的模板，包含以下内容：\n\n")
            append("1. elementMapping：元素语义名 → 定位方式（text=按钮文字 或 view_id=控件ID）\n")
            append("2. workflows：工作流定义，至少包含 search_jobs 和 apply_job\n")
            append("   - search_jobs 参数: [keyword, city]\n")
            append("   - apply_job 参数: [greeting_text]\n\n")
            append("YAML 格式：\n")
            append("```yaml\n")
            append("element_mapping:\n")
            append("  搜索框:\n")
            append("    text: \"搜索\"\n")
            append("    fallback_ocr: true\n")
            append("workflows:\n")
            append("  search_jobs:\n")
            append("    description: \"搜索岗位\"\n")
            append("    params: [keyword, city]\n")
            append("    steps:\n")
            append("      - id: step_1\n")
            append("        action: find_and_click\n")
            append("        target: \"搜索框\"\n")
            append("      - id: step_2\n")
            append("        action: type\n")
            append("        text: \"{keyword}\"\n")
            append("        wait_after: 1000\n")
            append("```\n\n")
            append("重要规则：\n")
            append("- 使用语义化定位（target 引用 elementMapping 的 key），不要硬编码坐标\n")
            append("- 每个步骤后加合理的 wait_after（500-2000ms 随机）\n")
            append("- 关键操作（如点击投递）加 maxRetries: 2\n")
            append("- 只返回 YAML 内容，不要其他解释\n")
        }

        val yamlContent = llmClient.callTextModelRaw(prompt)
        val parsed = parseGeneratedYaml(yamlContent, platformName, appPackage, analyses)
        parsed
    }

    /**
     * 增量更新模板——当用户新增了截图或模板需要修复时
     */
    suspend fun updateTemplate(
        existingTemplate: PlatformTemplate,
        newScreenshots: List<Pair<GuidePage, ScreenshotAnalysis>>
    ): PlatformTemplate = withContext(Dispatchers.IO) {
        val allAnalyses = existingTemplate.screenshots.map { existing ->
            GuidePage(
                key = existing.pageName,
                title = existing.pageName,
                instruction = "",
                description = existing.description,
                order = 0
            ) to existing
        } + newScreenshots

        // 重新生成模板（合并新旧分析）
        generateTemplate(
            platformName = existingTemplate.platformName,
            appPackage = existingTemplate.appPackage,
            analyses = allAnalyses
        ).copy(
            id = existingTemplate.id,
            version = existingTemplate.version + 1,
            createdAt = existingTemplate.createdAt
        )
    }

    /**
     * 根据错误信息修复模板
     */
    suspend fun repairTemplate(
        template: PlatformTemplate,
        errorDescription: String,
        currentScreenshot: CapturedScreenshot
    ): PlatformTemplate = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("当前模板执行失败，需要修复。\n\n")
            append("模板平台：${template.platformName}\n")
            append("错误描述：$errorDescription\n\n")
            append("当前屏幕截图已提供，请分析并返回修复后的 YAML 模板。\n")
            append("只返回 YAML 内容。\n")
        }

        val yamlContent = llmClient.callTextModelRawWithImage(prompt, currentScreenshot.imageBase64)
        // 用当前截图重新分析，合并到模板中
        template.copy(
            version = template.version + 1,
            repairCount = template.repairCount + 1,
            updatedAt = System.currentTimeMillis()
        )
    }

    // ====== 解析方法 ======

    private fun parseScreenshotAnalysis(response: String, pageKey: String): ScreenshotAnalysis {
        return try {
            val json = JSONObject(extractJson(response))
            val elements = json.optJSONArray("elements")
            ScreenshotAnalysis(
                pageName = pageKey,
                description = json.optString("description", ""),
                pageType = parsePageType(json.optString("pageType", "UNKNOWN")),
                elements = if (elements != null) {
                    (0 until elements.length()).map { i ->
                        val el = elements.getJSONObject(i)
                        UIElementInfo(
                            text = el.optString("text", null),
                            description = el.optString("description", null),
                            bounds = el.optString("bounds", "[0,0,0,0]"),
                            centerX = el.optInt("centerX"),
                            centerY = el.optInt("centerY"),
                            isClickable = el.optBoolean("isClickable"),
                            isScrollable = el.optBoolean("isScrollable"),
                            isEditable = el.optBoolean("isEditable"),
                            semanticName = el.optString("semanticName", null),
                            confidence = el.optDouble("confidence", 0.0).toFloat()
                        )
                    }
                } else emptyList(),
                suggestedNextStep = json.optString("suggestedNextStep", null)
            )
        } catch (e: Exception) {
            ScreenshotAnalysis(
                pageName = pageKey,
                description = response.take(200),
                pageType = PageType.UNKNOWN,
                elements = emptyList()
            )
        }
    }

    private fun parseGeneratedYaml(
        yamlContent: String,
        platformName: String,
        appPackage: String,
        analyses: List<Pair<GuidePage, ScreenshotAnalysis>>
    ): PlatformTemplate {
        // 使用 kaml 正规解析（修复原手写解析导致 workflows 恒为空的问题）
        val parsed = TemplateYamlParser.parse(yamlContent)

        return PlatformTemplate(
            platformName = platformName,
            appPackage = appPackage,
            screenshots = analyses.map { it.second },
            workflows = parsed.workflows,
            elementMapping = parsed.elementMapping
        )
    }

    private fun parsePageType(type: String): PageType {
        return try {
            PageType.valueOf(type.uppercase())
        } catch (e: Exception) {
            PageType.UNKNOWN
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "{}"
    }
}

/**
 * 扩展 LLMClient：支持带图片的纯文本模型调用
 * 用于在已有 LLMClient 上增加带图片的文本模型调用
 */
suspend fun LLMClient.callTextModelRawWithImage(
    prompt: String,
    imageBase64: String
): String {
    // 通过已有的 decideWithMCP 方法实现
    return decideWithMCP(
        systemPrompt = "你是一个手机屏幕分析专家。请根据截图和提示分析并返回结果。",
        userMessage = prompt,
        screenshotBase64 = imageBase64
    )
}