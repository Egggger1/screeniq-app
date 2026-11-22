package com.screeniq.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var tvConn: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var swRead: Switch
    private lateinit var swOverlay: Switch
    private lateinit var swAction: Switch
    private lateinit var swExplain: Switch
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tvGuide: TextView
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
        tvService = TextView(this).apply { textSize = 13f; setTextColor(android.graphics.Color.parseColor("#DDE5E1")); setPadding(24, 18, 24, 6) }
        tvConn = TextView(this).apply { textSize = 13f; setTextColor(android.graphics.Color.parseColor("#DDE5E1")); setPadding(24, 6, 24, 6) }
        tvOverlay = TextView(this).apply { textSize = 13f; setTextColor(android.graphics.Color.parseColor("#DDE5E1")); setPadding(24, 6, 24, 18) }
        val statusCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#172724"))
            addView(tvService)
            addView(tvConn)
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

        // ---- 下一步引导卡（把权限→开关→切后台串起来）----
        tvGuide = TextView(this).apply {
            textSize = 13f
            setLineSpacing(5f, 1.25f)
            setTextColor(android.graphics.Color.parseColor("#FFD98F"))
            setPadding(24, 20, 24, 20)
        }
        val guideCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1F3A35"))
            addView(tvGuide)
        }
        root.addView(guideCard, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 18 })

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
            refreshGuide()
        }
        swOverlay.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            if (v && !Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                swOverlay.isChecked = false
                return@setOnCheckedChangeListener
            }
            cfg.setOverlay(v)
            refreshGuide()
        }
        swAction.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            cfg.setAutoAction(v)
            if (v) Toast.makeText(this, "自动操控已开启：会尝试自动点选答案。只在自己练习时使用。", Toast.LENGTH_LONG).show()
            refreshGuide()
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
        tvService.text = if (enabled) "● 读屏开关：已开启" else "○ 读屏开关：未开启（点下方按钮）"
        tvService.setTextColor(android.graphics.Color.parseColor(if (enabled) "#5DCAA5" else "#FFD98F"))
        val connected = cfg.serviceConnected() && enabled
        tvConn.text = if (connected) "● 服务连接：已连接，正在后台待命"
                      else if (enabled) "○ 服务连接：未连接（开关刚开请等几秒，或锁屏再解锁）"
                      else "○ 服务连接：未连接（先开读屏开关）"
        tvConn.setTextColor(android.graphics.Color.parseColor(if (connected) "#5DCAA5" else "#FFD98F"))
        val ov = Settings.canDrawOverlays(this)
        tvOverlay.text = if (ov) "● 悬浮窗权限：已开启" else "○ 悬浮窗权限：未开启（点下方按钮）"
        tvOverlay.setTextColor(android.graphics.Color.parseColor(if (ov) "#5DCAA5" else "#FFD98F"))
        refreshGuide()
    }

    /** 动态引导：明确告诉用户「现在到第几步、接下来做什么」，把权限→开关→切后台串成一条线。 */
    @SuppressLint("SetTextI18n")
    private fun refreshGuide() {
        val readOn = isServiceEnabled()
        val connected = cfg.serviceConnected() && readOn
        val ovOn = Settings.canDrawOverlays(this)
        val autoRead = cfg.autoRead()
        val text: String
        val color: String
        when {
            !readOn && !ovOn -> {
                text = "第 1 步：点上面两个按钮，把「读屏权限」和「悬浮窗权限」都打开。\n" +
                       "读屏权限在系统设置页里找到「屏知 ScreenIQ」打开；两个都变绿后继续。"
                color = "#FFD98F"
            }
            !readOn -> {
                text = "还差读屏权限：点「① 开启读屏权限」，在系统设置里找到「屏知 ScreenIQ」打开。\n" +
                       "回来后这里会自动变绿。"
                color = "#FFD98F"
            }
            !connected -> {
                text = "读屏开关已开，但服务还没连上。系统刚开权限常要等几秒——\n" +
                       "请锁屏再解锁一次，或把本 App 从后台划掉重开，再看「服务连接」是否变绿。"
                color = "#FFD98F"
            }
            !ovOn -> {
                text = "读屏服务已连上。还差悬浮窗权限：点「② 开启悬浮窗权限」并允许。\n" +
                       "这个权限让答案能浮在题目上方。"
                color = "#FFD98F"
            }
            !autoRead -> {
                text = "✓ 权限和服务都好了。现在把下面「自动识屏」开关打开，就算全部就绪。"
                color = "#8FD8FF"
            }
            else -> {
                text = "✓✓ 全部就绪！现在直接切到你的题库 App 就行——\n" +
                       "这个界面可以放后台、也可以划掉关掉。切过去后答案会自动浮出来，日志会记在这里。"
                color = "#5DCAA5"
            }
        }
        tvGuide.text = text
        tvGuide.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun refreshLog() {
        val text = cfg.log()
        tvLog.text = if (text.isBlank()) "还没有记录。开启「自动识屏」后，读到的题目和答案会出现在这里。" else text
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * 判断系统无障碍开关是否打开。
     * 直接读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES——这正是系统设置页显示"已开启/已关闭"
     * 所依据的权威数据源。不要用 getEnabledAccessibilityServiceList(FEEDBACK_GENERIC)：
     * 那套查询带反馈类型过滤、且各 ROM 实现不一（荣耀 MagicOS 上尤其查不到），
     * 会出现"系统开了、App 误判没开"的假阴性。
     */
    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val target = "$packageName/${ScreenReadService::class.java.name}"
        return enabled.split(':').any { entry ->
            // 兼容两种写法：com.x/com.x.Svc 与 com.x/.Svc
            entry.equals(target, ignoreCase = true) ||
            (entry.contains(packageName, ignoreCase = true) && entry.substringAfter('/').trimStart('.').equals(
                ScreenReadService::class.java.simpleName, ignoreCase = true))
        }
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
