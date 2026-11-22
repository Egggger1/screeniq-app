package com.screeniq.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
 * 触发三条通道：
 *  1. 无障碍事件（切页、内容变化、滚动）→ 延迟 220ms 等渲染稳定再读；
 *  2. 稳定重试：读到空白/半截内容，隔 600~1200ms 再读，最多 3 轮；
 *  3. 低频巡检：每 1.5 秒比对一次屏幕指纹，变了才读（事件活跃时跳过，省电）。
 *
 * 判题：本地强结构判据（选项标签 / 填空 / 题库措辞）+ 购物类噪声否决；
 *       过筛后直接送 AI，不再每张页面都问一遍，所以又快又少误弹。
 *
 * 自动操控：开关打开后，答案一到就**自动点选**对应选项（高置信匹配才动手），
 *           不需要用户再点浮窗按钮；匹配不上会说明原因，绝不瞎点。
 */
class ScreenReadService : AccessibilityService() {

    private lateinit var cfg: ConfigStore
    private lateinit var ai: AiClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** 最近一次读屏对应的节点树（自动点选时优先用实时树，这个只作兜底） */
    private var pendingRoot: AccessibilityNodeInfo? = null

    private var lastFingerprint = ""
    private var lastAskAt = 0L
    private var lastAttemptAt = 0L
    private var lastEventAt = 0L
    private var lastClickedFp = ""
    private var busy = false
    private var heartbeat: Runnable? = null

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_READ_NOW) {
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

        @Volatile
        var instance: ScreenReadService? = null

        private const val CHANNEL_ID = "screeniq_read_channel"

        private const val MIN_ASK_INTERVAL_MS = 600L      // 两次答题最短间隔
        private const val MIN_ATTEMPT_INTERVAL_MS = 300L   // 事件风暴节流
        private const val FAIL_COOLDOWN_MS = 5000L         // 失败后同一屏冷却
        private const val SETTLE_DELAY_MS = 220L           // 事件后等渲染稳定
        private const val HEARTBEAT_MS = 1500L             // 巡检周期
        private const val MAX_ATTEMPTS = 3
        private const val MIN_TEXT_LEN = 10
        private const val MAX_QUESTION_CHARS = 1000
        private const val AUTO_CLICK_DELAY_MS = 1200L      // 出答案后稍等再自动点，让你看清答案

        /** 题库/试卷措辞：出现即认为"这屏是题目"，不必等 AI 判。 */
        private val STRONG_WORDS = listOf(
            "下列", "以下选项", "选择题", "单选题", "多选题", "判断题", "填空题",
            "正确的是", "错误的是", "不正确的是", "补全", "填空", "知识问答",
            "选出", "请选择", "哪一项", "以下说法"
        )

        /** 购物/内容流特征词：只在"证据弱"时用来否决，避免把商品页当题目。 */
        private val NOISE_WORDS = listOf(
            "立即购买", "加入购物车", "购物车", "确认收货", "领礼物", "优惠券", "包邮",
            "规格", "已售", "关注", "点赞", "评论区", "直播", "秒杀", "下单", "结算",
            "运费", "券后", "满减"
        )
    }

    override fun onCreate() {
        super.onCreate()
        cfg = ConfigStore(this)
        ai = AiClient(cfg)
        createChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        cfg.setServiceConnected(true)
        cfg.appendLog("[服务] 读屏服务已连接，开始自动待命")
        runCatching {
            ContextCompat.registerReceiver(
                this, cmdReceiver, IntentFilter(ACTION_READ_NOW),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        startHeartbeat()
        syncIndicators()
        main.post {
            runCatching {
                android.widget.Toast.makeText(
                    this, "屏知读屏服务已连接", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ======================= 供悬浮球 / 面板调用 =======================

    fun isRunningNow(): Boolean = runCatching { cfg.autoRead() }.getOrDefault(false)

    /** 悬浮球上的「开始/暂停」：改总开关并同步指示灯、光边框。 */
    fun toggleAutoRead(): Boolean {
        val v = !cfg.autoRead()
        cfg.setAutoRead(v)
        if (v) {
            lastFingerprint = ""
            lastAskAt = 0
            lastAttemptAt = 0
        }
        cfg.appendLog(if (v) "[开关] 悬浮球：继续自动识屏" else "[开关] 悬浮球：已暂停")
        syncIndicators()
        return v
    }

    /** 悬浮球上的「识屏」：立刻读一次当前屏幕。 */
    fun readNowManual() {
        lastFingerprint = ""
        lastAskAt = 0
        lastAttemptAt = 0
        readOnce(allowOcr = true, manual = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!cfg.autoRead()) return
        if (event.packageName?.toString() == packageName) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) return

        lastEventAt = System.currentTimeMillis()
        main.postDelayed({ readOnce(allowOcr = true, manual = false) }, SETTLE_DELAY_MS)
    }

    /**
     * 低频巡检：App 不再发事件时（页面静止、WebView 不触发），靠它发现新题。
     * 只做"取节点文字 + 比对指纹"，指纹没变就几乎零成本；不在这里跑 OCR，省电。
     */
    private fun startHeartbeat() {
        if (heartbeat != null) return
        val r = object : Runnable {
            override fun run() {
                runCatching {
                    val now = System.currentTimeMillis()
                    if (cfg.autoRead() && !busy && now - lastEventAt > 700) {
                        val root = rootInActiveWindow
                        val t = collectText(root).trim()
                        if (t.length >= MIN_TEXT_LEN && fingerprint(t) != lastFingerprint) {
                            if (now - lastAttemptAt >= MIN_ATTEMPT_INTERVAL_MS) {
                                readOnce(allowOcr = false, manual = false)
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
     * @param manual  用户手动触发（跳过总开关与节流，且不自动重试）
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

        busy = true
        scope.launch {
            try {
                var text = nodeText
                var source = "节点"

                // 节点里已经有"题干 + 选项"结构就不用 OCR（省 1~2 秒）
                val nodeUseful = text.length >= 20 &&
                        (countOptionLabels(text) >= 1 || shortTexts >= 2)
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

                // --- 情况 3：粗筛不像题目（这一层挡掉商品页/内容流，既少误弹又快）---
                if (!looksLikeQuestion(text)) {
                    if (!manual && retryLater(allowOcr, attempt)) return@launch
                    if (manual) {
                        cfg.appendLog("[跳过] 读到 ${text.length} 字，判定不是题目：${text.take(60).replace('\n', ' ')}")
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
                withContext(Dispatchers.Main) {
                    // 先放一张"正在问 AI"的占位卡，让"它在动"立刻可见
                    if (cfg.overlay() && Settings.canDrawOverlays(this@ScreenReadService)) {
                        AnswerOverlay.showThinking(this@ScreenReadService)
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
                        AnswerOverlay.hide()   // 别把"正在问 AI"的占位卡留在屏幕上
                        lastAskAt = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                    }
                    isNotAQuestion(answer) -> {
                        cfg.appendLog("[跳过] 模型判断不是题目")
                        lastFingerprint = fp
                        lastAskAt = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                        AnswerOverlay.hide()
                    }
                    else -> {
                        cfg.appendLog("[答案] ${stripMd(answer).take(120).replace('\n', ' ')}")
                        lastFingerprint = fp
                        lastAskAt = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            showAnswer(answer)
                            // 自动操控：不用再点按钮，答案出来就自己点
                            scheduleAutoClick(answer, fp)
                        }
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
        val clean = stripMd(answer)
        if (cfg.overlay() && Settings.canDrawOverlays(this)) {
            AnswerOverlay.show(this, clean, cfg.autoAction()) { autoClickAnswer(answer, exactOnly = false) }
        } else {
            postNotification("答案：${clean.take(120)}")
        }
    }

    /**
     * 自动操控的主体：开关打开 → 延迟 1.2 秒（让你先看到答案）→ 自动点。
     * 只做"高置信"匹配（选项文字与答案规范化后完全相等），近乎不会点错；
     * 匹不上就写明原因，留在浮窗上让你手动点。
     */
    private fun scheduleAutoClick(answer: String, fp: String) {
        if (!cfg.autoAction()) return
        if (fp == lastClickedFp) return
        cfg.appendLog("[操控] 自动操控开启，准备点选…")
        main.postDelayed({
            val msg = autoClickAnswer(answer, exactOnly = true)
            if (msg.startsWith("已点选")) {
                lastClickedFp = fp
                AnswerOverlay.noteFeedback("已自动" + msg.removePrefix("已"))
            } else {
                cfg.appendLog("[操控] 自动点选未命中：$msg（浮窗上可手动点）")
                AnswerOverlay.noteFeedback("自动点选未命中，可手动点")
            }
        }, AUTO_CLICK_DELAY_MS)
    }

    // ======================= 取字 =======================

    /** 遍历当前窗口收集可见文字。只读 getText，绝不写。 */
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
     * 统计"像选项的行"数量：以 A./B、/（C）/D： 开头的短行。
     * 这是最可靠的题型证据，也是这版收紧判定的核心——上一版用"短文本 ≥2"，
     * 结果商品列表、礼物页全被当成题目。
     */
    private fun countOptionLabels(text: String): Int {
        var n = 0
        for (raw in text.lineSequence()) {
            val s = raw.trim()
            if (s.length !in 2..40) continue
            if (Regex("^[（(]?\\s*[A-DＡ-Ｄ][\\s.、．)）:：]").containsMatchIn(s)) n++
        }
        return n
    }

    /**
     * 判定"这屏是不是一道题"。分三级，只有决定性证据才放行：
     *  决定性：≥2 个选项标签 / 有填空横线 / 题库措辞
     *  弱证据：单独的问号 —— 必须同时至少有一个选项标签
     *  否决：证据弱且购物类特征词 ≥3 个
     */
    private fun looksLikeQuestion(text: String): Boolean {
        if (text.length < MIN_TEXT_LEN) return false

        val optionLabels = countOptionLabels(text)
        val hasBlank = Regex("[_＿]{2,}|（\\s{0,3}）|\\(\\s{0,3}\\)").containsMatchIn(text)
        val hasQMark = text.contains('？') || text.contains('?')
        val hasStrongWord = STRONG_WORDS.any { text.contains(it) }

        val decisive = optionLabels >= 2 || hasBlank || hasStrongWord
        if (!decisive) {
            return hasQMark && optionLabels >= 1
        }
        if (optionLabels < 2 && !hasBlank) {
            val noise = NOISE_WORDS.count { text.contains(it) }
            if (noise >= 3) return false
        }
        return true
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
     * 按答案去点选项，返回给界面看的结论（"已点选：xx" / "没找到对应选项"）。
     * @param exactOnly true=只接受"规范化后完全相等"的高置信匹配（自动点选用）
     */
    private fun autoClickAnswer(rawAnswer: String, exactOnly: Boolean): String {
        // 用实时节点树：读题时的旧树常常已失效，performAction 会返回 false
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: pendingRoot
        if (root == null) {
            cfg.appendLog("[操控] 拿不到当前界面节点")
            return "拿不到当前界面"
        }

        val cands = candidates(rawAnswer)
        cfg.appendLog("[操控] 候选：${cands.take(5).joinToString(" / ")}")

        for (c in cands) {
            val node = findOptionNode(root, c, exactOnly) ?: continue
            if (clickNode(node)) {
                cfg.appendLog("[操控] 已点选：$c")
                return "已点选：$c"
            }
            cfg.appendLog("[操控] 命中「$c」但点击失败")
        }

        // 字母兜底（"选 A" 这类）
        if (!exactOnly) {
            val letter = optionLetter(rawAnswer)
            if (letter != null) {
                val node = findLetterOption(root, letter)
                if (node != null && clickNode(node)) {
                    cfg.appendLog("[操控] 已点选选项 $letter")
                    return "已点选：选项 $letter"
                }
            }
        }

        cfg.appendLog("[操控] 没找到对应选项（不瞎点）")
        return "没找到对应选项"
    }

    /** 把答案拆成候选词：去 Markdown、去「答案：」前缀、按标点切。 */
    private fun candidates(raw: String): List<String> {
        val a = stripMd(raw)
            .replace(Regex("^(最终)?(正确答案|答案|结论)\\s*[:：]\\s*"), "")
            .replace(Regex("[「」『』“”\"]"), " ")
        return a.split('\n', '，', ',', '。', '；', ';', '：', ':', '、', ' ')
            .map { it.trim().trim('.', '!', '?', '-', '*', '·', '•', '#', ' ') }
            .filter { it.length in 2..20 }
            .distinct()
            .take(12)
    }

    /**
     * 规范化节点文字与目标词，供匹配用：
     * "A. 万里长征人未还" → "万里长征人未还"，"**万里长征人未还**" → "万里长征人未还"。
     */
    private fun normalize(s: String): String {
        var t = s.replace(Regex("[\\s\\u3000]+"), "")
        t = t.replace(Regex("^[（(]?[A-Da-dＡ-Ｄａ-ｄ][)）]?[.、．:：]?"), "")
        t = t.replace(Regex("[.、．,，。;；:：!！?？\"'“”‘’「」『』()（）\\[\\]【】]"), "")
        return t.lowercase()
    }

    private fun isOnScreen(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        runCatching { n.getBoundsInScreen(r) }
        return r.width() > 0 && r.height() > 0
    }

    /** 找匹配度最高的节点。exactOnly 时只接受完全相等（0 分）。 */
    private fun findOptionNode(
        root: AccessibilityNodeInfo, target: String, exactOnly: Boolean
    ): AccessibilityNodeInfo? {
        val t = normalize(target)
        if (t.length < 2) return null

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 50) return
            val raw = n.text?.toString()?.trim().orEmpty()
            if (raw.length in 1..40) {
                val cand = normalize(raw)
                if (cand.isNotEmpty()) {
                    val score = when {
                        cand == t -> 0
                        exactOnly -> -1
                        cand.contains(t) -> 1 + (cand.length - t.length)
                        t.contains(cand) && cand.length >= 2 -> 5 + (t.length - cand.length)
                        else -> -1
                    }
                    if (score in 0 until bestScore && isOnScreen(n)) {
                        best = n
                        bestScore = score
                    }
                }
            }
            for (i in 0 until n.childCount) {
                walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    /** 从答案里抽选项字母（"选 A"、"（C）"、"A."）。 */
    private fun optionLetter(answer: String): String? {
        val a = stripMd(answer)
        Regex("[（(]\\s*([A-Da-d])\\s*[)）]").find(a)?.let { return it.groupValues[1].uppercase() }
        Regex("(?:答案|选|应选|正确选项)[^A-Da-d]{0,4}([A-Da-d])(?![A-Za-z])")
            .find(a)?.let { return it.groupValues[1].uppercase() }
        Regex("(?<![A-Za-z])([A-D])\\s*[.、．)）:：]").find(a)?.let { return it.groupValues[1] }
        return null
    }

    private fun findLetterOption(root: AccessibilityNodeInfo, letter: String): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 50 || best != null) return
            val raw = n.text?.toString()?.trim().orEmpty()
            if (raw.length in 2..40 && isOnScreen(n)) {
                val m = Regex("^\\s*[（(]?\\s*([A-Da-d])\\s*[.、．)）:：]").find(raw)
                if (m != null && m.groupValues[1].equals(letter, ignoreCase = true)) {
                    best = n
                    return
                }
            }
            for (i in 0 until n.childCount) {
                walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    /**
     * 点一个节点：先自己点，再沿父链找可点祖先，最后用手势在中心点戳一下。
     * H5/WebView 的选项经常"文字不可点、父容器才可点"，甚至要真实触摸才响应，
     * 所以手势兜底是必须的（对应 accessibility_service_config 里的 canPerformGestures）。
     */
    private fun clickNode(target: AccessibilityNodeInfo): Boolean {
        // 先摸清这条链是不是在 WebView 里：H5 的选项经常"节点可点、页面不响应"，
        // 对这类内容真实触摸比 performAction 可靠得多。
        val chain = mutableListOf<AccessibilityNodeInfo>()
        var cur: AccessibilityNodeInfo? = target
        var hops = 0
        var inWebView = false
        while (hops < 6 && cur != null) {
            val n = cur
            chain.add(n)
            if (n.className?.toString()?.contains("WebView", ignoreCase = true) == true) inWebView = true
            cur = runCatching { n.parent }.getOrNull()
            hops++
        }

        if (inWebView && tapByGesture(target)) return true

        for (n in chain) {
            if (n.isClickable) {
                val ok = runCatching { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
                if (ok) return true
            }
        }
        return tapByGesture(target)
    }

    private fun tapByGesture(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        runCatching { n.getBoundsInScreen(r) }
        if (r.width() <= 0 || r.height() <= 0) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val ok = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        cfg.appendLog(
            if (ok) "[操控] 手势点击 (${r.centerX()}, ${r.centerY()})"
            else "[操控] 手势注入被系统拒绝"
        )
        return ok
    }

    // ======================= 杂项 =======================

    private fun stripMd(s: String): String = s
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")

    /** 统一刷新"运行指示灯"：光边框、悬浮球、通知、面板广播。 */
    private fun syncIndicators() {
        val on = cfg.autoRead()
        if (on && cfg.globalBorder()) RunningBorderOverlay.show(this) else RunningBorderOverlay.hide()
        if (cfg.bubble() && !AppVisible.foreground) ControlBubble.show(this) else ControlBubble.hide()
        ControlBubble.updateRunning(on)
        broadcastState(on)
        postNotification(if (on) "屏知正在后台识屏，切到题目页会自动作答" else "屏知已暂停，点悬浮球可继续")
    }

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
        instance = null
        cfg.setServiceConnected(false)
        RunningBorderOverlay.hide()
        AnswerOverlay.hide()
        ControlBubble.hide()
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
