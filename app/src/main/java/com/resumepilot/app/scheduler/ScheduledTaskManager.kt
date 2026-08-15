package com.resumepilot.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.resumepilot.app.ResumePilotApp
import com.resumepilot.app.data.db.TemplateEntity
import com.resumepilot.app.service.RPAAccessibilityService
import com.resumepilot.app.service.RecordingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * 定时任务管理器——管理自动投递的定时调度
 *
 * 使用 Android AlarmManager 实现定时触发，
 * 通过 BroadcastReceiver 接收闹钟广播后启动投递。
 *
 * 配置存储在 DataStore 中，支持：
 * - 开关控制
 * - 一天内多个时间点（早/中/晚）
 * - 选择平台和关键词
 * - 选择使用的模板
 */
class ScheduledTaskManager(private val context: Context) {

    companion object {
        private const val PREFS_KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val PREFS_KEY_SCHEDULE_TIMES = "schedule_times"       // JSON: ["09:00","14:00","20:00"]
        private const val PREFS_KEY_SCHEDULE_KEYWORD = "schedule_keyword"
        private const val PREFS_KEY_SCHEDULE_PLATFORM = "schedule_platform"  // template id
        private const val PREFS_KEY_SCHEDULE_MAX_APPLY = "schedule_max_apply"
        private const val ACTION_SCHEDULE_TRIGGER = "com.resumepilot.app.SCHEDULE_TRIGGER"
        private const val REQUEST_CODE_BASE = 10001
    }

    private val prefs = context.getSharedPreferences("scheduled_tasks", Context.MODE_PRIVATE)

    // ====== 配置读写 ======

    var isEnabled: Boolean
        get() = prefs.getBoolean(PREFS_KEY_SCHEDULE_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(PREFS_KEY_SCHEDULE_ENABLED, value).apply()
            if (value) scheduleAll() else cancelAll()
        }

    var scheduleTimes: List<String>
        get() = prefs.getString(PREFS_KEY_SCHEDULE_TIMES, "09:00,14:00,20:00")
            ?.let { parseTimeList(it) } ?: listOf("09:00", "14:00", "20:00")
        set(value) {
            prefs.edit().putString(PREFS_KEY_SCHEDULE_TIMES, value.joinToString(",") { it }).apply()
            if (isEnabled) {
                cancelAll()
                scheduleAll()
            }
        }

    var keyword: String
        get() = prefs.getString(PREFS_KEY_SCHEDULE_KEYWORD, "Java开发") ?: "Java开发"
        set(value) = prefs.edit().putString(PREFS_KEY_SCHEDULE_KEYWORD, value).apply()

    var platformTemplateId: String
        get() = prefs.getString(PREFS_KEY_SCHEDULE_PLATFORM, "") ?: ""
        set(value) = prefs.edit().putString(PREFS_KEY_SCHEDULE_PLATFORM, value).apply()

    var maxApplications: Int
        get() = prefs.getInt(PREFS_KEY_SCHEDULE_MAX_APPLY, 15)
        set(value) = prefs.edit().putInt(PREFS_KEY_SCHEDULE_MAX_APPLY, value).apply()

    // ====== 调度管理 ======

    /**
     * 注册所有定时任务
     */
    fun scheduleAll() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val times = scheduleTimes

        times.forEachIndexed { index, time ->
            val parts = time.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull() ?: return@forEachIndexed
                val minute = parts[1].toIntOrNull() ?: return@forEachIndexed

                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(Calendar.getInstance())) {
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                }

                val intent = Intent(context, ScheduleReceiver::class.java).apply {
                    action = ACTION_SCHEDULE_TRIGGER
                    putExtra("template_id", platformTemplateId)
                    putExtra("keyword", keyword)
                    putExtra("max_applications", maxApplications)
                    putExtra("schedule_index", index)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_BASE + index,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // 使用 RTC_WAKEUP 确保手机休眠时也能触发
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
                )
            }
        }
    }

    /**
     * 取消所有定时任务
     */
    fun cancelAll() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduleTimes.indices.forEach { index ->
            val intent = Intent(context, ScheduleReceiver::class.java).apply {
                action = ACTION_SCHEDULE_TRIGGER
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    /**
     * 获取下一次执行时间
     */
    fun getNextRunTime(): Long? {
        if (!isEnabled) return null
        val times = scheduleTimes
        val now = Calendar.getInstance()

        for (time in times) {
            val parts = time.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull() ?: continue
                val minute = parts[1].toIntOrNull() ?: continue

                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (cal.after(now)) {
                    return cal.timeInMillis
                }
            }
        }

        // 所有时间都已过，返回明天第一个
        val first = times.firstOrNull()?.split(":") ?: return null
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, first[0].toIntOrNull() ?: 9)
            set(Calendar.MINUTE, first[1].toIntOrNull() ?: 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun parseTimeList(json: String): List<String> {
        return json.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.contains(":") }
    }
}

/**
 * 定时任务广播接收器——收到闹钟后启动投递
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val templateId = intent.getStringExtra("template_id") ?: return
        val keyword = intent.getStringExtra("keyword") ?: "Java开发"
        val maxApplications = intent.getIntExtra("max_applications", 15)

        // 后台定时触发：无障碍服务未开启时无法操作 UI，直接放弃本次投递
        val accessibilityService = RPAAccessibilityService.instance
        if (accessibilityService == null) {
            android.util.Log.w("ResumePilot-Schedule", "定时投递取消：无障碍服务未开启")
            return
        }

        // 在后台协程中执行投递（前台服务 + WakeLock 保活，防止进程被杀/休眠）
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val app = ResumePilotApp.instance
            try {
                RecordingForegroundService.start(app)

                val templateEntity = app.database.templateDao().getTemplateById(templateId) ?: return@launch
                val template = com.resumepilot.app.adapter.PlatformTemplate.fromJson(templateEntity.templateJson)
                val config = app.preferences.getLLMConfig()
                val llmClient = com.resumepilot.app.llm.LLMClient(config)

                // 执行流水线
                val scheduler = BatchScheduler(
                    accessibilityService = accessibilityService,
                    llmClient = llmClient,
                    screenshotCapture = app.screenshotCapture
                )

                val result = scheduler.runPipeline(
                    template = template,
                    config = PipelineConfig(
                        keyword = keyword,
                        maxApplications = maxApplications,
                        customGreeting = ""
                    )
                )

                // 发送通知
                sendNotification(context, result)
            } finally {
                RecordingForegroundService.stop(app)
            }
        }
    }

    private fun sendNotification(context: Context, result: PipelineResult) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要 POST_NOTIFICATIONS 权限
        }
        // 通知实现（简化，实际项目需要 NotificationManager）
        android.util.Log.i("ResumePilot-Schedule",
            "定时投递完成: ${result.appliedCount}成功, ${result.failedCount}失败")
    }
}