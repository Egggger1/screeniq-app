package com.screeniq.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 流动光边框：沿屏幕四周画一圈跑动的渐变光带，用来表示"屏知正在后台识屏"。
 *
 * 设计取舍：
 * - 用 SweepGradient（扫描渐变）+ Matrix 旋转实现"光在转"，比逐帧画路径省电、代码少；
 * - 不拦截任何触摸（onTouchEvent 恒 false），叠在任何界面上都不影响操作；
 * - setActive(true) 才启动动画，静止时不耗 CPU。
 */
class FlowBorderView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private var angle = 0f
    private var animator: ValueAnimator? = null
    private var active = false

    private val colorMain = Color.parseColor("#17C3B2")
    private val colorGlow = Color.parseColor("#A3E8D8")
    private val colorIdle = Color.parseColor("#2A4742")

    // 每帧 new 对象会让这个常驻动画持续 GC（审查 M-12）；三样都提到字段复用
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: SweepGradient? = null
    private var shaderW = 0
    private var shaderH = 0

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = null   // 中心点变了，渐变要跟着重建
    }

    /** 开/关动画。重复调用无副作用。 */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        if (on) startAnim() else { stopAnim(); invalidate() }
    }

    private fun startAnim() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    /** 绝不消费触摸事件，保证叠在内容上时不影响滚动/点击。 */
    override fun onTouchEvent(event: MotionEvent) = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = paint.strokeWidth / 2f + dp(1f)
        rect.set(inset, inset, width.toFloat() - inset, height.toFloat() - inset)
        val radius = dp(18f)

        if (!active) {
            paint.shader = null
            paint.color = colorIdle
            canvas.drawRoundRect(rect, radius, radius, paint)
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        if (shader == null || shaderW != width || shaderH != height) {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, colorMain, colorGlow, colorMain, Color.TRANSPARENT),
                floatArrayOf(0f, 0.08f, 0.16f, 0.24f, 0.34f)
            )
            shaderW = width
            shaderH = height
        }
        matrix.setRotate(angle, cx, cy)
        shader!!.setLocalMatrix(matrix)
        paint.shader = shader
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}
