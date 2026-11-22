package com.screeniq.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** 控制面板是否在前台。面板在前台时不用悬浮球（避免两套入口打架）。 */
object AppVisible {
    @Volatile
    var foreground: Boolean = false
}

/**
 * 常驻控制悬浮球。
 *
 * 为什么要有它：用户不应该为了"开始/暂停识屏"专门回到 App。
 * 所以只要有悬浮窗权限，服务连上就把这粒小胶囊挂到屏幕上；
 * 点一下展开成 [开始/暂停] [识屏一次] [打开面板]，再点收起。
 *
 * 三条硬约束（都是上一版误触事故换来的）：
 *  1. 窗口带 FLAG_NOT_TOUCH_MODAL —— 胶囊以外的触摸直接透给下层 App；
 *  2. 默认收起成一条窄胶囊，挂在屏幕右侧中部，不压题目、不压底部导航；
 *  3. App 面板在前台时自动隐藏，避免和面板里的开关重复。
 */
object ControlBubble {

    private const val COLOR_ON = "#A3E8D8"
    private const val COLOR_OFF = "#FFD98F"

    private var wm: WindowManager? = null
    private var host: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var pill: TextView? = null
    private var panel: LinearLayout? = null
    private var btnToggle: TextView? = null
    private var running = false

    fun isShowing(): Boolean = host != null

    fun show(context: Context) {
        if (host != null) {
            updateRunning(ScreenReadService.instance?.isRunningNow() ?: running)
            return
        }
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return

        val w = runCatching {
            app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }.getOrNull() ?: return
        wm = w

        val dm = app.resources.displayMetrics
        val d = dm.density

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (10 * d).toInt()
            y = (dm.heightPixels * 0.52f).toInt()
        }
        lp = p

        val root = FrameLayout(app)

        // ---- 收起态：一粒窄胶囊 ----
        val pillView = TextView(app).apply {
            text = "◈ 屏知"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
            background = capsule(app, COLOR_OFF)
        }
        pill = pillView

        // ---- 展开态：一排操作 ----
        val panelView = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = rounded(app, Color.parseColor("#F2142321"), 22f, Color.parseColor("#8017C3B2"))
            visibility = View.GONE
        }

        btnToggle = actionBtn(app, "暂停", true, d) {
            val svc = ScreenReadService.instance
            if (svc == null) {
                toast(app, "读屏服务没连上：先回面板开一次读屏权限")
            } else {
                val on = svc.toggleAutoRead()
                updateRunning(on)
                toast(app, if (on) "屏知：已开始自动识屏" else "屏知：已暂停")
                collapse()
            }
        }
        panelView.addView(btnToggle, rowLp(d, 6))
        panelView.addView(actionBtn(app, "识屏", false, d) {
            val svc = ScreenReadService.instance
            if (svc == null) toast(app, "读屏服务没连上") else {
                svc.readNowManual()
                toast(app, "屏知：读一次当前屏幕")
            }
            collapse()
        }, rowLp(d, 6))
        panelView.addView(actionBtn(app, "面板", false, d) {
            runCatching {
                app.startActivity(
                    Intent(app, ControlActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            collapse()
        }, rowLp(d, 0))

        root.addView(panelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(pillView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        host = root
        makeDraggable(app, pillView)

        if (!runCatching { w.addView(root, p) }.isSuccess) {
            host = null; pill = null; panel = null; wm = null; return
        }
        updateRunning(ScreenReadService.instance?.isRunningNow() ?: running)
    }

    /** 刷新"运行中/已暂停"的外观。服务状态一变就调它。 */
    fun updateRunning(on: Boolean) {
        running = on
        val p = pill ?: return
        val c = if (on) COLOR_ON else COLOR_OFF
        p.text = if (on) "◈ 屏知 · 运行中" else "◈ 屏知 · 已暂停"
        p.setTextColor(Color.parseColor(c))
        p.background = capsule(p.context, c)
        btnToggle?.let {
            it.text = if (on) "暂停" else "开始"
            it.background = rounded(it.context, Color.parseColor(COLOR_ON), 14f)
        }
    }

    fun hide() {
        val h = host ?: return
        runCatching { wm?.removeView(h) }
        host = null
        pill = null
        panel = null
        btnToggle = null
    }

    // ======================= 内部 =======================

    private fun expand() {
        panel?.visibility = View.VISIBLE
        pill?.visibility = View.GONE
    }

    private fun collapse() {
        panel?.visibility = View.GONE
        pill?.visibility = View.VISIBLE
    }

    @SuppressLint("SetTextI18n")
    private fun actionBtn(
        app: Context, label: String, primary: Boolean, d: Float, onClick: () -> Unit
    ): TextView = TextView(app).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
        setTextColor(if (primary) Color.parseColor("#00312B") else Color.parseColor("#CFE9E2"))
        background = if (primary) rounded(app, Color.parseColor(COLOR_ON), 14f)
                     else rounded(app, Color.parseColor("#2C4440"), 14f)
        setOnClickListener { onClick() }
    }

    private fun rowLp(d: Float, endMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = (endMargin * d).toInt() }

    /** 拖动胶囊；没拖动就当作"点一下"= 展开/收起。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(app: Context, handle: View) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var baseX = 0
            private var baseY = 0
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val params = lp ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        baseX = params.x; baseY = params.y
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > 10 || abs(dy) > 10)) dragging = true
                        if (dragging) {
                            // gravity=END：x 是从右边缘往左的偏移，所以手势往右走 x 要减
                            params.x = (baseX - dx).toInt().coerceAtLeast(0)
                            params.y = (baseY + dy).toInt().coerceAtLeast(0)
                            runCatching { wm?.updateViewLayout(host, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            if (panel?.visibility == View.VISIBLE) collapse() else expand()
                        }
                    }
                }
                return true
            }
        })
    }

    private fun capsule(context: Context, color: String): GradientDrawable =
        rounded(context, Color.parseColor("#E6142321"), 20f, Color.parseColor(color))

    private fun rounded(
        context: Context, color: Int, radiusDp: Float, stroke: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * context.resources.displayMetrics.density
        if (stroke != 0) setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
    }

    private fun toast(app: Context, msg: String) {
        runCatching { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }
}
