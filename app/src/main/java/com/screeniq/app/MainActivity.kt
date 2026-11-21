package com.screeniq.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {

    private var statusBarDp = 0
    private var webViewRef: WebView? = null

    // 网页里 <input type="file"> 的系统文件选择器回调
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val REQ_PICK_IMAGE = 1001
    }

    // 把状态栏高度注入给网页，header 据此留出安全区
    private fun injectTopInset() {
        webViewRef?.evaluateJavascript(
            "document.documentElement.style.setProperty('--safe-top','${statusBarDp}px');", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让内容延伸到状态栏下方，由我们手动给 WebView 加顶部 padding，避免标题被压住
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            // 关键修复：关掉桌面宽度模拟，让 viewport=device-width 真正生效，消除左右滑动
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            // 页面自己撑满宽度，不允许横向滚动
            settings.userAgentString = settings.userAgentString + " ScreenIQAndroid/0.1"
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.parseColor("#091312"))

            // 关键修复：网页点「上传题目图片」时，必须把选择交给系统相册/文件管理器，
            // 否则 WebView 里点上传什么都不会发生（这就是"点识屏没反应"的原因）。
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    return try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent.createChooser(intent, "选择题目图片"), REQ_PICK_IMAGE)
                        true
                    } catch (e: Exception) {
                        this@MainActivity.filePathCallback = null
                        false
                    }
                }
            }

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

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 页面加载完成后注入，确保安全区生效
                    injectTopInset()
                }
            }
        }
        webViewRef = webView
        setContentView(webView)

        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            statusBarDp = (top / resources.displayMetrics.density).toInt()
            injectTopInset()
            insets
        }

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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMAGE) return
        val cb = filePathCallback ?: return
        filePathCallback = null
        val uris: Array<Uri>? = if (resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) {
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        } else null
        cb.onReceiveValue(uris ?: emptyArray())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 原型只有一个页面，直接退出，避免返回空白
        super.onBackPressed()
    }
}
