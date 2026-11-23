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
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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
    private lateinit var flowBorder: FlowBorderView
    private lateinit var spProvider: Spinner
    private lateinit var etKey: EditText
    private lateinit var tvAiStatus: TextView
    private lateinit var swBorder: Switch
    private lateinit var swBubble: Switch
    private var initializing = true   // 抑制初始化时开关回调
    private var leavingForPermission = false   // 跳系统设置期间别把悬浮球盖在设置页上

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshServiceState()
            applySwitches()
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
        refreshAiStatus()
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
        AppVisible.foreground = true
        leavingForPermission = false
        ControlBubble.hide()      // 面板在前台时不需要悬浮球，避免两套入口打架
        refreshServiceState()
        applySwitches()           // 悬浮球上按过开始/暂停后，面板里的开关要跟着变
    }

    override fun onPause() {
        AppVisible.foreground = false
        // 退出面板 = 自动变成悬浮球；但去系统设置授权时不要盖在设置页上（审查 L-15）
        if (!leavingForPermission && cfg.bubble() && ScreenReadService.instance != null) ControlBubble.show(this)
        else ControlBubble.hide()
        super.onPause()
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val pad = dp(16)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#091312"))
            setPadding(pad, dp(28), pad, pad)
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
        swAction = makeSwitch(root, "自动作答（答案一到就自己点选项）")
        swExplain = makeSwitch(root, "详细解释（答案 + 解题步骤）")

        swRead.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            // 没权限就不能把"开"持久化，否则面板显示开着、服务却没连上（审查 M-8）
            if (v && !isServiceEnabled()) {
                Toast.makeText(this, "请先点上面的「开启读屏权限」", Toast.LENGTH_LONG).show()
                initializing = true
                swRead.isChecked = false
                initializing = false
                cfg.setAutoRead(false)
                openAccessibilitySettings()
                refreshGuide()
                return@setOnCheckedChangeListener
            }
            cfg.setAutoRead(v)
            refreshGuide()
            syncBorders()
        }
        swOverlay.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            if (v && !Settings.canDrawOverlays(this)) {
                // 回滚必须包 initializing，否则重入监听器会把原配置写成 false（审查 M-22）
                initializing = true
                swOverlay.isChecked = cfg.overlay()
                initializing = false
                requestOverlayPermission()
                return@setOnCheckedChangeListener
            }
            cfg.setOverlay(v)
            refreshGuide()
        }
        swAction.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            cfg.setAutoAction(v)
            ControlBubble.updateAutoAction(v)   // 悬浮球菜单里的那一项跟着变
            if (v) Toast.makeText(this, "自动作答已开启：答案一出来就自己点选。只在自己练习时使用。", Toast.LENGTH_LONG).show()
            refreshGuide()
        }
        swExplain.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            cfg.setExplain(v)
        }

        // ---- 排障：立刻识屏试一次（不用等屏幕变化自己触发）----
        val btnTry = Button(this).apply {
            text = "⚡ 立刻识屏试一次"
            isAllCaps = false
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#A3E8D8"))
            setBackgroundColor(android.graphics.Color.parseColor("#1F3A35"))
            setOnClickListener {
                if (!isServiceEnabled() || !cfg.serviceConnected()) {
                    Toast.makeText(
                        this@ControlActivity,
                        "读屏服务还没连上：先开读屏权限，等「服务连接」变绿再试",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // 留 4 秒给你切到题目页，到点它会读当前屏幕并作答
                    sendBroadcast(
                        Intent(ScreenReadService.ACTION_READ_NOW)
                            .setPackage(packageName)
                            .putExtra(ScreenReadService.EXTRA_DELAY, 4000L)
                    )
                    Toast.makeText(this@ControlActivity, "4 秒后读屏，现在切到题目页面", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnTry, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })

        val tryTip = TextView(this).apply {
            text = "平时不用点它：切到题目页会自动读（页面变化会触发，静止时约每 1.2 秒也会巡检一次）。" +
                   "这个按钮只用来排障——点了之后 4 秒内切到题目页，它会把每一步写进下面的日志：" +
                   "「读题」= 读到了；「跳过」= 判定不是题目；「操控」= 点选过程；「失败」= 密钥或网络问题。"
            setTextColor(android.graphics.Color.parseColor("#6F8B84"))
            textSize = 11.5f
            setLineSpacing(3f, 1.25f)
            setPadding(0, 8, 0, 0)
        }
        root.addView(tryTip)

        // ---- AI 模型配置（直接在控制面板填，不用再进传图界面）----
        val aiTitle = TextView(this).apply {
            text = "AI 模型（DeepSeek 只读文字；Kimi / 通义 / OpenAI 还能看图认字）"
            setTextColor(android.graphics.Color.parseColor("#6F8B84"))
            textSize = 12f
            setPadding(0, 24, 0, 8)
        }
        root.addView(aiTitle)

        val aiCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#172724"))
            setPadding(24, 18, 24, 18)
        }

        spProvider = Spinner(this).apply {
            val names = Providers.list.map { it["name"] as String }
            adapter = object : ArrayAdapter<String>(
                this@ControlActivity, android.R.layout.simple_spinner_item, names
            ) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val tv = super.getView(position, convertView, parent) as TextView
                    tv.setTextColor(android.graphics.Color.parseColor("#DDE5E1"))
                    tv.textSize = 13f
                    return tv
                }
                override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val tv = super.getDropDownView(position, convertView, parent) as TextView
                    tv.setTextColor(android.graphics.Color.parseColor("#DDE5E1"))
                    tv.setBackgroundColor(android.graphics.Color.parseColor("#1F3A35"))
                    tv.textSize = 13f
                    tv.setPadding(dp(18), dp(14), dp(18), dp(14))
                    return tv
                }
            }
            val idx = Providers.list.indexOfFirst { it["id"] == cfg.provider() }
            if (idx >= 0) setSelection(idx)
        }
        aiCard.addView(spProvider, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))

        etKey = EditText(this).apply {
            hint = "粘贴 API Key（已填过的会保留，留空即不修改）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#DDE5E1"))
            setHintTextColor(android.graphics.Color.parseColor("#6F8B84"))
            setPadding(0, 18, 0, 18)
        }
        aiCard.addView(etKey)

        val btnSaveKey = Button(this).apply {
            text = "保存模型配置"
            isAllCaps = false
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#003831"))
            setBackgroundColor(android.graphics.Color.parseColor("#A3E8D8"))
            setOnClickListener { saveAiConfig() }
        }
        aiCard.addView(btnSaveKey)

        tvAiStatus = TextView(this).apply {
            textSize = 12f
            setLineSpacing(3f, 1.15f)
            setPadding(0, 14, 0, 0)
        }
        aiCard.addView(tvAiStatus)
        root.addView(aiCard, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- 全局运行光边框 + 常驻悬浮球 ----
        swBorder = makeSwitch(root, "全局运行光线边框（切到任意 App 可见）")
        swBubble = makeSwitch(root, "退出后显示悬浮球（开始 / 暂停 / 识屏）")

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
        root.addView(logScroll, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))

        // ---- 底部按钮 ----
        val btnClear = Button(this).apply {
            text = "清空日志"
            isAllCaps = false
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#AEC5BE"))
            setBackgroundColor(android.graphics.Color.parseColor("#203330"))
            setOnClickListener { cfg.clearLog(); refreshLog() }
        }
        root.addView(btnClear, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })

        swBorder.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            cfg.setGlobalBorder(v)
            syncBorders()
        }

        swBubble.setOnCheckedChangeListener { _, v ->
            if (initializing) return@setOnCheckedChangeListener
            cfg.setBubble(v)
            if (v) {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                } else if (ScreenReadService.instance != null && !AppVisible.foreground) {
                    ControlBubble.show(this)
                }
            } else {
                ControlBubble.hide()
            }
        }

        // ---- 组装：可滚动内容 + 最上层流动光边框 ----
        val contentScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#091312"))
            addView(contentScroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        flowBorder = FlowBorderView(this)
        // 边框叠在内容之上但绝不拦截触摸（FlowBorderView.onTouchEvent 恒 false）
        frame.addView(flowBorder, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(frame)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 保存 AI 配置；密钥留空表示不修改已存值。 */
    private fun saveAiConfig() {
        val pid = Providers.list.getOrNull(spProvider.selectedItemPosition)?.get("id") as? String ?: "deepseek"
        val key = etKey.text.toString().trim()
        val switched = cfg.provider() != pid
        cfg.save(pid, key, "", "")
        etKey.setText("")
        // 换服务商时旧 key 已被清掉，绝不能给"密钥未改动"这种误导提示（审查 S-2）
        val name = Providers.find(pid)?.get("name") ?: pid
        val msg = when {
            switched && key.isBlank() -> "已切换到 $name，旧密钥已清除，请重新填写该服务商的密钥"
            switched -> "已切换到 $name 并保存密钥"
            key.isBlank() -> "密钥未改动"
            else -> "已保存，切到题库 App 即可真答题"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        refreshAiStatus()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshAiStatus() {
        val key = cfg.apiKey()
        val p = Providers.find(cfg.provider())
        val name = p?.get("name") ?: cfg.provider()
        if (key.isBlank()) {
            tvAiStatus.text = "当前：$name · 未填密钥，不会作答"
            tvAiStatus.setTextColor(android.graphics.Color.parseColor("#FFD98F"))
        } else {
            val masked = if (key.length >= 8) "${key.take(4)}••••${key.takeLast(4)}" else "••••"
            val enc = if (cfg.keyIsEncrypted()) "已加密" else "明文降级（本机不支持 Keystore）"
            tvAiStatus.text = "当前：$name · 已配置 $masked（$enc）"
            tvAiStatus.setTextColor(android.graphics.Color.parseColor("#5DCAA5"))
        }
    }

    /** 同步边框动画：服务连上且自动识屏开着才动；全局光边框按开关显示。 */
    private fun syncBorders() {
        val running = isServiceEnabled() && cfg.serviceConnected() && cfg.autoRead()
        flowBorder.setActive(running)
        if (running && cfg.globalBorder()) RunningBorderOverlay.show(this) else RunningBorderOverlay.hide()
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

    /** 从配置回填所有开关。initializing 期间屏蔽回调，避免"回填"被当成"用户操作"。 */
    private fun applySwitches() {
        initializing = true
        swRead.isChecked = cfg.autoRead()
        swOverlay.isChecked = cfg.overlay()
        swAction.isChecked = cfg.autoAction()
        swExplain.isChecked = cfg.explain()
        swBorder.isChecked = cfg.globalBorder()
        swBubble.isChecked = cfg.bubble()
        initializing = false
    }

    private fun syncSwitches() = applySwitches()

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
        syncBorders()
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
                text = "✓✓ 全部就绪！直接切到题库 App 就行——\n" +
                       "退出本界面后，屏幕右侧挂一粒「屏知」悬浮球，点开有四项：\n" +
                       "⚡ 识屏作答 / ◎ 自动作答开关 / 暂停 / 面板。\n" +
                       "把「自动作答」打开，答案一出来它自己就点上去。"
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
        leavingForPermission = true
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "在系统设置里找到「屏知 ScreenIQ」并打开", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            leavingForPermission = true
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
        // targetSdk 35 / Android 14+ 必须显式声明导出标志，否则注册直接抛 SecurityException。
        // 以前被 runCatching 吞掉，面板看不到状态和日志刷新（审查 M-7）。
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(ScreenReadService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, logReceiver, IntentFilter(ConfigStore.ACTION_LOG),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(logReceiver) }
        super.onStop()
    }
}
