package com.screeniq.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 后台识屏控制面板（App 主界面）。
 * 提供：读屏服务开关、悬浮窗开关、自动操控开关、详细解释开关，
 * 权限状态与一键跳转系统设置，以及实时读屏日志。
 */
class ControlActivity : AppCompatActivity() {

    private lateinit var cfg: ConfigStore
    private lateinit var tvService: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var swRead: Switch
    private lateinit var swOverlay: Switch
    private lateinit var swAction: Switch
    private lateinit var swExplain: Switch
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private var initializing = true   // 抑制初始化时开关回调

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshServiceState()
        }
    }
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshLog()
        }
    }

    companion object {
        private const val REQ_OVERLAY = 2001
        private const val REQ_NOTIFICATION = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = ConfigStore(this)
        buildUi()
        syncSwitches()
        refreshServiceState()
        refreshLog()
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val pad = 44
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#091312"))
            setPadding(pad, 90, pad, pad)
        }

        val title = TextView(this).apply {
            text = "屏知 ScreenIQ"
            setTextColor(android.graphics.Color.parseColor("#A3E8D8"))
            textSize = 26f
            setPadding(0, 0, 0, 6)
        }
        val subtitle = TextView(this).apply {
            text = "后台识屏 · 自动作答 · 答案在眼前\n个人备考自测使用，开关都在你手里"
            setTextColor(android.graphics.Color.parseColor("#AEC5BE"))
            textSize = 13f
            setLineSpacing(3f, 1.2f)
            setPadding(0, 0, 0, 26)
        }
        root.addView(title)
        root.addView(subtitle)

        // ---- 权限状态卡 ----
        tvService = TextView(this).apply { textSize = 13f; setTextColor(android.graphics.Color.parseColor("#DDE5E1")); setPadding(24, 20, 24, 10) }
        tvOverlay = TextView(this).apply { textSize = 13f; setTextColor(android.graphics.Color.parseColor("#DDE5E1")); setPadding(24, 6, 24, 20) }
        val statusCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#172724"))
            addView(tvService)
            addView(tvOverlay)
        }
        root.addView(statusCard)

        // ---- 权限按钮 ----
        val btnService = Button(this).apply {
            text = "① 开启读屏权限"
            isAllCaps = false
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#003831"))
            setBackgroundColor(android.graphics.Color.parseColor("#A3E8D8"))
            setOnClickListener { openAccessibilitySettings() }
        }
        val btnOverlay = Button(this).apply {
            text = "② 开启悬浮窗权限"
            isAllCaps = false
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#003831"))
            setBackgroundColor(android.graphics.Color.parseColor("#A3E8D8"))
            setOnClickListener { requestOverlayPermission() }
        }
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(btnService, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) })
            addView(btnOverlay, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 0, 0) })
        }
        root.addView(btnRow, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })

        // ---- 四个开关 ----
        swRead = makeSwitch(root, "自动识屏（后台读题并作答）")
        swOverlay = makeSwitch(root, "悬浮窗就地显示答案")
        swAction = makeSwitch(root, "自动操控（自动点选答案 · 默认关）")
        swExplain = makeSwitch(root, "详细解释（答案 + 解题步骤）")

        swRead.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            cfg.setAutoRead(v)
            if (v && !isServiceEnabled()) {
                Toast.makeText(this, "请先点上面的「开启读屏权限」", Toast.LENGTH_LONG).show()
            }
        }
        swOverlay.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            if (v && !Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                swOverlay.isChecked = false
                return@setOnCheckedChangeListener
            }
            cfg.setOverlay(v)
        }
        swAction.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            cfg.setAutoAction(v)
            if (v) Toast.makeText(this, "自动操控已开启：会尝试自动点选答案。只在自己练习时使用。", Toast.LENGTH_LONG).show()
        }
        swExplain.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            cfg.setExplain(v)
        }

        // ---- 日志 ----
        val logTitle = TextView(this).apply {
            text = "读屏日志（最近 30 条）"
            setTextColor(android.graphics.Color.parseColor("#6F8B84"))
            textSize = 12f
            setPadding(0, 22, 0, 8)
        }
        root.addView(logTitle)
        tvLog = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#AEC5BE"))
            setLineSpacing(4f, 1.15f)
        }
        logScroll = ScrollView(this).apply { addView(tvLog) }
        root.addView(logScroll, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底部按钮 ----
        val btnClear = Button(this).apply {
            text = "清空日志"
            isAllCaps = false
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#AEC5BE"))
            setBackgroundColor(android.graphics.Color.parseColor("#203330"))
            setOnClickListener { cfg.clearLog(); refreshLog() }
        }
        val btnManual = Button(this).apply {
            text = "手动传图答题 →"
            isAllCaps = false
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#AEC5BE"))
            setBackgroundColor(android.graphics.Color.parseColor("#203330"))
            setOnClickListener { startActivity(Intent(this@ControlActivity, MainActivity::class.java)) }
        }
        val bottomRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(btnClear, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) })
            addView(btnManual, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 0, 0) })
        }
        root.addView(bottomRow, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })

        setContentView(root)
    }

    private fun makeSwitch(parent: android.widget.LinearLayout, label: String): Switch {
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#172724"))
            setPadding(24, 8, 24, 8)
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(android.graphics.Color.parseColor("#DDE5E1"))
            textSize = 13f
        }
        val sw = Switch(this)
        box.addView(tv, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(sw)
        parent.addView(box, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 })
        return sw
    }

    private fun syncSwitches() {
        swRead.isChecked = cfg.autoRead()
        swOverlay.isChecked = cfg.overlay()
        swAction.isChecked = cfg.autoAction()
        swExplain.isChecked = cfg.explain()
        initializing = false
    }

    @SuppressLint("SetTextI18n")
    private fun refreshServiceState() {
        val enabled = isServiceEnabled()
        tvService.text = if (enabled) "● 读屏权限：已开启" else "○ 读屏权限：未开启（点下方按钮）"
        tvService.setTextColor(android.graphics.Color.parseColor(if (enabled) "#5DCAA5" else "#FFD98F"))
        val ov = Settings.canDrawOverlays(this)
        tvOverlay.text = if (ov) "● 悬浮窗权限：已开启" else "○ 悬浮窗权限：未开启（点下方按钮）"
        tvOverlay.setTextColor(android.graphics.Color.parseColor(if (ov) "#5DCAA5" else "#FFD98F"))
    }

    private fun refreshLog() {
        val text = cfg.log()
        tvLog.text = if (text.isBlank()) "还没有记录。开启「自动识屏」后，读到的题目和答案会出现在这里。" else text
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return list.any { it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == ScreenReadService::class.java.name }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "在系统设置里找到「屏知 ScreenIQ」并打开", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQ_OVERLAY)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            refreshServiceState()
            if (Settings.canDrawOverlays(this)) swOverlay.isChecked = cfg.overlay()
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { registerReceiver(stateReceiver, IntentFilter(ScreenReadService.ACTION_STATE)) }
        runCatching { registerReceiver(logReceiver, IntentFilter(ConfigStore.ACTION_LOG)) }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(logReceiver) }
        super.onStop()
    }
}
