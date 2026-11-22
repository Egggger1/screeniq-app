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
 * 截图 OCR 兜底：无障碍服务自带的 takeScreenshot（Android 11+）抓当前屏幕，
 * 再用离线 ML Kit 中文识别把字认出来。
 *
 * 为什么需要它：
 * 很多 App 的界面是 H5 / 自绘控件，无障碍节点里拿不到文字（或只拿到按钮名），
 * 光靠节点会"读不到题"。截图 OCR 是最后的保底手段。
 *
 * 说明：全程在本机内存里处理，图片不落盘、不上传，识别完立刻回收。
 */
object ScreenshotOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** Android 11 以下没有无障碍截图接口，只能走节点。 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun readScreen(service: AccessibilityService): String? {
        val shot = grab(service) ?: return null
        val scaled = downscale(shot)
        val text = runCatching { recognize(scaled) }.getOrNull()
        if (scaled !== shot) runCatching { scaled.recycle() }
        runCatching { shot.recycle() }
        return text
    }

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
    private fun downscale(src: Bitmap): Bitmap {
        val maxWidth = 1080
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, maxWidth, h, true) }.getOrDefault(src)
    }

    private suspend fun recognize(bmp: Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
}
