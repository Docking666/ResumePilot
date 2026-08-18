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
import kotlinx.coroutines.flow.MutableStateFlow

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

    /**
     * 引导截图流程的"当前页 key"：通知栏「截图本页」需要知道自己截的是哪一页，
     * 由 ScreenshotGuideStep 在切页时写入。
     */
    @Volatile
    var currentGuidePageKey: String = ""

    /**
     * 引导截图结果回流通道：用户通过通知栏按钮（在目标 App 内）或应用内按钮完成截图后，
     * [requestCapture] 把 (pageKey, base64) 发射到这里，ScreenshotGuideStep 监听后
     * 把截图存入模板并翻页。用 StateFlow 而非回调，避免跨进程/跨组件传参。
     */
    val guideCaptureFlow = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * 触发一次屏幕截图并发射到 [guideCaptureFlow]。
     * 由通知栏「截图本页」广播或应用内按钮调用；在 IO 协程执行，避免阻塞主线程/ANR。
     * @return 是否发起了截图（false 表示未授权或捕获器为空）
     */
    fun requestCapture(): Boolean {
        val cap = screenshotCapture ?: return false
        if (!cap.isAuthorized()) return false
        appScope.launch(Dispatchers.IO) {
            val b64 = cap.captureScreenshot()
            if (b64 != null) {
                guideCaptureFlow.emit(currentGuidePageKey to b64)
            }
        }
        return true
    }

    // 全局协程作用域（应用级，任务在 Activity 销毁后仍可继续执行）
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 全局崩溃捕获：记录到 filesDir/crash_log.txt，便于诊断"后台退出/闪退"类问题
        installCrashHandler()

        // 数据库。
        // 注意：fallbackToDestructiveMigration 会清空数据，仅限开发阶段使用；
        // 上生产前必须替换为手写 Migration（schema 已导出到 app/schemas 供编写参考）。
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

    /**
     * 全局未捕获异常处理：写入崩溃日志后交给原默认处理器（不吞异常）。
     * 崩溃日志位于 /data/data/包名/files/crash_log.txt
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = java.io.File(filesDir, "crash_log.txt")
                logFile.appendText(
                    "----- ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} -----\n" +
                            "thread: ${thread.name}\n" +
                            android.util.Log.getStackTraceString(throwable) + "\n"
                )
            } catch (_: Exception) {
                // 记录失败不阻塞崩溃流程
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 读取最近一次崩溃日志（供 UI 诊断横幅展示），无则返回 null。
     * 日志由 [installCrashHandler] 写入 filesDir/crash_log.txt。
     */
    fun readLatestCrash(): String? {
        return try {
            val logFile = java.io.File(filesDir, "crash_log.txt")
            if (!logFile.exists()) return null
            val text = logFile.readText()
            if (text.isBlank()) return null
            // 取最后一个 "-----" 分段，即最近一次崩溃
            text.split("-----")
                .lastOrNull { it.trim().isNotBlank() }
                ?.trim()
        } catch (_: Exception) {
            null
        }
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