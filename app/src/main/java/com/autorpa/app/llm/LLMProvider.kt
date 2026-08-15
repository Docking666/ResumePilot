package com.autorpa.app.llm

/**
 * LLM 供应商配置
 * 支持多模型切换：GPT-4o / Claude / 千问VL / DeepSeek / Gemini
 */
enum class LLMProvider(val displayName: String) {
    OPENAI("OpenAI GPT-4o"),
    ANTHROPIC("Anthropic Claude"),
    ALIYUN("阿里千问 VL"),
    DEEPSEEK("DeepSeek"),
    GEMINI("Google Gemini");

    companion object {
        fun fromName(name: String): LLMProvider {
            return entries.find { it.displayName.contains(name, ignoreCase = true) }
                ?: OPENAI
        }
    }
}

data class LLMConfig(
    val provider: LLMProvider = LLMProvider.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "",  // 兼容 OpenAI 格式的第三方 API
    val modelName: String = "gpt-4o",
    val temperature: Float = 0.1f,
    val maxTokens: Int = 4096
)

/**
 * LLM 响应解析结果
 */
sealed class LLMDecision {
    /** LLM 返回的具体动作 */
    data class Action(val action: com.autorpa.app.engine.Action) : LLMDecision()
    /** LLM 认为任务已完成 */
    data object TaskComplete : LLMDecision()
    /** LLM 需要用户帮助 */
    data class NeedHelp(val reason: String) : LLMDecision()
    /** LLM 返回错误 */
    data class Error(val message: String) : LLMDecision()
}

/**
 * 屏幕分析结果
 */
data class ScreenAnalysis(
    val description: String,             // 屏幕内容描述
    val interactiveElements: List<UIElement>,  // 可交互元素
    val currentApp: String? = null,      // 当前应用
    val pageType: String? = null         // 页面类型：首页/列表页/详情页等
)

data class UIElement(
    val text: String?,
    val description: String?,
    val bounds: String,                  // "[left,top,right,bottom]"
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean
)