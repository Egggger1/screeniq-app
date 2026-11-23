package com.screeniq.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager

/**
 * 全屏"运行中光边框"悬浮层：叠在所有 App 最上层，四周一圈流光表示屏知在后台工作。
 *
 * 关键安全约束：
 * - 必须 FLAG_NOT_TOUCHABLE（否则整块屏都点不动）；
 * - 必须 FLAG_NOT_FOCUSABLE（不抢输入焦点、不弹键盘）；
 * - 依赖悬浮窗权限（SYSTEM_ALERT_WINDOW），无权限时静默跳过。
 */
object RunningBorderOverlay {

    private var view: FlowBorderView? = null

    /** WindowManager 的 add/remove 必须在主线程；调用方以后从协程进来也不会崩（审查 L-16）。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    fun show(context: Context) = onMain { showOnMain(context) }

    private fun showOnMain(context: Context) {
        if (view != null) return
        val app = context.applicationContext
        if (!android.provider.Settings.canDrawOverlays(app)) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val v = FlowBorderView(app).apply { setActive(true) }
        runCatching { wm.addView(v, lp) }.onSuccess { view = v }
    }

    fun hide() = onMain {
        val v = view ?: return@onMain
        val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        view = null
    }

    fun isShowing() = view != null
}
