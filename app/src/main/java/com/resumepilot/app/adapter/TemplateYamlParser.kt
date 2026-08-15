package com.resumepilot.app.adapter

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/**
 * 模板 YAML 解析器——基于 kaml 的正规 YAML 解析
 *
 * 从 LLM 生成的模板 YAML 中提取：
 * - elementMapping：语义名 → "text=xxx" / "view_id=xxx" 定位方式
 * - workflows：工作流定义（search_jobs / apply_job 等）
 *
 * 独立成 object 便于 JVM 单元测试（kaml 为纯 Kotlin 库，无 Android 依赖）。
 */
object TemplateYamlParser {

    /** 解析结果 */
    data class Result(
        val elementMapping: Map<String, String>,
        val workflows: Map<String, WorkflowDef>
    )

    fun parse(yaml: String): Result {
        // 清理 LLM 常见的 markdown 代码块包裹
        val cleaned = yaml
            .replace("```yaml", "")
            .replace("```yml", "")
            .replace("```", "")
            .trim()

        return try {
            val root = Yaml.default.parseToYamlNode(cleaned) as? YamlMap
            Result(
                elementMapping = parseElementMapping(root),
                workflows = parseWorkflows(root)
            )
        } catch (e: Exception) {
            Result(emptyMap(), emptyMap())
        }
    }

    // ====== element_mapping 解析 ======
    // YAML 结构：
    //   element_mapping:
    //     搜索框:
    //       text: "搜索"
    //       fallback_ocr: true
    private fun parseElementMapping(root: YamlMap?): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        val elemMap = root.map("element_mapping") ?: return mapping

        for ((keyNode, valueNode) in elemMap.entries) {
            val semanticName = keyNode.content
            val valueMap = valueNode as? YamlMap ?: continue
            val text = valueMap.str("text")
            val viewId = valueMap.str("view_id")

            val resolved = when {
                !text.isNullOrBlank() -> "text=$text"
                !viewId.isNullOrBlank() -> "view_id=$viewId"
                else -> null
            }
            if (resolved != null) {
                mapping[semanticName] = resolved
            }
        }
        return mapping
    }

    // ====== workflows 解析 ======
    // YAML 结构：
    //   workflows:
    //     search_jobs:
    //       description: "搜索岗位"
    //       params: [keyword, city]
    //       steps:
    //         - id: step_1
    //           action: find_and_click
    //           target: "搜索框"
    //           wait_after: 1000
    private fun parseWorkflows(root: YamlMap?): Map<String, WorkflowDef> {
        val workflows = mutableMapOf<String, WorkflowDef>()
        val workflowsMap = root.map("workflows") ?: return workflows

        for ((nameNode, defNode) in workflowsMap.entries) {
            val defMap = defNode as? YamlMap ?: continue
            val name = nameNode.content

            val params = defMap.list("params")?.items
                ?.mapNotNull { (it as? YamlScalar)?.content }
                ?: emptyList()

            val steps = defMap.list("steps")?.items?.mapNotNull { stepNode ->
                val m = stepNode as? YamlMap ?: return@mapNotNull null
                val action = m.str("action") ?: return@mapNotNull null
                WorkflowStep(
                    id = m.str("id") ?: "step_${stepNode.hashCode()}",
                    action = action,
                    target = m.str("target"),
                    text = m.str("text"),
                    direction = m.str("direction"),
                    millis = (m.str("millis") ?: m.str("wait"))?.toLongOrNull(),
                    maxRetries = m.str("max_retries")?.toIntOrNull() ?: 2,
                    waitAfter = m.str("wait_after")?.toLongOrNull() ?: 500,
                    description = m.str("description") ?: ""
                )
            } ?: emptyList()

            // 无有效步骤的工作流不产出（避免空模板）
            if (steps.isNotEmpty()) {
                workflows[name] = WorkflowDef(
                    name = name,
                    description = defMap.str("description") ?: "",
                    steps = steps,
                    requiredParams = params
                )
            }
        }
        return workflows
    }

    // ====== kaml 辅助扩展 ======
    // kaml 0.55：YamlMap.get(key: String) 返回 YamlNode?，entries 为 Map<YamlScalar, YamlNode>

    private fun YamlNode?.scalar(): String? = (this as? YamlScalar)?.content

    private fun YamlMap?.str(key: String): String? =
        (this?.get<YamlNode>(key) as? YamlScalar)?.content

    private fun YamlMap?.map(key: String): YamlMap? =
        this?.get<YamlNode>(key) as? YamlMap

    private fun YamlMap?.list(key: String): YamlList? =
        this?.get<YamlNode>(key) as? YamlList
}
