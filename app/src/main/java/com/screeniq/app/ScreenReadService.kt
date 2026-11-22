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
 * 流程：屏幕变化 → 取字（无障碍节点优先，读不到用截图 OCR 兜底）
 *      → 粗筛（看有没有"题干 + 选项"的结构）→ 送 AI（AI 再判一次是不是题）
 *      → 悬浮窗就地显示答案 →（可选）自动点选。
 *
 * 合规设计：自动识屏/自动操控都是客户端开关，默认关闭，用户手动开启才生效；
 * 程序不判断使用场景；屏幕内容只在内存处理，不落盘、不上传，日志只记摘要。
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
    private var busy = false

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_READ_NOW) {
                // 手动"立刻试一次"：清掉去重和节流，强制读当前屏
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
        private const val MIN_ASK_INTERVAL_MS = 3000L     // 两次答题最短间隔
        private const val MIN_ATTEMPT_INTERVAL_MS = 700L  // 事件风暴节流
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
            // 界面内容变化、滚动、点击后状态变化都要能收到，否则答完一题就不再触发
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        cfg.setServiceConnected(true)
        cfg.appendLog("[服务] 读屏服务已连接")
        postNotification()

        runCatching {
            ContextCompat.registerReceiver(
                this, cmdReceiver, IntentFilter(ACTION_READ_NOW),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        main.post {
            if (cfg.autoRead() && cfg.globalBorder()) RunningBorderOverlay.show(this)
        }
        broadcastState(true)
        main.post {
            android.widget.Toast.makeText(this, "屏知读屏服务已连接", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!cfg.autoRead()) return
        // 不读自己 App 的界面，避免自我触发
        if (event.packageName?.toString() == packageName) return
        // 锁屏状态不读，防止把锁屏/通知栏当题目
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) return

        readOnce(allowOcr = true, manual = false)
    }

    /**
     * 读一次当前屏幕并尝试作答。
     * @param allowOcr 节点文字不够时是否允许截图 OCR 兜底
     * @param manual  是否是用户手动点"立刻试一次"（跳过总开关与节流）
     */
    private fun readOnce(allowOcr: Boolean, manual: Boolean) {
        if (!manual && !cfg.autoRead()) return
        if (busy) return

        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < MIN_ATTEMPT_INTERVAL_MS) return
        if (now - lastAskAt < MIN_ASK_INTERVAL_MS) return
        lastAttemptAt = now

        val root = runCatching { rootInActiveWindow }.getOrNull()
        val nodeText = runCatching { collectText(root) }.getOrDefault("").trim()
        val optionCount = runCatching { countShortClickable(root) }.getOrDefault(0)
        pendingRoot = root

        scope.launch {
            var text = nodeText
            var source = "节点"

            // 节点没拿到"题干 + 选项"的结构时，用截图 OCR 补
            val nodeUseful = text.length >= 20 && optionCount >= 2
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
            if (text.length < MIN_TEXT_LEN) {
                if (manual) {
                    cfg.appendLog("[识屏] 没读到文字（节点 ${nodeText.length} 字）")
                    postNotification("这次没读到屏幕文字，换个页面再试")
                }
                return@launch
            }

            val fp = fingerprint(text)
            if (fp == lastFingerprint) return@launch
            if (!looksLikeQuestion(text)) {
                if (manual) {
                    cfg.appendLog("[识屏] 读到 ${text.length} 字，但看着不是题目：${text.take(60).replace('\n', ' ')}")
                    postNotification("读到 ${text.length} 字，但这不是题目")
                }
                return@launch
            }

            // 认下这道题，避免同一屏反复问
            lastFingerprint = fp
            lastAskAt = System.currentTimeMillis()
            busy = true
            broadcastState(true)

            val preview = text.take(110).replace('\n', ' ')
            cfg.appendLog("[读题·$source] $preview")
            postNotification("读到题目，正在问 AI…")

            val result = runCatching { ai.answer(buildQuestion(text), cfg.explain()) }
                .getOrElse { AiResult("", "本地", false, "异常：${it.javaClass.simpleName}") }
            busy = false

            val answer = result.answer.trim()
            when {
                !result.ok || answer.isBlank() -> {
                    val why = result.note.ifBlank { "没有返回内容" }
                    cfg.appendLog("[失败] $why")
                    postNotification("这次没答出来：$why")
                }
                isNotAQuestion(answer) -> {
                    cfg.appendLog("[跳过] 模型判断不是题目（可能是列表/聊天/广告页）")
                }
                else -> {
                    cfg.appendLog("[答案] ${answer.take(120).replace('\n', ' ')}")
                    withContext(Dispatchers.Main) { showAnswer(answer) }
                }
            }
        }
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

    /** 数一数屏上有几个"短文本且可点"的节点——通常就是选项按钮。 */
    private fun countShortClickable(node: AccessibilityNodeInfo?, depth: Int = 0): Int {
        if (node == null || depth > 40) return 0
        var n = 0
        val t = node.text?.toString()?.trim().orEmpty()
        if (node.isClickable && t.length in 1..12 && !node.isEditable) n++
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            n += countShortClickable(child, depth + 1)
        }
        return n
    }

    private fun fingerprint(text: String): String =
        text.replace(Regex("[\\s\\u3000]+"), " ").take(400)

    /**
     * 粗筛"像不像题目"。
     * 关键教训：很多题库/问答页根本不打问号、不用 A/B/C/D，只给"题干 + 两个按钮"。
     * 所以判据必须包含"枚举结构"本身，不能只靠标点。
     */
    private fun looksLikeQuestion(text: String): Boolean {
        if (text.length < MIN_TEXT_LEN) return false

        // 1) 强特征：问号、选项字母、常见题干词
        val strong = listOf(
            "？", "?", "（ ）", "( )", "下列", "以下", "选项", "正确的是", "错误的是",
            "判断", "填空", "多少", "哪项", "哪一", "计算", "求值"
        )
        if (strong.any { text.contains(it) }) return true

        // 2) 结构特征：屏上有 ≥2 个短文本可点节点（典型二选一/多选一）+ 屏上有题干长度
        val opts = runCatching { countShortClickable(pendingRoot) }.getOrDefault(0)
        if (opts >= 2 && text.length >= 8) return true

        // 3) 弱特征：题库类页面常见措辞（这题"知识问答 西湖龙井产自"就是靠这条救回来）
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

    /** 把整个屏幕文字交给模型，同时要求它先判断"这里到底有没有题"。 */
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
     * 按答案去点选项。两种匹配：先按答案里的文字（"杭州"）找，再按选项字母找。
     * 只在「自动操控」开着且用户点了悬浮窗上的按钮时才调用；找不到就跳过，不瞎点。
     */
    private fun autoClickAnswer(answer: String) {
        val root = pendingRoot
        if (root == null) {
            cfg.appendLog("[操控] 界面已变，跳过自动点选")
            return
        }

        // 先按答案文字匹配（最可靠：选项本身是"杭州"/"苏州"这种词）
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

        // 退一步：按选项字母
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
    private fun postNotification(text: String = "屏知正在后台识屏，点击打开") {
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
        runCatching { unregisterReceiver(cmdReceiver) }
        stopAll()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1001)
        }
        super.onDestroy()
    }
}
