package com.screeniq.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.userAgentString = settings.userAgentString + " ScreenIQAndroid/0.1"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // 外链（如申请密钥）交给系统浏览器，App 内只留本地页面
                    if (!request.url.toString().startsWith("file:///android_asset/")) {
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                        } catch (e: Exception) { /* 没有浏览器就忽略 */ }
                        return true
                    }
                    return false
                }
            }
        }
        setContentView(webView)

        val bridge = JsBridge(
            webView = webView,
            cfg = ConfigStore(this),
            ocr = OcrEngine(),
            ai = AiClient(ConfigStore(this)),
            scope = lifecycleScope,
        )
        webView.addJavascriptInterface(bridge, "ScreenIQ")

        // 加载打包在 APK 里的界面（无需服务器、无需联网即可打开 App）
        webView.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 原型只有一个页面，直接退出，避免返回空白
        super.onBackPressed()
    }
}
