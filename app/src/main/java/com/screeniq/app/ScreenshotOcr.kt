package com.screeniq.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 一行被认出来的字 + 它在图上的位置（图中心坐标，像素）。
 *
 * 为什么要带位置：这版最关键的修复就是靠它。
 * 拼多多/活动答题这类页面把选项画在 Canvas 或图片里，无障碍节点里
 * 根本没有「长江」这个节点 —— 答案显示出来了，自动点选却永远找不到目标。
 * 有了 OCR 的坐标框，就算它只是画上去的，也能按坐标戳中。
 */
data class OcrLine(val text: String, val cx: Float, val cy: Float)

/**
 * 截图 + 离线中文识别（Android 11+ 的无障碍截图接口）。
 *
 * 一次抓图同时供两件事使用：
 *  1. 认出文字（含每行坐标）→ 补节点读不到的字，并给出可点击的位置；
 *  2. 压成 JPEG 交给视觉模型 → 生僻字（嫑、兲）只有它能认。
 *
 * 全文只在本机内存处理，图片不落盘。
 */
object ScreenshotOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** Android 11 以下没有无障碍截图接口，只能走节点。 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 抓一张已经缩好的屏幕图，调用方决定怎么用（识别 / 发给视觉模型）。 */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun grabScaled(service: AccessibilityService, maxWidth: Int = 1080): Bitmap? {
        val shot = grab(service) ?: return null
        val scaled = downscale(shot, maxWidth)
        if (scaled !== shot) runCatching { shot.recycle() }
        return scaled
    }

    /** 离线识别：返回每行文字及其中心点，供"按坐标点选"使用。 */
    suspend fun recognizeLines(bmp: Bitmap): List<OcrLine> =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    val out = ArrayList<OcrLine>(24)
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val t = line.text.trim()
                            val box = line.boundingBox
                            if (t.isEmpty() || box == null || box.width() <= 0) continue
                            out.add(OcrLine(t, box.exactCenterX(), box.exactCenterY()))
                        }
                    }
                    if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        }

    /** 把图压成 JPEG base64，随请求发给视觉模型；不落盘。 */
    fun encodeBase64(bmp: Bitmap, quality: Int = 72): String? = runCatching {
        java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun grab(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
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
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            }.onFailure {
                if (cont.isActive) cont.resume(null)
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
