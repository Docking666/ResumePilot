package com.autorpa.app.llm.mcp

/**
 * MCP (Model Context Protocol) 工具定义
 * 标准化的接口，让 LLM 可以调用各种能力
 *
 * 每个 Tool 是一个独立的能力单元，LLM 通过 MCP 网关发现和调用
 */
interface MCPTool {
    /** 工具名称（LLM 用来识别） */
    val name: String

    /** 工具描述（LLM 用来理解用途） */
    val description: String

    /** 输入 JSON Schema（LLM 填充参数） */
    val inputSchema: Map<String, Any>

    /** 执行工具 */
    suspend fun execute(params: Map<String, Any>): MCPToolResult
}

/**
 * 工具执行结果
 */
sealed class MCPToolResult {
    data class Success(val data: Map<String, Any>) : MCPToolResult() {
        fun text(key: String, default: String = ""): String = (data[key] as? String) ?: default
        fun int(key: String, default: Int = 0): Int = (data[key] as? Number)?.toInt() ?: default
        fun list(key: String): List<Any> = (data[key] as? List<Any>) ?: emptyList()
    }
    data class Error(val message: String, val code: Int = -1) : MCPToolResult()
}

/**
 * MCP 工具注册表
 */
class MCPToolRegistry {
    private val tools = mutableMapOf<String, MCPTool>()

    fun register(tool: MCPTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): MCPTool? = tools[name]
    fun getAll(): List<MCPTool> = tools.values.toList()

    /**
     * 生成 tools 描述（嵌入 LLM System Prompt）
     */
    fun generateToolsDescription(): String {
        val sb = StringBuilder()
        sb.append("可用工具列表：\n\n")
        tools.values.forEachIndexed { i, tool ->
            sb.append("${i + 1}. ${tool.name}\n")
            sb.append("   描述: ${tool.description}\n")
            sb.append("   参数: ${tool.inputSchema}\n\n")
        }
        return sb.toString()
    }
}

/**
 * MCP 调用请求
 */
data class MCPRequest(
    val toolName: String,
    val params: Map<String, Any> = emptyMap()
)

/**
 * MCP 调用结果
 */
data class MCPResponse(
    val toolName: String,
    val result: MCPToolResult,
    val durationMs: Long
)

/**
 * MCP 网关：核心路由层
 * 所有 LLM 的能力调用都经过此网关
 */
class MCPGateway(private val registry: MCPToolRegistry) {

    /**
     * 调用工具（同步，挂起协程）
     */
    suspend fun call(request: MCPRequest): MCPResponse {
        val start = System.currentTimeMillis()
        val tool = registry.get(request.toolName)

        return if (tool == null) {
            MCPResponse(
                toolName = request.toolName,
                result = MCPToolResult.Error("未知工具: ${request.toolName}，可用工具: ${registry.getAll().map { it.name }}"),
                durationMs = System.currentTimeMillis() - start
            )
        } else {
            try {
                val result = tool.execute(request.params)
                MCPResponse(
                    toolName = request.toolName,
                    result = result,
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                MCPResponse(
                    toolName = request.toolName,
                    result = MCPToolResult.Error("执行失败: ${e.message ?: "未知错误"}"),
                    durationMs = System.currentTimeMillis() - start
                )
            }
        }
    }

    /**
     * 批量调用工具（按顺序执行）
     */
    suspend fun callBatch(requests: List<MCPRequest>): List<MCPResponse> {
        return requests.map { call(it) }
    }

    /**
     * 获取所有工具的 System Prompt 描述
     */
    fun getSystemPrompt(): String {
        return registry.generateToolsDescription()
    }
}

/**
 * MCP 配置
 */
data class MCPConfig(
    val enabledTools: Set<String> = emptySet(), // 空 = 全部启用
    val maxRetries: Int = 3,
    val timeoutMs: Long = 60000,
    val enableLogging: Boolean = true,
    val customToolProviders: List<String> = emptyList() // 插件扩展
)