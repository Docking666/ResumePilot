package com.resumepilot.app.service

import android.content.Context
import android.content.Intent
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 截图捕获工具类——使用 MediaProjection API 实现真实屏幕截图
 *
 * 使用方式：
 * 1. 调用 createScreenCaptureIntent() 获取授权 Intent
 * 2. 用 ActivityResultLauncher 启动授权
 * 3. 授权成功后调用 onActivityResult()
 * 4. 调用 captureScreenshot() 获取 Base64 截图
 */
class ScreenshotCapture(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /** 创建屏幕捕获授权 Intent */
    fun createScreenCaptureIntent(): Intent =
        projectionManager.createScreenCaptureIntent()

    /** 用户授权后初始化 MediaProjection */
    fun onActivityResult(resultCode: Int, data: Intent?) {
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        }
    }

    /** 是否已获得授权 */
    fun isAuthorized(): Boolean = mediaProjection != null

    /**
     * 捕获当前屏幕截图，返回 Base64 编码的 PNG 字符串
     * 应在后台线程调用
     */
    fun captureScreenshot(): String? {
        try {
            val mp = mediaProjection ?: return null

            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null

            val handler = Handler(Looper.getMainLooper())
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val bmp = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height, Bitmap.Config.ARGB_8888
                        )
                        buffer.position(0)
                        bmp.copyPixelsFromBuffer(buffer)
                        bitmap = if (bmp.width > width) {
                            Bitmap.createBitmap(bmp, 0, 0, width, height)
                        } else bmp
                    }
                    image.close()
                }
                latch.countDown()
            }, handler)

            val vd = mp.createVirtualDisplay(
                "ScreenshotCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )

            // 等待最多 3 秒让帧到达
            latch.await(3000, TimeUnit.MILLISECONDS)

            vd.release()
            reader.close()

            val result = bitmap?.let { bmp ->
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 80, out)
                val bytes = out.toByteArray()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.util.Base64.getEncoder().encodeToString(bytes)
                } else {
                    @Suppress("DEPRECATION")
                    android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                }
            }
            bitmap?.recycle()
            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /** 释放资源 */
    fun release() {
        mediaProjection?.stop()
        mediaProjection = null
    }
}