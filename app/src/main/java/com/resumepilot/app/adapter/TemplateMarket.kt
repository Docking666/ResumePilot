package com.resumepilot.app.adapter

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 模板市场——模板导入/导出/分享功能
 *
 * 功能：
 * 1. 导出模板为 JSON 文件（可分享给他人）
 * 2. 从 JSON 文件导入模板
 * 3. 模板版本检查与兼容性校验
 * 4. 模板格式标准化（便于跨平台分享）
 */
class TemplateMarket(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        private const val EXPORT_FORMAT_VERSION = 1
        private const val EXPORT_MIME_TYPE = "application/json"
        private const val EXPORT_FILE_PREFIX = "ResumePilot_Template_"
        private const val EXPORT_FILE_SUFFIX = ".json"

        // 签名密钥，用于验证模板来源（简化版）
        private const val APP_SIGNATURE = "ResumePilot_v1"
    }

    /**
     * 导出模板为 JSON 字符串
     */
    fun exportTemplate(template: PlatformTemplate): String {
        val exportData = ExportData(
            formatVersion = EXPORT_FORMAT_VERSION,
            signature = APP_SIGNATURE,
            exportedAt = System.currentTimeMillis(),
            template = template
        )
        return gson.toJson(exportData)
    }

    /**
     * 导出模板到文件
     */
    fun exportTemplateToFile(template: PlatformTemplate, uri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(exportTemplate(template))
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 JSON 字符串导入模板
     */
    fun importTemplate(jsonString: String): ImportResult {
        return try {
            val exportData = gson.fromJson(jsonString, ExportData::class.java)

            // 格式验证
            if (exportData.signature != APP_SIGNATURE) {
                return ImportResult(
                    success = false,
                    errorMessage = "模板来源不兼容：签名不匹配"
                )
            }

            if (exportData.formatVersion > EXPORT_FORMAT_VERSION) {
                return ImportResult(
                    success = false,
                    errorMessage = "模板版本过高，请更新简历投递助手"
                )
            }

            val template = exportData.template ?: return ImportResult(
                success = false,
                errorMessage = "模板数据为空"
            )

            // 基本完整性检查
            val checkResult = checkTemplateIntegrity(template)
            if (!checkResult.isValid) {
                return ImportResult(success = false, errorMessage = checkResult.message)
            }

            // 生成新 ID 避免冲突
            val importedTemplate = template.copy(
                id = java.util.UUID.randomUUID().toString(),
                version = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                runCount = 0,
                repairCount = 0
            )

            ImportResult(
                success = true,
                template = importedTemplate,
                message = "模板导入成功：${template.platformName}"
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                errorMessage = "模板解析失败: ${e.message}"
            )
        }
    }

    /**
     * 从文件导入模板
     */
    fun importTemplateFromFile(uri: Uri): ImportResult {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            } ?: return ImportResult(success = false, errorMessage = "无法读取文件")
            importTemplate(content)
        } catch (e: Exception) {
            ImportResult(
                success = false,
                errorMessage = "文件读取失败: ${e.message}"
            )
        }
    }

    /**
     * 获取模板摘要信息（用于分享预览）
     */
    fun getTemplateSummary(template: PlatformTemplate): String {
        return buildString {
            appendLine("简历投递助手 - 平台模板")
            appendLine("平台: ${template.platformName}")
            appendLine("版本: v${template.version}")
            appendLine("截图分析: ${template.screenshots.size} 页")
            appendLine("工作流: ${template.workflows.size} 个")
            appendLine("元素映射: ${template.elementMapping.size} 个")
            appendLine("执行次数: ${template.runCount}")
            appendLine("修复次数: ${template.repairCount}")
            appendLine("创建时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.getDefault()).format(java.util.Date(template.createdAt))}")
        }
    }

    /**
     * 模板完整性检查
     */
    private fun checkTemplateIntegrity(template: PlatformTemplate): IntegrityCheck {
        val issues = mutableListOf<String>()

        if (template.platformName.isBlank()) issues.add("平台名称为空")
        if (template.appPackage.isBlank()) issues.add("应用包名为空")
        if (template.workflows.isEmpty()) issues.add("没有工作流定义")
        if (template.screenshots.isEmpty()) issues.add("没有截图分析数据")

        // 检查每个工作流是否有步骤
        template.workflows.forEach { (name, workflow) ->
            if (workflow.steps.isEmpty()) {
                issues.add("工作流 '$name' 没有步骤定义")
            }
        }

        return IntegrityCheck(
            isValid = issues.isEmpty(),
            message = if (issues.isEmpty()) "完整性检查通过" else issues.joinToString("; ")
        )
    }
}

// ====== 数据模型 ======

/** 导出数据格式 */
data class ExportData(
    val formatVersion: Int,
    val signature: String,
    val exportedAt: Long,
    val template: PlatformTemplate
)

/** 导入结果 */
data class ImportResult(
    val success: Boolean,
    val template: PlatformTemplate? = null,
    val errorMessage: String? = null,
    val message: String? = null
)

/** 完整性检查结果 */
data class IntegrityCheck(
    val isValid: Boolean,
    val message: String
)