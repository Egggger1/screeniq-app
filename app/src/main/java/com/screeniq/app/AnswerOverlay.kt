package com.screeniq.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 就地作答悬浮窗：把答案浮在屏幕上，可拖动、可关闭、超时自动收起。
 * 依赖 SYSTEM_ALERT_WINDOW 权限；没权限时由服务降级为通知。
 */
object AnswerOverlay {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    @SuppressLint("InflateParams")
    fun show(context: Context, answer: String, explain: Boolean, onAutoClick: () -> Unit) {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            hide()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val lp = WindowManager.LayoutParams(
                (context.resources.displayMetrics.widthPixels * 0.86f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 180
            }

            // 卡片
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F210201E"))
                setPadding(4, 4, 4, 4)
            }

            val titleBar = TextView(context).apply {
                text = "◈  屏知 · 答案就在眼前"
                setTextColor(Color.parseColor("#A3E8D8"))
                textSize = 14f
                setPadding(28, 22, 28, 10)
            }
            card.addView(titleBar)

            val answerText = TextView(context).apply {
                text = answer
                setTextColor(Color.parseColor("#DDE5E1"))
                textSize = 15f
                setLineSpacing(4f, 1.25f)
                setPadding(28, 6, 28, 12)
            }
            val scroller = ScrollView(context).apply {
                addView(answerText)
                isFillViewport = false
            }
            runCatching { card.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 480)) }
                .onFailure { card.addView(answerText) }

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(20, 4, 20, 20)
            }
            val autoBtn = Button(context).apply {
                text = "自动点选此答案"
                setTextColor(Color.parseColor("#003831"))
                setBackgroundColor(Color.parseColor("#A3E8D8"))
                textSize = 12f
                isAllCaps = false
                setOnClickListener { onAutoClick(); hide() }
            }
            val closeBtn = Button(context).apply {
                text = "关闭 ✕"
                setTextColor(Color.parseColor("#AEC5BE"))
                setBackgroundColor(Color.parseColor("#29413D"))
                textSize = 12f
                isAllCaps = false
                setOnClickListener { hide() }
            }
            btnRow.addView(autoBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) })
            btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 0, 0) })
            card.addView(btnRow)

            // 拖动：按住标题栏移动整个悬浮窗
            titleBar.setOnTouchListener(object : View.OnTouchListener {
                var startX = 0f; var startY = 0f; var baseX = 0; var baseY = 0
                @SuppressLint("ClickableViewAccessibility")
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; baseX = lp.x; baseY = lp.y }
                        MotionEvent.ACTION_MOVE -> {
                            lp.x = baseX + (e.rawX - startX).toInt()
                            lp.y = baseY + (e.rawY - startY).toInt()
                            runCatching { wm.updateViewLayout(overlayView, lp) }
                        }
                    }
                    return true
                }
            })

            overlayView = card
            runCatching { wm.addView(card, lp) }
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, 30_000)
        }
    }

    fun hide() {
        main.post {
            main.removeCallbacks(hideRunnable)
            overlayView?.let { v -> runCatching { windowManager?.removeView(v) } }
            overlayView = null
            windowManager = null
        }
    }
}
