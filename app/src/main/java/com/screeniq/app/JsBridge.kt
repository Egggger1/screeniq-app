package com.screeniq.app

import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * JS 桥接：网页负责界面和交互，原生负责 OCR 和 AI。
 * 网页通过 window.ScreenIQ.xxx() 调用这里的方法。
 */
class JsBridge(
    private val webView: WebView,
    private val cfg: ConfigStore,
    private val ocr: OcrEngine,
    private val ai: AiClient,
    private val scope: CoroutineScope,
) {

    /** 同步方法：读配置（密钥已打码） */
    @JavascriptInterface
    fun getConfig(): String = cfg.publicJson()

    /** 同步方法：模型清单 */
    @JavascriptInterface
    fun listProviders(): String {
        val arr = JSONArray()
        Providers.list.forEach { arr.put(JSONObject(it)) }
        return JSONObject().put("providers", arr).toString()
    }

    /** 同步方法：保存配置 */
    @JavascriptInterface
    fun saveConfig(provider: String, apiKey: String, baseUrl: String, model: String): String {
        cfg.save(provider, apiKey, baseUrl, model)
        return "{\"ok\":true}"
    }

    /**
     * 异步方法：识屏 + 答题。网页传 callback 名，完成后原生回调它。
     * imageBase64 可为空（纯聊天）；question 可为空（只识别图片）。
     */
    @JavascriptInterface
    fun answer(imageBase64: String?, question: String, explain: Boolean, callback: String) {
        scope.launch(Dispatchers.IO) {
            var ocrText = ""
            var ocrEngine = ""
            if (!imageBase64.isNullOrBlank()) {
                ocrText = ocr.recognize(imageBase64)
                ocrEngine = "ML Kit 中文(离线)"
            }
            val q = question.trim().ifBlank { ocrText.trim() }
            val result: AiResult
            if (q.isBlank()) {
                result = AiResult("", "", false, "没读到文字，也没输入问题")
            } else {
                result = ai.answer(q, explain)
            }
            val json = JSONObject().apply {
                put("ok", result.ok)
                put("answer", result.answer)
                put("model", result.model)
                put("explain", explain)
                put("ocr_text", ocrText)
                put("ocr_engine", ocrEngine)
                put("note", result.note)
            }.toString()
            // 切回主线程执行 JS 回调
            withMain { webView.evaluateJavascript("javascript:$callback($json)", null) }
        }
    }

    private fun withMain(block: () -> Unit) {
        webView.post(block)
    }
}
