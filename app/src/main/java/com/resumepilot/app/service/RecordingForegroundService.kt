package com.resumepilot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.resumepilot.app.MainActivity

/**
 * 前台服务：确保录制/执行/截屏过程中不被系统杀死，并持有 WakeLock 防止 CPU 休眠
 *
 * 注意（Android 14+ / targetSdk 34）：
 * MediaProjection 要求在发起"共享屏幕"授权（createScreenCaptureIntent）之前，
 * 必须先启动一个 foregroundServiceType 为 mediaProjection 的前台服务，
 * 否则 getMediaProjection() 会抛 SecurityException，导致授权后无法截图。
 * 因此截图授权前必须先调用 [start] 启动本服务。
 */
class RecordingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "resume_pilot_recording"
        const val NOTIFICATION_ID = 1001

        /**
         * 启动前台服务（幂等：已在运行时重复调用无副作用）
         * 应在以下时机调用：
         *  1. 发起 MediaProjection 屏幕捕获授权之前（Android 14 强制要求）
         *  2. 开始自动化执行（探索/回放/投递）之前
         */
        fun start(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止前台服务并释放 WakeLock */
        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        // Android 10+ 需要声明前台服务类型
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 获取 PARTIAL_WAKE_LOCK：仅防止 CPU 休眠，不阻止屏幕关闭。
     * 自动化执行期间由调用方负责让用户保持屏幕常亮（或使用系统设置的最大屏幕超时）。
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ResumePilot::ExecutionWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // 最长持有 6 小时，防止泄漏导致永远不释放
            }
        } catch (e: Exception) {
            // 某些 OEM 系统可能拒绝，忽略即可
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // 忽略
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ResumePilot 运行中",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ResumePilot 正在执行自动化任务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // 点击通知回到主界面
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 通知栏「📸 截图本页」按钮：用户在目标 App（如 BOSS直聘）内时，
        // 下拉通知栏点此按钮即可截取当前真实页面，无需切回本应用。
        val captureIntent = Intent(this, CaptureActionReceiver::class.java).apply {
            action = CaptureActionReceiver.ACTION_CAPTURE
        }
        val capturePendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ResumePilot")
            .setContentText("自动化任务运行中…（下拉可「截图本页」）")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_camera,
                "📸 截图本页",
                capturePendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
