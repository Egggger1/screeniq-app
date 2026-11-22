package com.screeniq.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
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
 *
 * v0.2.7 的行为约定（上一版"点一下就变暂停"的事故换来的）：
 *  1. 点胶囊**只负责展开菜单**，绝不直接执行任何动作；
 *  2. 菜单出现在胶囊**下方**，不和胶囊重叠——避免同一次触摸落到「暂停」上；
 *  3. 展开后 400ms 内所有菜单项**忽略点击**（防误触冷却）；
 *  4. 窗口带 FLAG_NOT_TOUCH_MODAL，胶囊/菜单以外的触摸直接透给下层 App。
 */
object ControlBubble {

    private const val COLOR_ON = "#A3E8D8"
    private const val COLOR_OFF = "#FFD98F"

    /** 展开后多久才接受菜单项点击。手指抬起与第二下之间通常 <300ms，取 400ms 兜住。 */
    private const val GUARD_MS = 400L

    private var wm: WindowManager? = null
    private var host: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var pill: TextView? = null
    private var menu: LinearLayout? = null
    private var itemToggle: TextView? = null
    private var running = false
    private var expandedAt = 0L

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

        // 竖列：胶囊在上，菜单在它下方展开（关键：两者永不同位）
        val col = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            minimumWidth = (188 * d).toInt()
        }

        // ---- 收起态：一粒窄胶囊（宽度跟随竖列，展开时会一起变宽） ----
        val pillView = TextView(app).apply {
            text = "◈ 屏知"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
            background = capsule(app, COLOR_OFF)
        }
        pill = pillView

        // ---- 展开态：小菜单，第 1 行是标题+关闭，下面三行是操作 ----
        val menuView = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            background = rounded(app, Color.parseColor("#F6142321"), 16f, Color.parseColor("#8017C3B2"))
            visibility = View.GONE
        }
        menu = menuView

        // 标题行：左边文字，右边 ✕（点标题行也收起，给一个明确的"退出菜单"手势）
        val titleRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            setOnClickListener { collapse() }
        }
        val titleText = TextView(app).apply {
            text = "◈ 屏知"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8FB5AC"))
        }
        val closeBtn = TextView(app).apply {
            text = "✕"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#8FB5AC"))
            setPadding((10 * d).toInt(), 0, (2 * d).toInt(), 0)
            setOnClickListener { collapse() }
        }
        titleRow.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(closeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        menuView.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 三个操作项
        val toggleItem = menuItem(app, "暂停自动识屏", primary = true, d) {
            if (guard()) return@menuItem
            val svc = ScreenReadService.instance
            if (svc == null) {
                toast(app, "读屏服务没连上：先回面板开一次读屏权限")
            } else {
                val on = svc.toggleAutoRead()
                updateRunning(on)
                toast(app, if (on) "屏知：已开始自动识屏" else "屏知：已暂停自动识屏")
                collapse()
            }
        }
        itemToggle = toggleItem
        menuView.addView(toggleItem, itemLp(d))

        menuView.addView(menuItem(app, "⚡ 识屏一次", primary = false, d) {
            if (guard()) return@menuItem
            val svc = ScreenReadService.instance
            if (svc == null) {
                toast(app, "读屏服务没连上")
            } else {
                svc.readNowManual()
                toast(app, "屏知：读一次当前屏幕")
            }
            collapse()
        }, itemLp(d))

        menuView.addView(menuItem(app, "⚙ 打开面板", primary = false, d) {
            if (guard()) return@menuItem
            runCatching {
                app.startActivity(
                    Intent(app, ControlActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            collapse()
        }, itemLp(d))

        col.addView(pillView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(menuView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * d).toInt()   // 与胶囊留出间隙，避免视觉粘连
        })

        root.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        host = root
        makeDraggable(app, pillView)

        if (!runCatching { w.addView(root, p) }.isSuccess) {
            host = null; pill = null; menu = null; itemToggle = null; wm = null; return
        }
        updateRunning(ScreenReadService.instance?.isRunningNow() ?: running)
    }

    /** 刷新"运行中/已暂停"的外观。服务状态一变就调它。 */
    fun updateRunning(on: Boolean) {
        running = on
        val c = if (on) COLOR_ON else COLOR_OFF
        pill?.let {
            it.text = if (on) "◈ 屏知 · 运行中" else "◈ 屏知 · 已暂停"
            it.setTextColor(Color.parseColor(c))
            it.background = capsule(it.context, c)
        }
        itemToggle?.let {
            val d = it.context.resources.displayMetrics.density
            it.text = if (on) "暂停自动识屏" else "▶ 开始自动识屏"
            // 暂停态用实心亮色，运行态用暗底——一眼分得出点下去会发生什么
            it.setTextColor(Color.parseColor(if (on) "#FFD98F" else "#00312B"))
            it.background = if (on)
                rounded(it.context, Color.parseColor("#2C4440"), 12f)
            else
                rounded(it.context, Color.parseColor(COLOR_ON), 12f)
            it.setPadding((14 * d).toInt(), (11 * d).toInt(), (14 * d).toInt(), (11 * d).toInt())
        }
    }

    fun hide() {
        val h = host ?: return
        runCatching { wm?.removeView(h) }
        host = null
        pill = null
        menu = null
        itemToggle = null
        expandedAt = 0L
    }

    // ======================= 内部 =======================

    /** 展开菜单；只显示，不执行任何动作。 */
    private fun expand() {
        if (menu?.visibility == View.VISIBLE) return
        menu?.visibility = View.VISIBLE
        expandedAt = SystemClock.uptimeMillis()
        // 窗口变高可能顶出屏幕底部，展开后立刻校正一次
        host?.post { keepOnScreen() }
    }

    private fun collapse() {
        menu?.visibility = View.GONE
        expandedAt = 0L
    }

    /** 防误触冷却：刚展开的这一小会儿，菜单项一律不响应。 */
    private fun guard(): Boolean {
        if (expandedAt == 0L) return false
        return SystemClock.uptimeMillis() - expandedAt < GUARD_MS
    }

    private fun keepOnScreen() {
        val h = host ?: return
        val p = lp ?: return
        val dm = h.context.resources.displayMetrics
        val margin = (12 * dm.density).toInt()
        val maxY = (dm.heightPixels - h.height - margin).coerceAtLeast(0)
        if (p.y > maxY) {
            p.y = maxY
            runCatching { wm?.updateViewLayout(h, p) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun menuItem(
        app: Context, label: String, primary: Boolean, d: Float, onClick: () -> Unit
    ): TextView = TextView(app).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding((14 * d).toInt(), (11 * d).toInt(), (14 * d).toInt(), (11 * d).toInt())
        setTextColor(Color.parseColor(if (primary) "#FFD98F" else "#CFE9E2"))
        background = if (primary) rounded(app, Color.parseColor("#2C4440"), 12f)
                     else rounded(app, Color.parseColor("#1E332F"), 12f)
        setOnClickListener { onClick() }
    }

    private fun itemLp(d: Float) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (4 * d).toInt() }

    /** 拖动胶囊；没拖动就当作"点一下"= 展开/收起菜单。 */
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
                        if (!dragging && (abs(dx) > 12 || abs(dy) > 12)) dragging = true
                        if (dragging) {
                            // gravity=END：x 是从右边缘往左的偏移，所以手势往右走 x 要减
                            params.x = (baseX - dx).toInt().coerceAtLeast(0)
                            params.y = (baseY + dy).toInt().coerceAtLeast(0)
                            runCatching { wm?.updateViewLayout(host, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        // 只有"点一下没拖"才切菜单状态；拖动后保持原状，不误触
                        if (!dragging) {
                            if (menu?.visibility == View.VISIBLE) collapse() else expand()
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
