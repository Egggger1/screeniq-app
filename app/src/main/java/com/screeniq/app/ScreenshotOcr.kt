package com.screeniq.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 一行被认出来的字 + 它在图上的位置。
 *
 * 为什么必须带框而不只是中心点：这版要干两件事——
 *  1. 自动点选：Canvas/图片上"画出来"的选项，节点树里没有，只能按框点；
 *  2. Set-of-Mark：把编号画在框旁边发给视觉模型，让它"选编号"而不是估坐标。
 * 只给中心点做不到第 2 件事，也没法判断一行字有多高。
 */
data class OcrLine(val text: String, val cx: Float, val cy: Float, val box: Rect)

/**
 * 截图 + 离线中文识别（Android 11+ 的无障碍截图接口）。
 *
 * 一次抓图同时供三件事使用：
 *  1. 认出文字（含每行坐标框）→ 补节点读不到的字，并给出可点击的位置；
 *  2. 算出"这一屏长什么样"的指纹 → 判断页面到底变没变（换题了没有、点中了没有）；
 *  3. 画上编号交给视觉模型 → 生僻字（嫑、兲）和满屏图片的题只有它能认。
 *
 * 全文只在本机内存处理，图片不落盘。
 */
object ScreenshotOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** Android 11 以下没有无障碍截图接口，只能走节点。 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 最近一次截图失败码：0=成功，非 0 见 AccessibilityService.ERROR_TAKE_SCREENSHOT_*。 */
    @Volatile var lastError: Int = 0
        private set

    /** 系统对 takeScreenshot 有调用间隔限制，保守留 1 秒；不够就等，而不是让 OCR 静默缺失。 */
    private const val MIN_SHOT_INTERVAL_MS = 1000L
    private var lastShotAt = 0L

    /** 抓一张已经缩好的屏幕图，调用方决定怎么用（识别 / 发给视觉模型 / 算指纹）。 */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun grabScaled(service: AccessibilityService, maxWidth: Int = 1080): Bitmap? {
        val wait = MIN_SHOT_INTERVAL_MS - (System.currentTimeMillis() - lastShotAt)
        if (wait > 0) delay(wait)
        val shot = grab(service) ?: return null
        lastShotAt = System.currentTimeMillis()
        val scaled = downscale(shot, maxWidth)
        if (scaled !== shot) runCatching { shot.recycle() }
        return scaled
    }

    /** 离线识别：返回每行文字及其边框，供"按坐标点选"和"编号定位"使用。 */
    suspend fun recognizeLines(bmp: Bitmap): List<OcrLine> =
        suspendCancellableCoroutine { cont ->
            // ML Kit 的 Task 本身不能取消；至少在协程已取消时丢弃结果，别再往下游送（审查 M-13）
            var cancelled = false
            cont.invokeOnCancellation { cancelled = true }
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    if (cancelled || !cont.isActive) return@addOnSuccessListener
                    val out = ArrayList<OcrLine>(32)
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val t = line.text.trim()
                            val box = line.boundingBox
                            if (t.isEmpty() || box == null || box.width() <= 0 || box.height() <= 0) continue
                            out.add(OcrLine(t, box.exactCenterX(), box.exactCenterY(), Rect(box)))
                        }
                    }
                    cont.resume(out)
                }
                .addOnFailureListener {
                    if (!cancelled && cont.isActive) cont.resume(emptyList())
                }
        }

    /**
     * 「这一屏长什么样」的感知指纹：32×32 的亮度哈希（aHash）。
     *
     * 为什么不用 OCR 文字算：题目可能是纯图片，换题后文字一个字都不变，
     * 上一版就是因此认为"内容没变"而永远不再读。像素哈希能直接看出画面换了。
     *
     * 为什么要裁掉四周、还要能排除某些矩形：我们自己的流光边框在动、
     * 悬浮球和答案卡也在，若不排除，截图会一直"看起来变了"，导致无限重读。
     */
    fun signature(bmp: Bitmap, exclude: List<Rect> = emptyList()): String {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return ""
        val left = (w * 0.07f).toInt().coerceAtLeast(0)
        val right = (w - left).coerceAtMost(w - 1)
        val top = (h * 0.05f).toInt().coerceAtLeast(0)
        val bottom = (h - (h * 0.04f).toInt()).coerceAtMost(h - 1)
        if (right <= left || bottom <= top) return ""

        // 32×32：24×24 对布局接近的两屏有碰撞可能，纯图片题只能靠它判换题（审查 L-11）
        val cols = 32
        val rows = 32
        val lum = IntArray(cols * rows) { -1 }
        var sum = 0L
        var valid = 0

        for (r in 0 until rows) {
            val y = top + (bottom - top) * r / (rows - 1)
            for (c in 0 until cols) {
                val x = left + (right - left) * c / (cols - 1)
                if (exclude.any { it.contains(x, y) }) continue
                val p = runCatching { bmp.getPixel(x, y) }.getOrDefault(0)
                val l = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                lum[r * cols + c] = l
                sum += l
                valid++
            }
        }
        if (valid < 24) return ""
        val mean = sum / valid

        val sb = StringBuilder(cols * rows / 4 + 8)
        var nibble = 0
        var bit = 0
        for (i in lum.indices) {
            // 被排除的点按"比均值亮"计，保持定长，避免排除区域本身造成指纹抖动
            val on = lum[i] < 0 || lum[i] >= mean
            if (on) nibble = nibble or (1 shl (3 - bit))
            bit++
            if (bit == 4) {
                sb.append("0123456789abcdef"[nibble]); nibble = 0; bit = 0
            }
        }
        if (bit != 0) sb.append("0123456789abcdef"[nibble])
        return sb.toString()
    }

    /**
     * 局部签名：只取 (cx,cy) 周围一小块算指纹，用来确认"点这一下有没有反应"。
     *
     * 为什么需要它：全屏 32×32 的 aHash 把整屏亮度平均掉，选项圆圈从空心变绿色
     * 只占几个采样点，签名常常纹丝不动 —— 于是"明明点中了"却报"页面没变化"，
     * 还会触发同点补点把刚选中的又取消掉。局部块里那一下变色占比大，立刻可见。
     *
     * [cx]/[cy] 是**截图坐标**（不是屏幕坐标），由调用方按比例换算后传入。
     * [half] 是半径像素，默认取截图的 9% 左右，足够盖住一个选项行。
     */
    fun localSignature(bmp: Bitmap, cx: Float, cy: Float, half: Int = 0): String {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return ""
        val r = if (half > 0) half else (minOf(w, h) * 0.09f).toInt().coerceAtLeast(24)
        val l = (cx - r).toInt().coerceAtLeast(0)
        val t = (cy - r).toInt().coerceAtLeast(0)
        val rr = (cx + r).toInt().coerceAtMost(w - 1)
        val bb = (cy + r).toInt().coerceAtMost(h - 1)
        if (rr - l < 8 || bb - t < 8) return ""
        val crop = runCatching { Bitmap.createBitmap(bmp, l, t, rr - l, bb - t) }.getOrNull()
            ?: return ""
        val sig = runCatching { signature(crop) }.getOrDefault("")
        crop.recycle()
        return sig
    }

    /**
     * Set-of-Mark：给每行字编个号，画在它旁边（左侧空隙，压不到字）。
     *
     * 这是 AppAgent / Mobile-Agent 那一派的通行做法：让视觉模型"选几号"，
     * 而不是让它估一个像素坐标——后者偏差动辄上百像素，前者只要认得出数字。
     * 编号与 [lines] 的下标一一对应（编号 = 下标 + 1）。
     */
    fun annotate(src: Bitmap, lines: List<OcrLine>): Bitmap? = runCatching {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E61B2A3A") }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor("#FFF0B429")
        }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        lines.forEachIndexed { i, line ->
            val b = line.box
            val r = (b.height() * 0.55f).coerceIn(15f, 30f)
            val cx = if (b.left - r - 5f > 4f) b.left - r - 5f else b.right + r + 5f
            val cy = b.exactCenterY()
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            num.textSize = r * (if (i >= 9) 1.05f else 1.25f)
            canvas.drawText((i + 1).toString(), cx, cy + num.textSize * 0.36f, num)
        }
        out
    }.getOrNull()

    /** 把图压成 JPEG base64，随请求发给视觉模型；不落盘。 */
    fun encodeBase64(bmp: Bitmap, quality: Int = 70): String? = runCatching {
        java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun grab(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            lastError = 0
            var cancelled = false
            cont.invokeOnCancellation { cancelled = true }
            runCatching {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val bmp = runCatching {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                            runCatching { result.hardwareBuffer.close() }
                            // 协程取消后回调才到达的 bitmap 必须回收，否则每次切屏漏 ~10MB（审查 M-21）
                            if (cont.isActive && !cancelled) cont.resume(bmp)
                            else bmp?.let { runCatching { it.recycle() } }
                        }

                        override fun onFailure(errorCode: Int) {
                            lastError = errorCode
                            if (cont.isActive && !cancelled) cont.resume(null)
                        }
                    }
                )
            }.onFailure {
                if (cont.isActive && !cancelled) cont.resume(null)
            }
        }

    /** 整屏太大识别慢，缩到 1080 宽足够认字。 */
    private fun downscale(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, maxWidth, h, true) }.getOrDefault(src)
    }
}
