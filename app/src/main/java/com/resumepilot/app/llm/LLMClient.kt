package com.resumepilot.app.llm

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LLM 客户端：统一接口对接多模型供应商
 * 支持 GPT-4o / Claude / 千问VL / DeepSeek / Gemini
 */
class LLMClient(val config: LLMConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * 分析屏幕截图，返回结构化理解
     * 自动适配：视觉模型传截图，纯文本模型（DeepSeek）只传 UI 树
     */
    suspend fun analyzeScreen(
        screenshotBase64: String? = null,
        uiTree: String? = null
    ): ScreenAnalysis = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("你是一个手机屏幕分析专家。")
            if (!config.provider.supportsVision) {
                append("你无法看到截图，请根据以下控件树信息分析屏幕。\n\n")
            } else {
                append("分析这张截图，")
            }
            append("返回 JSON 格式：\n")
            append("{\n")
            append("  \"description\": \"屏幕整体描述\",\n")
            append("  \"currentApp\": \"当前应用包名或名称\",\n")
            append("  \"pageType\": \"首页/列表页/详情页/表单页/弹窗/其他\",\n")
            append("  \"interactiveElements\": [\n")
            append("    {\n")
            append("      \"text\": \"按钮文字\",\n")
            append("      \"description\": \"元素描述\",\n")
            append("      \"bounds\": \"[left,top,right,bottom]\",\n")
            append("      \"isClickable\": true,\n")
            append("      \"isScrollable\": false,\n")
            append("      \"isEditable\": false\n")
            append("    }\n")
            append("  ]\n")
            append("}\n\n")
            if (uiTree != null) {
                append("控件树信息（供参考）：\n$uiTree\n")
            }
        }

        val response = if (config.provider.supportsVision && screenshotBase64 != null) {
            callVisionModel(prompt, screenshotBase64)
        } else {
            callTextModel(prompt)
        }
        parseScreenAnalysis(response)
    }

    /**
     * 根据屏幕截图 + 任务目标，决策下一步动作
     * 自动适配：视觉模型传截图，纯文本模型（DeepSeek）只传 UI 树描述
     */
    suspend fun decideNextAction(
        taskDescription: String,
        screenshotBase64: String? = null,
        uiTree: String? = null,
        previousActions: List<String> = emptyList()
    ): LLMDecision = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("你是一个手机自动化操作专家。你的任务是：$taskDescription\n\n")
            if (!config.provider.supportsVision) {
                append("注意：你无法看到屏幕截图，请根据以下控件树信息进行分析。\n\n")
            }
            append("请分析当前屏幕，决定下一步操作。\n\n")
            append("可用操作类型：\n")
            append("1. CLICK x y - 点击坐标 (x, y)\n")
            append("2. TYPE \"文本\" - 输入文本\n")
            append("3. SWIPE x1 y1 x2 y2 - 滑动\n")
            append("4. BACK - 返回\n")
            append("5. HOME - 回到桌面\n")
            append("6. WAIT ms - 等待\n")
            append("7. SCROLL DOWN/UP - 滚动\n")
            append("8. DONE - 任务完成\n\n")
            append("请只返回 JSON 格式：\n")
            append("{\n")
            append("  \"thought\": \"你的推理过程\",\n")
            append("  \"action\": \"CLICK\",\n")
            append("  \"params\": {\"x\": 100, \"y\": 200},\n")
            append("  \"description\": \"点击登录按钮\"\n")
            append("}\n")

            if (previousActions.isNotEmpty()) {
                append("\n已执行的操作：\n")
                previousActions.forEachIndexed { i, a ->
                    append("  $i. $a\n")
                }
            }
            if (uiTree != null) {
                append("\n当前屏幕控件树（供参考）：\n$uiTree\n")
            }
        }

        // 纯文本模型（DeepSeek）只传文字，不传截图
        if (config.provider.supportsVision && screenshotBase64 != null) {
            val response = callVisionModel(prompt, screenshotBase64)
            parseDecision(response)
        } else {
            val response = callTextModel(prompt)
            parseDecision(response)
        }
    }

    /**
     * 从轨迹生成 RPA 脚本（核心功能）
     */
    suspend fun generateScriptFromTrajectory(
        taskDescription: String,
        trajectoryJson: String,
        appPackage: String? = null
    ): String = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("你是一个 RPA 脚本生成专家。\n")
            append("基于以下用户操作轨迹，生成一个可复用的 YAML 格式 RPA 自动化脚本。\n\n")
            append("任务描述：$taskDescription\n")
            if (appPackage != null) append("目标应用：$appPackage\n")
            append("\n操作轨迹：\n$trajectoryJson\n\n")
            append("YAML 脚本格式要求：\n")
            append("```yaml\n")
            append("name: 脚本名称\n")
            append("description: 脚本描述\n")
            append("app: 目标应用包名 (可选)\n")
            append("steps:\n")
            append("  - action: click\n")
            append("    x: 100\n")
            append("    y: 200\n")
            append("    description: 点击XX按钮\n")
            append("    wait: 1000\n")
            append("  - action: type\n")
            append("    text: \"输入内容\"\n")
            append("  - action: wait\n")
            append("    millis: 2000\n")
            append("  - action: scroll\n")
            append("    direction: down\n")
            append("  - action: back\n")
            append("  - action: find_and_click\n")
            append("    text: \"按钮文字\"\n")
            append("    fallback_ocr: true\n")
            append("```\n\n")
            append("请只返回 YAML 内容，不要返回其他解释。\n")
            append("注意：使用语义化的 find_and_click 代替硬编码坐标，提高脚本鲁棒性。\n")
        }

        callTextModel(prompt)
    }

    /**
     * 调用视觉模型（多模态：截图 + 文字）
     */
    private suspend fun callVisionModel(prompt: String, imageBase64: String): String {
        return when (config.provider) {
            LLMProvider.OPENAI -> callOpenAIVision(prompt, imageBase64)
            LLMProvider.ALIYUN -> callOpenAICompatibleVision(prompt, imageBase64)
            LLMProvider.DEEPSEEK -> callOpenAICompatibleVision(prompt, imageBase64)
            LLMProvider.GEMINI -> callGeminiVision(prompt, imageBase64)
            LLMProvider.ANTHROPIC -> callAnthropicVision(prompt, imageBase64)
        }
    }

    /**
     * 调用纯文本模型
     */
    private suspend fun callTextModel(prompt: String): String {
        return when (config.provider) {
            LLMProvider.OPENAI -> callOpenAIText(prompt)
            LLMProvider.ALIYUN -> callOpenAICompatibleText(prompt)
            LLMProvider.DEEPSEEK -> callOpenAICompatibleText(prompt)
            LLMProvider.GEMINI -> callGeminiText(prompt)
            LLMProvider.ANTHROPIC -> callAnthropicText(prompt)
        }
    }

    /**
     * 构造 OpenAI 兼容接口的完整地址。
     *
     * 关键修复（404 根因）：设置页让用户填写的是"Base URL"（如 https://api.deepseek.com/v1），
     * 但旧代码把它当成"完整接口地址"直接用，导致请求打到根路径而 404。
     * 这里统一把 Base URL 拼成 .../chat/completions（若已包含则原样返回）。
     */
    private fun openAIEndpoint(baseUrl: String): String {
        val base = baseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /**
     * OpenAI GPT-4o Vision API
     */
    private suspend fun callOpenAIVision(prompt: String, imageBase64: String): String {
        val url = openAIEndpoint(config.baseUrl)
        val model = config.modelName.ifEmpty { "gpt-4o" }

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/png;base64,$imageBase64")
                                put("detail", "high")
                            })
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).await()
        return parseOpenAIResponse(response)
    }

    private suspend fun callOpenAIText(prompt: String): String {
        val url = openAIEndpoint(config.baseUrl)

        val body = JSONObject().apply {
            put("model", config.modelName.ifEmpty { "gpt-4o" })
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).await()
        return parseOpenAIResponse(response)
    }

    /**
     * OpenAI 兼容接口（千问VL、DeepSeek 等）
     */
    private suspend fun callOpenAICompatibleVision(prompt: String, imageBase64: String): String {
        return callOpenAIVision(prompt, imageBase64) // 格式兼容
    }

    private suspend fun callOpenAICompatibleText(prompt: String): String {
        return callOpenAIText(prompt)
    }

    /**
     * Anthropic Claude Vision API
     */
    private suspend fun callAnthropicVision(prompt: String, imageBase64: String): String {
        val url = "https://api.anthropic.com/v1/messages"

        val body = JSONObject().apply {
            put("model", config.modelName.ifEmpty { "claude-3-5-sonnet-20240620" })
            put("max_tokens", config.maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/png")
                                put("data", imageBase64)
                            })
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).await()
        return parseAnthropicResponse(response)
    }

    private suspend fun callAnthropicText(prompt: String): String {
        val url = "https://api.anthropic.com/v1/messages"

        val body = JSONObject().apply {
            put("model", config.modelName.ifEmpty { "claude-3-5-sonnet-20240620" })
            put("max_tokens", config.maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).await()
        return parseAnthropicResponse(response)
    }

    /**
     * Gemini Vision API
     */
    private suspend fun callGeminiVision(prompt: String, imageBase64: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${config.modelName.ifEmpty { "gemini-1.5-pro" }}:generateContent?key=${config.apiKey}"

        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/png")
                                put("data", imageBase64)
                            })
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).await()
        return parseGeminiResponse(response)
    }

    private suspend fun callGeminiText(prompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${config.modelName.ifEmpty { "gemini-1.5-pro" }}:generateContent?key=${config.apiKey}"

        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).await()
        return parseGeminiResponse(response)
    }

    // ====== 响应解析 ======

    private fun parseOpenAIResponse(response: Response): String {
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception(friendlyHttpError(response.code, body))

        val json = JSONObject(body)
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    private fun parseAnthropicResponse(response: Response): String {
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception(friendlyHttpError(response.code, body))

        val json = JSONObject(body)
        return json.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
    }

    private fun parseGeminiResponse(response: Response): String {
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception(friendlyHttpError(response.code, body))

        val json = JSONObject(body)
        return json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }

    /**
     * 将 HTTP 错误码转换为对用户友好的提示（业界最佳实践：暴露可操作信息而非原始堆栈）
     */
    private fun friendlyHttpError(code: Int, body: String): String {
        return when (code) {
            401, 403 -> "API Key 无效或已过期（$code），请到「设置」页检查 Key 与 Base URL"
            404 -> "接口地址不存在（404），请检查 Base URL / 模型名称配置"
            429 -> "请求过于频繁（429），请稍后重试或降低任务频率"
            in 500..599 -> "LLM 服务暂时不可用（$code），请稍后重试"
            else -> "LLM 请求失败（$code）: ${body.take(200)}"
        }
    }

    /**
     * 公开的纯文本调用入口（供 ResumeManager 等模块使用）
     */
    suspend fun callTextModelRaw(prompt: String): String = withContext(Dispatchers.IO) {
        callTextModel(prompt)
    }

    /**
     * 带 MCP 上下文的决策调用
     * 将 MCP 工具描述注入 System Prompt
     */
    suspend fun decideWithMCP(
        systemPrompt: String,
        userMessage: String,
        screenshotBase64: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = openAIEndpoint(config.baseUrl)
        val model = config.modelName.ifEmpty { "gpt-4o" }

        val messages = JSONArray().apply {
            // System prompt
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            // User message
            put(JSONObject().apply {
                put("role", "user")
                put("content", if (screenshotBase64 != null) {
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", userMessage)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/png;base64,$screenshotBase64")
                                put("detail", "high")
                            })
                        })
                    }
                } else {
                    userMessage
                })
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).await()
        parseOpenAIResponse(response)
    }

    private fun parseScreenAnalysis(response: String): ScreenAnalysis {
        return try {
            val json = JSONObject(extractJson(response))
            val elements = json.getJSONArray("interactiveElements")
            ScreenAnalysis(
                description = json.getString("description"),
                currentApp = json.optString("currentApp", null),
                pageType = json.optString("pageType", null),
                interactiveElements = (0 until elements.length()).map { i ->
                    val el = elements.getJSONObject(i)
                    UIElement(
                        text = el.optString("text", null),
                        description = el.optString("description", null),
                        bounds = el.optString("bounds", "[0,0,0,0]"),
                        isClickable = el.optBoolean("isClickable"),
                        isScrollable = el.optBoolean("isScrollable"),
                        isEditable = el.optBoolean("isEditable")
                    )
                }
            )
        } catch (e: Exception) {
            ScreenAnalysis(
                description = response.take(500),
                interactiveElements = emptyList()
            )
        }
    }

    private fun parseDecision(response: String): LLMDecision {
        return try {
            val json = JSONObject(extractJson(response))
            val actionStr = json.getString("action").uppercase()

            when (actionStr) {
                "DONE" -> LLMDecision.TaskComplete
                "CLICK" -> {
                    val params = json.getJSONObject("params")
                    LLMDecision.Action(com.resumepilot.app.engine.Action.Click(
                        x = params.getInt("x"),
                        y = params.getInt("y"),
                        description = json.optString("description", "")
                    ))
                }
                "TYPE" -> {
                    val params = json.getJSONObject("params")
                    LLMDecision.Action(com.resumepilot.app.engine.Action.Type(
                        text = params.getString("text"),
                        description = json.optString("description", "")
                    ))
                }
                "SWIPE" -> {
                    val params = json.getJSONObject("params")
                    LLMDecision.Action(com.resumepilot.app.engine.Action.Swipe(
                        fromX = params.getInt("x1"),
                        fromY = params.getInt("y1"),
                        toX = params.getInt("x2"),
                        toY = params.getInt("y2"),
                        description = json.optString("description", "")
                    ))
                }
                "BACK" -> LLMDecision.Action(com.resumepilot.app.engine.Action.Back)
                "HOME" -> LLMDecision.Action(com.resumepilot.app.engine.Action.Home)
                "WAIT" -> {
                    val params = json.getJSONObject("params")
                    LLMDecision.Action(com.resumepilot.app.engine.Action.Wait(
                        millis = params.getLong("millis"),
                        description = json.optString("description", "")
                    ))
                }
                "SCROLL" -> {
                    val params = json.getJSONObject("params")
                    val dir = if (params.getString("direction").uppercase() == "UP")
                        com.resumepilot.app.engine.Action.ScrollDirection.UP
                    else
                        com.resumepilot.app.engine.Action.ScrollDirection.DOWN
                    LLMDecision.Action(com.resumepilot.app.engine.Action.Scroll(dir))
                }
                "FIND_CLICK" -> {
                    val params = json.getJSONObject("params")
                    LLMDecision.Action(com.resumepilot.app.engine.Action.FindAndClick(
                        text = params.optString("text", null)
                    ))
                }
                "HELP" -> LLMDecision.NeedHelp(json.optString("reason", "需要用户干预"))
                else -> LLMDecision.Error("未知动作: $actionStr")
            }
        } catch (e: Exception) {
            LLMDecision.Error("解析失败: ${e.message}")
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "{}"
    }

    /**
     * 带指数退避重试的 HTTP 执行（业界最佳实践）：
     * - 网络错误（IO 异常/超时）与 5xx 服务端错误自动重试，最多 [RETRY_TIMES] 次
     * - 退避策略：1s → 2s → 4s（指数增长）
     * - 4xx 客户端错误（如 API Key 无效）不重试，快速失败
     */
    private suspend fun Call.await(): Response {
        var lastError: Exception? = null
        repeat(RETRY_TIMES) { attempt ->
            try {
                val response = execute()
                // 成功或 4xx（客户端错误）直接返回；5xx 关闭响应体后重试
                if (response.isSuccessful || response.code < 500) return response
                response.body?.close()
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < RETRY_TIMES - 1) {
                kotlinx.coroutines.delay(1000L shl attempt)
            }
        }
        throw lastError ?: java.io.IOException("HTTP 请求失败（已重试 $RETRY_TIMES 次）")
    }

    private companion object {
        const val RETRY_TIMES = 3
    }
}