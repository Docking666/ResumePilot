package com.resumepilot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.resumepilot.app.MainActivity
import com.resumepilot.app.ResumePilotApp
import kotlin.math.abs

/**
 * 前台服务：确保录制/执行/截屏过程中不被系统杀死，并持有 WakeLock 防止 CPU 休眠。
 *
 * 同时承载「悬浮窗截图按钮」：
 * - Android 14+ 要求 MediaProjection 授权前必须先启动一个 mediaProjection 前台服务，
 *   否则 getMediaProjection() 抛 SecurityException。
 * - 引导截图阶段（guideCaptureMode=true 且已授权）在任意 App 上方显示一个悬浮「📸」按钮，
 *   用户无需下拉通知栏即可点击截图，且点击瞬间按钮会自动隐藏，避免被拍进截图（解决
 *   “通知栏按钮难触发 + 下拉面板遮挡截图”两类问题）。
 */
class RecordingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "resume_pilot_recording"
        const val NOTIFICATION_ID = 1001

        /**
         * 启动前台服务（幂等：已在运行时重复调用无副作用）
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
    private val mainHandler = Handler(Looper.getMainLooper())

    // 悬浮窗相关
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingAdded = false
    private val visibilityRunnable = object : Runnable {
        override fun run() {
            updateFloatingVisibility()
            mainHandler.postDelayed(this, 400)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildFloatingView()
        mainHandler.postDelayed(visibilityRunnable, 400)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
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

    // ===================== 前台服务通知 =====================

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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ResumePilot")
            .setContentText("运行中…（点击屏幕上的悬浮「📸」按钮即可截取当前 App 页面）")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    // ===================== 悬浮窗截图按钮 =====================

    private fun buildFloatingView() {
        val ctx = this
        val container = FrameLayout(ctx).apply {
            // 圆形背景
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE63B82F6.toInt()) // 主色蓝，半透明
                setStroke(4, 0xFFFFFFFF.toInt())
            }
            background = bg
        }

        val icon = TextView(ctx).apply {
            text = "📸"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val sizeDp = 72
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        container.addView(icon, FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.CENTER
        })
        container.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)

        // 点击 / 拖动处理
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var moved = false
        var params: WindowManager.LayoutParams? = null

        container.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downTime = System.currentTimeMillis()
                    moved = false
                    params = floatingView?.let {
                        it.tag as? WindowManager.LayoutParams
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params?.let { p ->
                        // 以 (0,0) 为锚点，用 x/y 表示相对位置；简单按位移调整
                        p.x = (p.x + dx.toInt()).coerceIn(-maxX(), maxX())
                        p.y = (p.y + dy.toInt()).coerceIn(-maxY(), maxY())
                        windowManager?.updateViewLayout(floatingView, p)
                        downX = event.rawX
                        downY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 视为点击：触发截图
                        triggerFloatingCapture()
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = container
    }

    private fun maxX() = resources.displayMetrics.widthPixels
    private fun maxY() = resources.displayMetrics.heightPixels

    /** 根据 guideCaptureMode + 授权状态决定是否显示悬浮按钮 */
    private fun updateFloatingVisibility() {
        val app = ResumePilotApp.instance
        val shouldShow = app.guideCaptureMode &&
                (app.screenshotCapture?.isAuthorized() == true) &&
                Settings.canDrawOverlays(this)
        val view = floatingView ?: return
        if (shouldShow && !floatingAdded) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = (20 * resources.displayMetrics.density).toInt()
                y = (120 * resources.displayMetrics.density).toInt()
            }
            view.tag = params
            try {
                windowManager?.addView(view, params)
                floatingAdded = true
            } catch (e: Exception) {
                // 权限未授予等情况，忽略（下次轮询重试）
            }
        } else if (!shouldShow && floatingAdded) {
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
            floatingAdded = false
        }
    }

    /** 点击悬浮按钮：先隐藏自身，再截图（避免按钮被拍进画面），随后恢复显示 */
    private fun triggerFloatingCapture() {
        val view = floatingView
        if (view == null || !floatingAdded) {
            toast("截图按钮未就绪")
            return
        }
        // 隐藏
        view.visibility = View.GONE
        // 等一帧让隐藏生效后再截图
        mainHandler.postDelayed({
            val ok = ResumePilotApp.instance.requestCapture()
            if (!ok) {
                toast("请先授权屏幕捕获")
            }
            // 截图为异步（IO），稍后恢复按钮显示
            mainHandler.postDelayed({
                if (floatingAdded) view.visibility = View.VISIBLE
            }, 900)
        }, 250)
    }

    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    // ===================== WakeLock =====================

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ResumePilot::ExecutionWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(visibilityRunnable)
        try {
            if (floatingAdded) windowManager?.removeView(floatingView)
        } catch (_: Exception) {
        }
        floatingAdded = false
        floatingView = null
        releaseWakeLock()
        super.onDestroy()
    }
}
