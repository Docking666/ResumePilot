package com.resumepilot.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.resumepilot.app.ResumePilotApp

/**
 * 通知栏「📸 截图本页」按钮的广播接收器。
 *
 * 引导流程需要截取"目标 App（如 BOSS直聘）"的真实页面，但 MediaProjection 截的是
 * 当前前台屏幕——用户在 BOSS直聘 里时无法点 ResumePilot 的按钮。
 * 因此在前台服务通知里放一个按钮，用户停留在目标 App 时下拉通知栏点它即可截图，
 * 截图结果通过 [ResumePilotApp.requestCapture] 发射到 [ResumePilotApp.guideCaptureFlow]。
 */
class CaptureActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CAPTURE = "com.resumepilot.app.ACTION_CAPTURE"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_CAPTURE) {
            ResumePilotApp.instance.requestCapture()
        }
    }
}
