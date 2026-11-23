package com.screeniq.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * 答案悬浮窗。
 *
 * 这一版最重要的改动是「不挡手」：
 * 1. 贴在屏幕顶部（状态栏下方），高度最多占屏高 30%，不再压在题目和选项上；
 * 2. 30 秒后自动收成右上角一个小胶囊，几乎不可能误触；
 * 3. 窗口带 FLAG_NOT_TOUCH_MODAL —— 卡片以外的触摸直接交给下层 App；
 * 4. 「自动点选」按钮只在自动操控开启时才出现，默认根本没有这个按钮。
 */
object AnswerOverlay {

    private var wm: WindowManager? = null
    private var host: FrameLayout? = null
    private var card: LinearLayout? = null
    private var pill: TextView? = null
    private var answerView: TextView? = null
    private var headerView: TextView? = null
    private var lp: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private var collapseRunnable: Runnable? = null
    private var hideRunnable: Runnable? = null

    private var cardW = 0
    private var topY = 0

    private const val AUTO_COLLAPSE_MS = 30_000L
    private const val AUTO_HIDE_MS = 120_000L

    fun show(context: Context, answer: String, allowAutoClick: Boolean, onAutoClick: () -> String) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        main.post {
            removeSelf()

            val dm = app.resources.displayMetrics
            val d = dm.density
            cardW = minOf((dm.widthPixels * 0.92f).toInt(), (420 * d).toInt())
            topY = statusBarHeight(app) + (10 * d).toInt()

            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w

            val p = WindowManager.LayoutParams(
                cardW,
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
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = topY
            }
            lp = p

            val root = FrameLayout(app)

            // ---- 展开态卡片 ----
            val c = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(app, Color.parseColor("#F2142321"), 18f,
                    Color.parseColor("#4017C3B2"))
                setPadding(dpi(app, 16), dpi(app, 10), dpi(app, 16), dpi(app, 12))
            }

            val header = TextView(app).apply {
                text = "◈  屏知 · 答案"
                setTextColor(Color.parseColor("#A3E8D8"))
                textSize = 13f
                setPadding(0, dpi(app, 4), 0, dpi(app, 6))
            }
            headerView = header

            val ans = TextView(app).apply {
                text = stripMd(answer)
                setTextColor(Color.parseColor("#EAF2EF"))
                textSize = 15f
                setLineSpacing(dpi(app, 3).toFloat(), 1.3f)
                setTextIsSelectable(true)
            }
            answerView = ans

            val scroller = ScrollView(app).apply {
                isFillViewport = false
                addView(ans)
            }
            val scrollerLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(app, 2) }
            c.addView(header)
            c.addView(scroller, scrollerLp)

            // 内容太高就限高滚动，别把下半屏吃掉
            val maxH = (dm.heightPixels * 0.26f).toInt()
            scroller.post {
                val inner = scroller.getChildAt(0)?.height ?: 0
                if (inner > maxH) {
                    scroller.layoutParams = (scroller.layoutParams as LinearLayout.LayoutParams).apply { height = maxH }
                    scroller.requestLayout()
                }
            }

            val btnRow = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dpi(app, 10), 0, 0)
            }
            if (allowAutoClick) {
                val autoBtn = TextView(app).apply {
                    text = "自动点选此答案"
                    setTextColor(Color.parseColor("#00312B"))
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dpi(app, 12), dpi(app, 9), dpi(app, 12), dpi(app, 9))
                    background = rounded(app, Color.parseColor("#A3E8D8"), 12f)
                    setOnClickListener {
                        // 现在会把"点了没点到"的原因回显到标题，不再点了没反应
                        val msg = runCatching { onAutoClick() }.getOrDefault("点选失败")
                        noteFeedback(msg)
                        main.postDelayed({ collapse() }, 1800)
                    }
                }
                btnRow.addView(autoBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dpi(app, 8) })
            }
            val foldBtn = TextView(app).apply {
                text = "收起"
                setTextColor(Color.parseColor("#B7CEC8"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dpi(app, 12), dpi(app, 9), dpi(app, 12), dpi(app, 9))
                background = rounded(app, Color.parseColor("#2C4440"), 12f)
                setOnClickListener { collapse() }
            }
            btnRow.addView(foldBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(btnRow)

            // 拖动手柄：按住标题栏可整卡挪走（不想被挡就拖开）
            makeDraggable(app, header) { collapse() }

            // ---- 收起态小胶囊 ----
            val p2 = TextView(app).apply {
                text = "◈ 答案"
                setTextColor(Color.parseColor("#A3E8D8"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dpi(app, 12), dpi(app, 8), dpi(app, 12), dpi(app, 8))
                background = rounded(app, Color.parseColor("#F2142321"), 20f,
                    Color.parseColor("#8017C3B2"))
                visibility = View.GONE
            }
            makeDraggable(app, p2) { expand() }
            pill = p2

            root.addView(c, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            root.addView(p2, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END or Gravity.TOP })

            card = c
            host = root

            val added = runCatching { w.addView(root, p) }.isSuccess
            if (!added) {
                host = null; card = null; pill = null; answerView = null
                headerView = null; wm = null; lp = null
                return@post
            }

            scheduleCollapse()
            hideRunnable = Runnable { removeSelf() }
            main.postDelayed(hideRunnable!!, AUTO_HIDE_MS)
        }
    }

    private fun expand() {
        val c = card ?: return
        val p = pill ?: return
        c.visibility = View.VISIBLE
        p.visibility = View.GONE
        val params = lp ?: return
        params.width = cardW
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = topY
        runCatching { wm?.updateViewLayout(host, params) }
        scheduleCollapse()
    }

    private fun collapse() {
        val c = card ?: return
        val p = pill ?: return
        c.visibility = View.GONE
        p.visibility = View.VISIBLE
        val params = lp ?: return
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        // 收成右侧中部的小胶囊：不压标题栏按钮、不压底部导航，几乎不可能误触
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        params.x = dp(host?.context ?: return, 8)
        params.y = 0
        runCatching { wm?.updateViewLayout(host, params) }
        collapseRunnable?.let { main.removeCallbacks(it) }
    }

    private fun scheduleCollapse() {
        collapseRunnable?.let { main.removeCallbacks(it) }
        collapseRunnable = Runnable { collapse() }
        main.postDelayed(collapseRunnable!!, AUTO_COLLAPSE_MS)
    }

    fun hide() {
        main.post { removeSelf() }
    }

    /** 识别到题、还没拿到答案时的占位卡：让"正在动"这件事立刻可见，不用干等。 */
    fun showThinking(context: Context) {
        show(context, "正在问 AI…", false) { "" }
    }

    /** 自动点选的结果回显到标题（成功/没找到都看得见，不再"点了像没点"）。 */
    fun noteFeedback(msg: String) {
        val h = headerView ?: return
        main.post {
            h.text = if (msg.startsWith("已")) "◈  $msg" else "◈  $msg（详见日志）"
            h.setTextColor(Color.parseColor(if (msg.startsWith("已")) "#5DCAA5" else "#FFD98F"))
        }
    }

    /**
     * 答案窗当前占的屏幕区域（展开是卡片，收起是右侧小胶囊）。
     *
     * 收起态也返回，是为了两件事：算识别/指纹时把这块钱排除掉
     * （否则「◈ 答案」会被 OCR 当成题目文字），以及点选前判断
     * "目标是不是被我自己挡住了"。
     */
    fun cardBounds(): android.graphics.Rect? {
        val h = host ?: return null
        val loc = IntArray(2)
        runCatching { h.getLocationOnScreen(loc) }
        if (h.width <= 0 || h.height <= 0) return null
        return android.graphics.Rect(loc[0], loc[1], loc[0] + h.width, loc[1] + h.height)
    }

    /** 立刻收成小胶囊（自动点选前腾开遮挡用）。 */
    fun collapseNow() {
        main.post { collapse() }
    }

    private fun stripMd(s: String): String = s
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE), "· ")

    /** 同步移除，避免"先 post 再 add"导致刚显示的窗口立刻被删掉。 */
    private fun removeSelf() {
        collapseRunnable?.let { main.removeCallbacks(it) }
        hideRunnable?.let { main.removeCallbacks(it) }
        collapseRunnable = null
        hideRunnable = null
        host?.let { h -> runCatching { wm?.removeView(h) } }
        host = null
        card = null
        pill = null
        answerView = null
        headerView = null
        wm = null
        lp = null
    }

    /** 拖动手柄：按住移动窗口；轻点则执行 onClick（胶囊=展开，标题栏=收起）。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(context: Context, handle: View, onClick: () -> Unit) {
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
                        if (!dragging && (abs(dx) > dp(context, 10) || abs(dy) > dp(context, 10))) dragging = true
                        if (dragging) {
                            params.x = baseX + dx.toInt()
                            params.y = baseY + dy.toInt()
                            runCatching { wm?.updateViewLayout(host, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) onClick()
                    }
                }
                return true
            }
        })
    }

    private fun rounded(context: Context, color: Int, radiusDp: Float, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            if (strokeColor != 0) setStroke(dp(context, 1), strokeColor)
        }

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dpi(context: Context, v: Int): Int = dp(context, v)

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id)
        else (24 * context.resources.displayMetrics.density).toInt()
    }
}
