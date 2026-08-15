package com.resumepilot.app

import android.app.Application
import androidx.room.Room
import com.resumepilot.app.adapter.PlatformAdapterFactory
import com.resumepilot.app.adapter.boss.BossAdapter
import com.resumepilot.app.adapter.liepin.LiepinAdapter
import com.resumepilot.app.adapter.job51.Job51Adapter
import com.resumepilot.app.data.PreferencesManager
import com.resumepilot.app.data.db.AppDatabase
import com.resumepilot.app.llm.mcp.MCPGatewayManager
import com.resumepilot.app.resume.ResumeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ResumePilotApp : Application() {

    lateinit var database: AppDatabase
    lateinit var preferences: PreferencesManager
    lateinit var resumeManager: ResumeManager
    lateinit var mcpGatewayManager: MCPGatewayManager

    /**
     * 全局屏幕截图捕获器：用户在任何页面完成 MediaProjection 授权后写入，
     * 供 Orchestrator / MCP ScreenshotTool / WorkflowEngine 复用同一授权会话。
     */
    @Volatile
    var screenshotCapture: com.resumepilot.app.service.ScreenshotCapture? = null

    // 全局协程作用域（应用级，任务在 Activity 销毁后仍可继续执行）
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 数据库（开发阶段使用 destructive migration）
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "resume_pilot.db"
        ).fallbackToDestructiveMigration()
            .build()

        // 偏好设置
        preferences = PreferencesManager(this)

        // 简历管理器
        resumeManager = ResumeManager(this, database)

        // MCP 网关管理器（工具在服务连接后注册）
        mcpGatewayManager = MCPGatewayManager.getInstance()

        // 注册平台适配器
        registerPlatformAdapters()
    }

    private fun registerPlatformAdapters() {
        val factory = PlatformAdapterFactory.getInstance()
        factory.register(BossAdapter())
        factory.register(LiepinAdapter())
        factory.register(Job51Adapter())
    }

    companion object {
        lateinit var instance: ResumePilotApp
            private set
    }
}