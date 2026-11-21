package com.screeniq.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 离线中文 OCR：ML Kit standalone 版，模型随 APK 打包，不依赖 GMS。 */
class OcrEngine {
    // 中文文字识别器（latina 与 chinese 分开，这里只用中文模型）
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 输入 data URL（data:image/png;base64,xxx）或纯 base64，识别出的文字。
     * ML Kit 的 API 是回调式，这里用 suspendCancellableCoroutine 包成协程可用。
     */
    suspend fun recognize(dataUrl: String): String {
        val bitmap = decode(dataUrl) ?: return ""
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText -> cont.resume(visionText.text) }
                .addOnFailureListener { cont.resume("") }
        }
    }

    private fun decode(dataUrl: String): Bitmap? = try {
        val pure = if ("," in dataUrl) dataUrl.substringAfter(",") else dataUrl
        val bytes = Base64.decode(pure, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}
