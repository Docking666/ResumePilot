package com.autorpa.app.llm.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MCP 网关管理器
 * 负责初始化 MCP 网关、注册工具、提供全局访问点
 */
class MCPGatewayManager {

    private val _gateway = MutableStateFlow<MCPGateway?>(null)
    val gateway: StateFlow<MCPGateway?> = _gateway

    private val _registry = MutableStateFlow(MCPToolRegistry())
    val registry: StateFlow<MCPToolRegistry> = _registry

    private var isInitialized = false

    /**
     * 初始化 MCP 网关
     * @param initializer 注册工具的回调
     */
    fun initialize(initializer: MCPToolRegistry.() -> Unit) {
        if (isInitialized) return

        val reg = MCPToolRegistry()
        reg.initializer()
        _registry.value = reg
        _gateway.value = MCPGateway(reg)
        isInitialized = true
    }

    /**
     * 动态注册新工具（运行时热加载）
     */
    fun registerTool(name: String, tool: MCPTool) {
        val reg = _registry.value
        reg.register(tool)
        _registry.value = reg
        _gateway.value = MCPGateway(reg)
    }

    /**
     * 获取系统提示词（嵌入 LLM 的 System Prompt）
     */
    fun getSystemPrompt(): String {
        return _gateway.value?.getSystemPrompt() ?: "MCP 网关未初始化"
    }

    /**
     * 获取指定工具
     */
    fun getTool(name: String): MCPTool? = _registry.value.get(name)

    fun isReady(): Boolean = isInitialized

    companion object {
        @Volatile
        private var instance: MCPGatewayManager? = null

        fun getInstance(): MCPGatewayManager {
            return instance ?: synchronized(this) {
                instance ?: MCPGatewayManager().also { instance = it }
            }
        }
    }
}