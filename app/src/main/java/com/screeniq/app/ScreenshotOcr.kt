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

    /**
     * 抓一张"已经缩好的"屏幕图，交给调用方决定怎么用（OCR 还是发给视觉模型）。
     *
     * 为什么要暴露这一层：一次识屏常常既要 OCR 又要看图，
     * 分开调 readScreen + captureBase64 会连截两次屏（多花 200~400ms 还可能截到不同画面）。
     * 现在只截一次、共用一张图。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun grabScaled(service: AccessibilityService): Bitmap? {
        val shot = grab(service) ?: return null
        val scaled = downscale(shot)
        if (scaled !== shot) runCatching { shot.recycle() }
        return scaled
    }

    /** 对已经拿到的图做离线中文识别。 */
    suspend fun recognizeBitmap(bmp: Bitmap): String? =
        runCatching { recognize(bmp) }.getOrNull()

    /** 把图压成 JPEG base64，随请求发给视觉模型；不落盘。 */
    fun encodeBase64(bmp: Bitmap, quality: Int = 78): String? = runCatching {
        java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }.getOrNull()

    /**
     * 抓当前屏幕并压成 JPEG base64，交给视觉模型认图。
     *
     * 为什么需要这条：猜字类题目（"看看这个字读什么"）的核心是一张图片，
     * 生僻字（嫑、兲、烎…）离线 OCR 基本认不出，只有视觉模型能认。
     * 图片只在内存里压缩后随请求发出，不写文件。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureBase64(service: AccessibilityService, quality: Int = 78): String? {
        val bmp = grabScaled(service) ?: return null
        val b64 = encodeBase64(bmp, quality)
        runCatching { bmp.recycle() }
        return b64
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
