package com.resumepilot.app.adapter

import com.resumepilot.app.llm.LLMClient

/**
 * 平台适配器接口——每个招聘平台实现一个适配器
 *
 * 职责：
 * 1. 提供引导配置，告诉 TemplateGenerator 需要截图哪些页面
 * 2. 提供工作流模板生成后的验证逻辑
 * 3. 持有平台特定的业务知识（如"投递后需要等待几秒"）
 */
interface PlatformAdapter {

    /** 平台显示名称 */
    val platformName: String

    /** 应用包名 */
    val appPackage: String

    /** 支持的版本号（用于模板兼容性检查） */
    val adapterVersion: Int

    /** 引导截图配置——告诉用户需要截哪些页面 */
    val guideConfig: GuideConfig

    /** 预置的工作流列表（名称 + 描述） */
    val predefinedWorkflows: List<WorkflowDef>

    /**
     * 引导截图完成后，LLM 分析所有截图，生成平台模板
     */
    suspend fun generateTemplate(
        llmClient: LLMClient,
        screenshots: List<CapturedScreenshot>
    ): TemplateGenerationResult

    /**
     * 验证模板是否完整可用
     */
    fun validateTemplate(template: PlatformTemplate): ValidationResult

    /**
     * 获取特定工作流的执行配置
     */
    fun getWorkflowConfig(workflowName: String): WorkflowDef? =
        predefinedWorkflows.find { it.name == workflowName }
}

/**
 * 用户截取的屏幕截图
 */
data class CapturedScreenshot(
    val pageKey: String,                // 对应 GuidePage.key
    val imageBase64: String,            // 截图 Base64
    val uiTree: String? = null,         // 截取时的控件树（可选）
    val capturedAt: Long = System.currentTimeMillis()
)

/**
 * 适配器验证结果
 */
data class ValidationResult(
    val isValid: Boolean,
    val message: String = "",
    val warnings: List<String> = emptyList()
)

/**
 * 平台适配器工厂——管理所有已注册的适配器
 */
class PlatformAdapterFactory {

    private val adapters = mutableMapOf<String, PlatformAdapter>()

    fun register(adapter: PlatformAdapter) {
        adapters[adapter.platformName] = adapter
        adapters[adapter.appPackage] = adapter
    }

    fun getByPlatformName(name: String): PlatformAdapter? = adapters[name]
    fun getByAppPackage(packageName: String): PlatformAdapter? = adapters[packageName]
    fun getAll(): List<PlatformAdapter> = adapters.values.distinct().toList()

    companion object {
        @Volatile
        private var instance: PlatformAdapterFactory? = null

        fun getInstance(): PlatformAdapterFactory {
            return instance ?: synchronized(this) {
                instance ?: PlatformAdapterFactory().also { instance = it }
            }
        }
    }
}