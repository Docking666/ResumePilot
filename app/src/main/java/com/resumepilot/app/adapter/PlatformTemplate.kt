package com.resumepilot.app.adapter

import com.google.gson.Gson

/**
 * 平台模板——LLM 分析截图后生成的平台操作知识库
 *
 * 核心设计：
 * - 存储的是**语义级**操作描述，而非硬编码坐标
 * - 执行时通过控件/OCR/LLM视觉逐级降级定位
 * - 失败时自动修复并更新模板
 */
data class PlatformTemplate(
    val id: String = java.util.UUID.randomUUID().toString(),
    val platformName: String,          // "BOSS直聘"
    val appPackage: String,            // "com.hpbr.bosszhipin"
    val version: Int = 1,
    val screenshots: List<ScreenshotAnalysis> = emptyList(),
    val workflows: Map<String, WorkflowDef> = emptyMap(),
    val elementMapping: Map<String, String> = emptyMap(),  // 语义名 → 定位方式
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val runCount: Int = 0,
    val repairCount: Int = 0
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): PlatformTemplate =
            Gson().fromJson(json, PlatformTemplate::class.java)
    }
}

/**
 * 单张截图的分析结果
 */
data class ScreenshotAnalysis(
    val pageName: String,               // "首页", "搜索结果页", "职位详情页", "打招呼页"
    val description: String,            // LLM 对页面的整体描述
    val elements: List<UIElementInfo>,  // 识别出的可交互元素
    val pageType: PageType,             // 页面类型
    val suggestedNextStep: String? = null  // LLM 建议的下一步截图引导
)

/**
 * 页面类型枚举
 */
enum class PageType {
    HOME,           // 首页
    SEARCH_RESULT,  // 搜索结果列表页
    JOB_DETAIL,     // 职位详情页
    CHAT_GREETING,  // 打招呼/聊天页
    LOGIN,          // 登录页
    POPUP,          // 弹窗
    UNKNOWN
}

/**
 * 截图中的 UI 元素信息
 */
data class UIElementInfo(
    val text: String? = null,
    val description: String? = null,
    val bounds: String = "[0,0,0,0]",
    val centerX: Int = 0,
    val centerY: Int = 0,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEditable: Boolean = false,
    val semanticName: String? = null,  // LLM 赋予的语义名称，如"搜索框"
    val confidence: Float = 0.0f       // 识别置信度
)

/**
 * 工作流定义
 */
data class WorkflowDef(
    val name: String,                   // "search_jobs"
    val description: String,
    val steps: List<WorkflowStep>,
    val requiredParams: List<String> = emptyList(),  // ["keyword", "city"]
    val timeoutMs: Long = 120_000       // 工作流超时
)

/**
 * 工作流中的单个步骤
 */
data class WorkflowStep(
    val id: String,                     // "step_1"
    val action: String,                 // "find_and_click", "type", "scroll", "wait", "launch_app", "back"
    val target: String? = null,          // 引用 elementMapping 的 key，如"搜索框"
    val text: String? = null,            // 输入文本 / 查找文本
    val direction: String? = null,       // 滚动方向 "up" / "down"
    val millis: Long? = null,            // 等待毫秒数
    val maxRetries: Int = 2,            // 步骤最大重试次数
    val waitAfter: Long = 500,          // 执行后等待
    val description: String = ""
)

/**
 * 模板生成引导配置——定义了每个平台需要截图哪些页面
 */
data class GuideConfig(
    val platformName: String,
    val appPackage: String,
    val pages: List<GuidePage>
)

/**
 * 引导截图页定义
 */
data class GuidePage(
    val key: String,                     // "home", "search_result", "job_detail", "greeting"
    val title: String,                   // 引导标题
    val instruction: String,             // 引导用户操作的文字
    val description: String,             // 给 LLM 分析时的提示
    val required: Boolean = true,        // 是否必须截图
    val order: Int                       // 截图顺序
)

/**
 * 工作流执行结果
 */
data class WorkflowResult(
    val success: Boolean,
    val workflowName: String,
    val stepsCompleted: Int = 0,
    val totalSteps: Int = 0,
    val failedStep: WorkflowStep? = null,
    val errorMessage: String? = null,
    val durationMs: Long = 0,
    val repaired: Boolean = false,      // 是否经过自动修复
    val collectedData: Map<String, Any> = emptyMap()  // 工作流收集的数据（如岗位列表）
)

/**
 * 模板生成结果
 */
data class TemplateGenerationResult(
    val success: Boolean,
    val template: PlatformTemplate? = null,
    val errorMessage: String? = null,
    val completedPages: Int = 0,
    val totalPages: Int = 0
)