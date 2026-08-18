package com.resumepilot.app.service

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 截图捕获工具类——使用 MediaProjection API 实现真实屏幕截图
 *
 * 使用方式：
 * 1. 先调用 RecordingForegroundService.start(context) 启动前台服务（Android 14 强制要求，
 *    否则 getMediaProjection() 会抛 SecurityException）
 * 2. 调用 createScreenCaptureIntent() 获取授权 Intent，用 ActivityResultLauncher 启动授权
 * 3. 授权成功后调用 onActivityResult()（失败返回 false）
 * 4. 调用 captureScreenshot() 获取 Base64 截图（可在任意线程调用）
 *
 * 实现要点：
 * - 授权成功后只创建一次 VirtualDisplay + ImageReader 并复用，避免每次截图重建导致偶发失败
 * - 用 context.resources.displayMetrics 读取真实屏幕尺寸（对所有 Context 类型都安全；
 *   ApplicationContext 调用 context.display 会抛 UnsupportedOperationException）
 * - 监听 MediaProjection.Callback，系统回收投影时自动重置授权状态
 */
class ScreenshotCapture(private val context: Context) {

    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    @Volatile
    private var mediaProjection: MediaProjection? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureHandler: Handler? = null

    private val displayWidth: Int
    private val displayHeight: Int
    private val displayDensity: Int

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 系统停止投影（例如用户关闭"正在投屏"通知）时重置
            synchronized(this@ScreenshotCapture) {
                teardownProjection()
                mediaProjection = null
            }
        }
    }

    init {
        // 注意：context 可能是 ApplicationContext（调用方传入 applicationContext）。
        // ApplicationContext 不关联任何 display，调用 context.display 会抛
        // UnsupportedOperationException；因此不能用 context.display，也不能依赖
        // WindowManager.defaultDisplay（部分 ROM 上同样不可靠）。
        // 正确做法：从 resources.displayMetrics 读取真实屏幕尺寸，对所有 Context 都安全。
        val metrics = context.resources.displayMetrics
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        var d = metrics.densityDpi
        // 极端情况下 metrics 可能为 0，回退到系统资源
        if (w <= 0 || h <= 0) {
            val sys = Resources.getSystem().displayMetrics
            if (sys.widthPixels > 0 && sys.heightPixels > 0) {
                w = sys.widthPixels
                h = sys.heightPixels
                d = sys.densityDpi
            }
        }
        displayWidth = w
        displayHeight = h
        displayDensity = d
    }

    /** 创建屏幕捕获授权 Intent */
    fun createScreenCaptureIntent(): Intent =
        projectionManager.createScreenCaptureIntent()

    /**
     * 用户授权后初始化 MediaProjection
     * @return 是否成功（Android 14 上未先启动前台服务会抛 SecurityException，返回 false）
     */
    fun onActivityResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            return false
        }
        return try {
            val mp = projectionManager.getMediaProjection(resultCode, data)
            synchronized(this) {
                // 重复授权时先清理旧实例
                teardownProjection()
                mediaProjection = mp
                mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                createCaptureResources()
            }
            true
        } catch (e: SecurityException) {
            // Android 14+：未先启动 mediaProjection 前台服务
            android.util.Log.e(TAG, "MediaProjection 授权失败：请确保已启动 mediaProjection 前台服务", e)
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "MediaProjection 授权失败", e)
            false
        }
    }

    /** 是否已获得有效授权 */
    fun isAuthorized(): Boolean = mediaProjection != null

    /** 是否为 Android 14+（需要前台服务 + 每次授权） */
    fun requiresForegroundService(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * 捕获当前屏幕截图，返回 Base64 编码的 PNG 字符串
     * 可在后台线程调用；重复调用复用同一个 VirtualDisplay
     */
    fun captureScreenshot(): String? {
        val mp = mediaProjection ?: return null

        val reader = synchronized(this) {
            val r = imageReader
            if (r == null) {
                // 首次调用时兜底创建（正常流程应在授权成功后创建）
                createCaptureResources()
                imageReader
            } else r
        } ?: return null

        // VirtualDisplay 意外释放时重新创建
        if (virtualDisplay?.surface == null) {
            synchronized(this) {
                if (virtualDisplay?.surface == null) {
                    createVirtualDisplay(mp, reader)
                }
            }
        }

        val latch = CountDownLatch(1)
        val resultHolder = arrayOf<Bitmap?>(null)

        val handler = captureHandler ?: Handler(Looper.getMainLooper())
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * displayWidth
                        val bmp = Bitmap.createBitmap(
                            displayWidth + rowPadding / pixelStride,
                            displayHeight, Bitmap.Config.ARGB_8888
                        )
                        buffer.position(0)
                        bmp.copyPixelsFromBuffer(buffer)
                        resultHolder[0] = if (bmp.width > displayWidth) {
                            Bitmap.createBitmap(bmp, 0, 0, displayWidth, displayHeight)
                        } else bmp
                    }
                } finally {
                    image.close()
                }
            }
            latch.countDown()
        }, handler)

        try {
            // 等待最多 3 秒让帧到达
            latch.await(3000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val bitmap = resultHolder[0]
        if (bitmap == null) return null

        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, out)
            val bytes = out.toByteArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                java.util.Base64.getEncoder().encodeToString(bytes)
            } else {
                @Suppress("DEPRECATION")
                android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun createCaptureResources() {
        captureHandler = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(
            displayWidth, displayHeight, PixelFormat.RGBA_8888, 2
        )
        imageReader = reader
        mediaProjection?.let { createVirtualDisplay(it, reader) }
    }

    private fun createVirtualDisplay(mp: MediaProjection, reader: ImageReader) {
        try {
            virtualDisplay = mp.createVirtualDisplay(
                "ScreenshotCapture", displayWidth, displayHeight, displayDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "创建 VirtualDisplay 失败", e)
        }
    }

    private fun teardownProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        captureHandler = null
    }

    /** 释放资源并停止投影 */
    fun release() {
        synchronized(this) {
            teardownProjection()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        }
    }

    private companion object {
        const val TAG = "ScreenshotCapture"
    }
}
