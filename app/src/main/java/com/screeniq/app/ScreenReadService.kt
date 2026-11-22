package com.screeniq.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后台自动识屏核心服务。
 *
 * 触发有三条通道，确保"不用手点也会自己工作"：
 *  1. 无障碍事件（页面切换、内容变化、滚动）→ 延迟 350ms 等渲染稳定再读；
 *  2. 稳定重试：读到空白/半截内容时，隔 600~1200ms 再读一次，最多 3 轮；
 *  3. 低频巡检：每 3 秒轻量比对一次屏幕指纹，变了就完整读一次
 *     —— 专治"页面静止后 App 不再发事件"。
 *
 * 取字两层：无障碍节点优先（快），读不到"题干+选项"结构时用截图 OCR 兜底。
 * 判题两层：本地粗筛（结构/关键词）+ 模型二次确认（答 NONE 就不弹窗）。
 *
 * 合规：自动识屏/自动操控都是客户端开关，默认关闭，用户手动开启才生效；
 * 程序不判断使用场景；屏幕内容只在内存处理，不落盘、不上传。
 */
class ScreenReadService : AccessibilityService() {

    private lateinit var cfg: ConfigStore
    private lateinit var ai: AiClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** 最近一次读屏对应的节点树，自动点选时按它去找可点选项 */
    private var pendingRoot: AccessibilityNodeInfo? = null

    private var lastFingerprint = ""
    private var lastAskAt = 0L
    private var lastAttemptAt = 0L
    private var lastEventAt = 0L
    private var busy = false
    private var heartbeat: Runnable? = null

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_READ_NOW) {
                // 手动"立刻试一次"：清掉去重和冷却，强制读当前屏
                lastFingerprint = ""
                lastAskAt = 0
                lastAttemptAt = 0
                val delay = intent.getLongExtra(EXTRA_DELAY, 0L)
                if (delay > 0) {
                    main.postDelayed({ readOnce(allowOcr = true, manual = true) }, delay)
                } else {
                    readOnce(allowOcr = true, manual = true)
                }
            }
        }
    }

    companion object {
        const val ACTION_STATE = "com.screeniq.READ_STATE"
        const val EXTRA_RUNNING = "running"
        const val ACTION_READ_NOW = "com.screeniq.READ_NOW"
        const val EXTRA_DELAY = "delay_ms"

        private const val CHANNEL_ID = "screeniq_read_channel"

        private const val MIN_ASK_INTERVAL_MS = 1200L      // 两次答题最短间隔（靠指纹去重，不用太长）
        private const val MIN_ATTEMPT_INTERVAL_MS = 400L   // 事件风暴节流
        private const val FAIL_COOLDOWN_MS = 6000L         // 失败后同一屏的冷却，避免狂刷接口
        private const val SETTLE_DELAY_MS = 350L           // 事件后等页面渲染稳定
        private const val HEARTBEAT_MS = 3000L             // 巡检周期
        private const val MAX_ATTEMPTS = 3                 // 一轮最多读几次
        private const val MIN_TEXT_LEN = 6
        private const val MAX_QUESTION_CHARS = 1600
    }

    override fun onCreate() {
        super.onCreate()
        cfg = ConfigStore(this)
        ai = AiClient(cfg)
        createChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        cfg.setServiceConnected(true)
        cfg.appendLog("[服务] 读屏服务已连接，开始自动待命")
        postNotification()

        runCatching {
            ContextCompat.registerReceiver(
                this, cmdReceiver, IntentFilter(ACTION_READ_NOW),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        startHeartbeat()
        if (cfg.autoRead() && cfg.globalBorder()) RunningBorderOverlay.show(this)
        broadcastState(true)
        main.post {
            android.widget.Toast.makeText(this, "屏知读屏服务已连接", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!cfg.autoRead()) return
        if (event.packageName?.toString() == packageName) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) return

        lastEventAt = System.currentTimeMillis()
        // 不立刻读：给页面一点渲染时间，减少"读到半截"
        main.postDelayed({ readOnce(allowOcr = true, manual = false) }, SETTLE_DELAY_MS)
    }

    /**
     * 低频巡检：App 不再发事件时（页面静止、WebView 不触发），靠它发现新题。
     * 只做"取节点文字 + 比对指纹"，指纹没变就几乎零成本。
     */
    private fun startHeartbeat() {
        if (heartbeat != null) return
        val r = object : Runnable {
            override fun run() {
                runCatching {
                    val now = System.currentTimeMillis()
                    // 事件还在流动就不用巡检，省电
                    if (cfg.autoRead() && !busy && now - lastEventAt > 1500) {
                        val root = rootInActiveWindow
                        val t = collectText(root).trim()
                        if (t.length >= MIN_TEXT_LEN && fingerprint(t) != lastFingerprint) {
                            if (now - lastAttemptAt >= MIN_ATTEMPT_INTERVAL_MS &&
                                now - lastAskAt >= 0
                            ) {
                                readOnce(allowOcr = true, manual = false)
                            }
                        }
                    }
                }
                main.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat = r
        main.postDelayed(r, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        heartbeat?.let { main.removeCallbacks(it) }
        heartbeat = null
    }

    /**
     * 读一次当前屏幕并尝试作答。
     * @param allowOcr 节点文字不够时是否允许截图 OCR 兜底
     * @param manual  用户手动点的"立刻试一次"（跳过总开关与节流，且不自动重试）
     * @param attempt 本轮第几次尝试（用于稳定重试）
     * @param force   重试时跳过冷却检查
     */
    private fun readOnce(
        allowOcr: Boolean,
        manual: Boolean,
        attempt: Int = 0,
        force: Boolean = false
    ) {
        if (!manual && !cfg.autoRead()) return
        if (busy) return

        val now = System.currentTimeMillis()
        if (!force && !manual) {
            if (now - lastAttemptAt < MIN_ATTEMPT_INTERVAL_MS) return
            if (now - lastAskAt < MIN_ASK_INTERVAL_MS) return
        }
        lastAttemptAt = now

        val root = runCatching { rootInActiveWindow }.getOrNull()
        val nodeText = runCatching { collectText(root) }.getOrDefault("").trim()
        val shortTexts = runCatching { countShortTexts(root) }.getOrDefault(0)
        pendingRoot = root

        scope.launch {
            busy = true
            try {
                var text = nodeText
                var source = "节点"

                // 节点里已经有"题干 + 若干短选项"就不用 OCR（省 1~2 秒）
                val nodeUseful = text.length >= 12 && shortTexts >= 2
                if (!nodeUseful && allowOcr && ScreenshotOcr.supported &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ) {
                    val ocr = runCatching { ScreenshotOcr.readScreen(this@ScreenReadService) }.getOrNull()
                    if (!ocr.isNullOrBlank() && ocr.length > text.length) {
                        text = ocr
                        source = "OCR"
                    }
                }
                text = text.trim()

                // --- 情况 1：文字太少，可能页面还没渲染完 ---
                if (text.length < MIN_TEXT_LEN) {
                    if (!manual && retryLater(allowOcr, attempt)) return@launch
                    if (manual) {
                        cfg.appendLog("[识屏] 没读到文字（节点 ${nodeText.length} 字）")
                        postNotification("这次没读到屏幕文字，换个页面再试")
                    }
                    return@launch
                }

                // --- 情况 2：内容没变，跳过 ---
                val fp = fingerprint(text)
                if (fp == lastFingerprint) return@launch

                // --- 情况 3：粗筛不像题目 ---
                if (!looksLikeQuestion(text)) {
                    if (!manual && retryLater(allowOcr, attempt)) return@launch
                    if (manual) {
                        cfg.appendLog("[识屏] 读到 ${text.length} 字，但看着不是题目：${text.take(60).replace('\n', ' ')}")
                        postNotification("读到 ${text.length} 字，但这不像题目")
                    }
                    return@launch
                }

                cfg.appendLog("[读题·$source] ${text.take(110).replace('\n', ' ')}")
                postNotification("读到题目，正在问 AI…")
                if (!manual) {
                    main.post {
                        runCatching {
                            android.widget.Toast.makeText(
                                this@ScreenReadService, "屏知：读到题目，正在问 AI…",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                broadcastState(true)

                val result = runCatching { ai.answer(buildQuestion(text), cfg.explain()) }
                    .getOrElse { AiResult("", "本地", false, "异常：${it.javaClass.simpleName}") }
                val answer = result.answer.trim()

                when {
                    !result.ok || answer.isBlank() -> {
                        val why = result.note.ifBlank { "没有返回内容" }
                        cfg.appendLog("[失败] $why")
                        postNotification("这次没答出来：$why")
                        // 不记指纹：改完密钥/网络恢复后还能重试；但先冷却一会儿，别狂刷
                        lastAskAt = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                    }
                    isNotAQuestion(answer) -> {
                        cfg.appendLog("[跳过] 模型判断不是题目")
                        lastFingerprint = fp
                        lastAskAt = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                    }
                    else -> {
                        cfg.appendLog("[答案] ${answer.take(120).replace('\n', ' ')}")
                        lastFingerprint = fp
                        lastAskAt = System.currentTimeMillis()
                        withContext(Dispatchers.Main) { showAnswer(answer) }
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    /** 稳定重试：页面可能还在渲染，隔一会儿再读，最多 3 轮。 */
    private fun retryLater(allowOcr: Boolean, attempt: Int): Boolean {
        if (attempt + 1 >= MAX_ATTEMPTS) return false
        main.postDelayed({
            readOnce(allowOcr = allowOcr, manual = false, attempt = attempt + 1, force = true)
        }, 600L * (attempt + 1))
        return true
    }

    private fun showAnswer(answer: String) {
        if (cfg.overlay() && android.provider.Settings.canDrawOverlays(this)) {
            AnswerOverlay.show(
                context = this,
                answer = answer,
                allowAutoClick = cfg.autoAction(),
                onAutoClick = { autoClickAnswer(answer) }
            )
        } else {
            postNotification("答案：${answer.take(120).replace('\n', ' ')}")
        }
    }

    // ======================= 取字 =======================

    /** 遍历当前窗口收集可见文字。只读 getText，绝不写。不再 recycle（API 33 起已废弃且易崩）。 */
    private fun collectText(node: AccessibilityNodeInfo?, depth: Int = 0, sb: StringBuilder = StringBuilder()): String {
        if (node == null || depth > 40) return sb.toString()
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank()) sb.append(t).append('\n')
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc != t) sb.append(desc).append('\n')
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectText(child, depth + 1, sb)
        }
        return sb.toString()
    }

    /** 统计"1~12 字的短文本节点"数量。不要求 clickable——H5 的选项文字常常挂在不可点的子节点上。 */
    private fun countShortTexts(node: AccessibilityNodeInfo?, depth: Int = 0): Int {
        if (node == null || depth > 40) return 0
        var n = 0
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.length in 1..12 && !node.isEditable) n++
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            n += countShortTexts(child, depth + 1)
        }
        return n
    }

    private fun fingerprint(text: String): String =
        text.replace(Regex("[\\s\\u3000]+"), " ").take(400)

    /**
     * 粗筛"像不像题目"。宁可多问一次也别漏：
     * 很多题库/问答页不打问号、不用 A/B/C/D，只给"题干 + 几个按钮"。
     */
    private fun looksLikeQuestion(text: String): Boolean {
        if (text.length < MIN_TEXT_LEN) return false

        val strong = listOf(
            "？", "?", "（ ）", "( )", "下列", "以下", "选项", "正确的是", "错误的是",
            "判断", "填空", "多少", "哪项", "哪一", "计算", "求值"
        )
        if (strong.any { text.contains(it) }) return true

        val shortTexts = runCatching { countShortTexts(pendingRoot) }.getOrDefault(0)
        if (shortTexts >= 2 && text.length >= 8) return true

        val weak = listOf(
            "知识问答", "问答", "产自", "位于", "属于", "是什么", "是指", "意思",
            "作者", "成立于", "被称为", "出自", "哪个", "哪座", "哪种", "哪年"
        )
        return weak.any { text.contains(it) }
    }

    private fun isNotAQuestion(answer: String): Boolean {
        val a = answer.trim()
        if (a == "NONE" || a.startsWith("NONE")) return true
        return a.length <= 8 && (a.contains("不是题目") || a.contains("无法判断") || a.contains("信息不足"))
    }

    private fun buildQuestion(screen: String): String {
        val cleaned = screen.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc.add(s); acc }
            .joinToString("\n")
            .take(MAX_QUESTION_CHARS)

        return "下面是我手机当前屏幕上的全部文字，可能夹着 App 的标题、按钮、规则、广告等无关内容。\n" +
                "请你这样处理：\n" +
                "1. 先判断其中有没有一道可以作答的题目。没有就只回复一个词：NONE\n" +
                "2. 有题目就直接给答案。选项题请给出选项的文字（例如「杭州」），不要只给字母。\n" +
                "3. 如果题目信息不足、无法确定答案，回复：信息不足\n" +
                "屏幕文字如下：\n" +
                "------\n$cleaned\n------"
    }

    // ======================= 自动操控 =======================

    /**
     * 按答案去点选项。先按答案文字（"杭州"）匹配，再退到选项字母。
     * 只在「自动操控」开着且用户点了悬浮窗按钮时才调用；找不到就跳过，不瞎点。
     */
    private fun autoClickAnswer(answer: String) {
        val root = pendingRoot
        if (root == null) {
            cfg.appendLog("[操控] 界面已变，跳过自动点选")
            return
        }

        val candidates = answer
            .replace(Regex("[（(].*?[）)]"), " ")
            .split('\n', '，', ',', '。', '；', ';', '：', ':', '、', ' ')
            .map { it.replace(Regex("^[\\s\\-*·•#]+"), "").replace(Regex("[。．.!！?？]+$"), "").trim() }
            .filter { it.length in 2..14 }
            .distinct()
            .take(8)

        for (c in candidates) {
            val node = findClickableByText(root, c) ?: continue
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            cfg.appendLog(if (ok) "[操控] 已点选：$c" else "[操控] 点击失败：$c")
            return
        }

        val letter = Regex("(?<![A-Za-z])([ABCD])\\s*[.、．)）:：]").find(answer)?.groupValues?.get(1)
            ?: Regex("(?:答案选?|选)\\s*([ABCD])\\b").find(answer)?.groupValues?.get(1)
        if (letter != null) {
            val node = findClickableByText(root, letter)
            if (node != null && runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                cfg.appendLog("[操控] 已点选选项 $letter")
                return
            }
        }
        cfg.appendLog("[操控] 没找到对应的可点选项，跳过（不瞎点）")
    }

    private fun findClickableByText(node: AccessibilityNodeInfo?, target: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val t = node.text?.toString()?.trim().orEmpty()
        val hit = t.isNotEmpty() && t.length <= 20 &&
                (t == target || t == "$target." || t.equals(target, ignoreCase = true))
        if (node.isClickable && hit) return node
        // 选项文字常常挂在不可点的子节点上，父节点才是按钮 → 沿父链往上找
        if (hit && node.parent?.isClickable == true) return node.parent
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findClickableByText(child, target, depth + 1)
            if (found != null) return found
        }
        return null
    }

    // ======================= 杂项 =======================

    private fun broadcastState(isRunning: Boolean) {
        sendBroadcast(Intent(ACTION_STATE).putExtra(EXTRA_RUNNING, isRunning).setPackage(packageName))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun postNotification(text: String = "屏知正在后台待命，切到题目页会自动读") {
        val intent = Intent(this, ControlActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("屏知 ScreenIQ")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1001, notification)
        }
    }

    private fun stopAll() {
        cfg.setServiceConnected(false)
        RunningBorderOverlay.hide()
        AnswerOverlay.hide()
        broadcastState(false)
    }

    override fun onInterrupt() = stopAll()

    override fun onDestroy() {
        stopHeartbeat()
        runCatching { unregisterReceiver(cmdReceiver) }
        stopAll()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1001)
        }
        super.onDestroy()
    }
}
