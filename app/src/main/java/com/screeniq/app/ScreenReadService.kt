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
import android.graphics.Bitmap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 后台自动识屏核心服务。
 *
 * ── 三条触发通道 ──────────────────────────────────────────────
 *  1. 无障碍事件：切页/滚动/点击 → 250ms 后读一次（等渲染稳定）；
 *  2. 巡检：每 1.2 秒做一次"屏幕变了吗"的低成本判断（像素指纹 + 节点文字），
 *     变了才进完整读屏。WebView / 游戏页不发事件时，全靠它；
 *  3. 稳定重试：读到半截内容，隔 600~1200ms 再读，最多 3 轮。
 *
 * ── 和 v0.3 的关键差别（都是"换一题就瞎"的病根）──
 *  A. 指纹不再是"前 400 个字"。上一版把文本截断到 400 字再做指纹，而 OCR 补的字
 *     是追加在末尾的——换题后前 400 字一模一样，于是判定"内容没变"，永远不再读。
 *     现在用全文哈希 + 像素指纹双保险。
 *  B. 不再因为"节点里已经有短文本"就跳过截图。上一版 nodeUseful=true 就完全不截图，
 *     可这类活动页的题干是画在 Canvas/图片上的，节点里只有"火星/太阳"两个按钮，
 *     结果是：有选项、没题目，AI 只能回 NONE。
 *  C. 巡检不再用 allowOcr=false。那等于"发现了变化却拒绝看图"，必然读不到题干。
 *  D. 截图与视觉调用解耦：截图/识别每轮都可做（便宜），视觉调用才限流（贵）。
 *
 * ── 自动操控 ─────────────────────────────────────────────────
 * 答案一到就自己点。定位顺序：模型给的编号(Set-of-Mark) → 节点精确 → 识别精确
 * → 识别包含 → 节点包含 → 模型坐标。点完会再截一屏确认页面真的变了，
 * 没变会原地补点一次；页面在回答期间已经变了（你手快自己点了）则直接放弃，绝不乱点。
 */
class ScreenReadService : AccessibilityService() {

    private lateinit var cfg: ConfigStore
    private lateinit var ai: AiClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** 最近一次读屏对应的节点树（自动点选时优先用实时树，这个只作兜底） */
    private var pendingRoot: AccessibilityNodeInfo? = null

    private var lastFingerprint = ""
    private var lastNodeHash = ""
    private var lastPixelSig = ""
    private var lastAskAt = 0L
    private var lastAttemptAt = 0L
    private var lastEventAt = 0L
    private var lastClickedFp = ""
    private var lastOcrAt = 0L
    private var busy = false
    private var busySince = 0L
    private var beatBusy = false
    private var heartbeat: Runnable? = null

    /** 最近一次截图识别到的文字行（带图内框）。自动点选靠它点"画上去的选项"。 */
    private var lastOcrLines: List<OcrLine> = emptyList()
    private var lastShotW = 0
    private var lastShotH = 0
    private var lastImageAskAt = 0L

    /** 模型最近一次给出的点击位置（相对坐标）；供浮窗上手动"点选"复用。 */
    private var lastPx = -1f
    private var lastPy = -1f
    private var lastChoice = 0

    /** 出答案那一屏的"选项词集合"，用来判断回答期间题有没有被换掉。 */
    private var lastOptionKeys: Set<String> = emptySet()

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_READ_NOW) {
                resetDedupe()
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

        private const val MIN_ASK_INTERVAL_MS = 700L      // 两次答题最短间隔
        private const val MIN_ATTEMPT_INTERVAL_MS = 800L   // 事件风暴节流
        private const val IMAGE_INTERVAL_MS = 3500L        // 两次"截图问视觉模型"之间至少隔这么久
        private const val FAIL_COOLDOWN_MS = 5000L         // 失败后同一屏冷却
        private const val SETTLE_DELAY_MS = 250L           // 事件后等渲染稳定
        private const val HEARTBEAT_MS = 1200L             // 巡检周期
        private const val MAX_ATTEMPTS = 4
        private const val MIN_TEXT_LEN = 10
        private const val MAX_QUESTION_CHARS = 1200
        private const val AUTO_CLICK_DELAY_MS = 800L       // 出答案后稍等再自动点，让你看清答案
        private const val BUSY_TIMEOUT_MS = 30_000L        // 卡死保护
        private const val TAP_VERIFY_DELAY_MS = 900L

        /**
         * 题库/试卷/活动答题措辞：出现即认为"这屏是题目"，不必等 AI 判。
         * 这一版补了「灯谜/谜语」这一类——拼多多"猜灯谜领奖励"的谜面
         * 既没有 A./B. 标签也没有问号，只有「猜它是什么（打一天体）」。
         */
        private val STRONG_WORDS = listOf(
            "下列", "以下选项", "选择题", "单选题", "多选题", "判断题", "填空题",
            "正确的是", "错误的是", "不正确的是", "补全", "填空", "知识问答",
            "选出", "请选择", "哪一项", "以下说法",
            // 猜字 / 读音 / 看图类
            "读什么", "念什么", "怎么读", "读音", "什么字", "哪个字", "这个字",
            "看看这个", "图中", "下图", "上图", "看图猜", "猜一猜", "是什么成语",
            "答题领", "答对得", "选对得",
            // 灯谜 / 谜语 / 脑筋急转弯
            "灯谜", "猜灯谜", "谜语", "谜底", "打一", "猜它", "猜一", "脑筋急转弯",
            "答对", "答错", "答错了", "再来一题", "下一题",
            // 补充（参考 FloatyAnswer 的宽词表，只取不容易撞车的）
            "拼音", "含义", "意思是", "此字", "该字", "下面哪个", "哪项", "说法正确"
        )

        /**
         * 视觉指代词：题干里的"字/图"不在文字节点里，而是一张图或 Canvas。
         * 命中这些词时**强制走一次视觉模型**，否则文字再多也读不到那个字。
         */
        private val VISUAL_WORDS = listOf(
            "这个字", "什么字", "哪个字", "读什么", "念什么", "怎么读",
            "图中", "下图", "上图", "看图", "图片", "图中所示"
        )

        /**
         * 提问式表述：配合"并排选项按钮"这条结构证据使用。
         * 只有按钮结构、没有任何提问语气（纯商品列表）时不放行，避免误判。
         */
        private val ASK_WORDS = listOf(
            "什么", "哪个", "哪一", "怎么", "多少", "是否", "请问",
            "选一个", "请选", "答案", "猜", "判断"
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
        if (v) resetDedupe()
        cfg.appendLog(if (v) "[开关] 悬浮球：继续自动识屏" else "[开关] 悬浮球：已暂停")
        syncIndicators()
        return v
    }

    /** 悬浮球上的「识屏作答」：立刻读一次当前屏幕，走完整条链路（含自动点选）。 */
    fun readNowManual() {
        resetDedupe()
        readOnce(allowOcr = true, manual = true)
    }

    fun isAutoActionNow(): Boolean = runCatching { cfg.autoAction() }.getOrDefault(false)

    /** 悬浮球上的「自动作答」开关：答案一到就自己点，不用再点浮窗按钮。 */
    fun toggleAutoAction(): Boolean {
        val v = !cfg.autoAction()
        cfg.setAutoAction(v)
        cfg.appendLog(if (v) "[开关] 自动作答：开（答案一到就点）" else "[开关] 自动作答：关（只显示答案）")
        ControlBubble.updateAutoAction(v)
        broadcastState(cfg.autoRead())
        return v
    }

    /** 手动重来时要清掉所有"这一屏已经处理过"的记忆，否则会被去重挡住。 */
    private fun resetDedupe() {
        lastFingerprint = ""
        lastNodeHash = ""
        lastPixelSig = ""
        lastAskAt = 0
        lastAttemptAt = 0
        lastClickedFp = ""
        lastOcrLines = emptyList()
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
     * 巡检：自己截图判断"屏幕变了吗"，变了才进完整读屏。
     *
     * 为什么必须自己截图：这类活动页/小游戏是 WebView 或自绘的，
     * 换题时不一定发无障碍事件；只看节点文字又会因为"题在图片里"而毫无变化。
     * 像素指纹把这两种情况一起解决。
     */
    private fun startHeartbeat() {
        if (heartbeat != null) return
        val r = object : Runnable {
            override fun run() {
                runCatching { heartbeatTick() }
                main.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat = r
        main.postDelayed(r, HEARTBEAT_MS)
    }

    private fun heartbeatTick() {
        val now = System.currentTimeMillis()
        // 卡死保护：网络/系统截图偶发不回调时，不让 busy 永久锁住整条链路
        if (busy && now - busySince > BUSY_TIMEOUT_MS) {
            busy = false
            cfg.appendLog("[服务] 上一轮超时，已强制复位")
        }
        if (!cfg.autoRead() || busy || beatBusy) return
        if (now - lastEventAt < 800) return          // 事件刚发生，交给事件通道
        if (now - lastAttemptAt < MIN_ATTEMPT_INTERVAL_MS) return

        beatBusy = true
        scope.launch {
            var shot: Bitmap? = null
            try {
                if (ScreenshotOcr.supported) {
                    shot = runCatching { ScreenshotOcr.grabScaled(this@ScreenReadService) }.getOrNull()
                }
                val sig = shot?.let { ScreenshotOcr.signature(it, ownOverlayRects(it)) } ?: ""
                val nodeText = runCatching { collectText(rootInActiveWindow) }.getOrDefault("").trim()
                val nh = hash(nodeText)
                if (sig == lastPixelSig && nh == lastNodeHash) {
                    shot?.recycle()
                    return@launch
                }
                lastPixelSig = sig
                lastNodeHash = nh
                if (sig.isEmpty() && nodeText.length < MIN_TEXT_LEN) {
                    shot?.recycle()
                    return@launch
                }
                // 把刚拍的图交下去复用，省一次截图
                readOnce(allowOcr = true, manual = false, preShot = shot)
                shot = null
            } catch (e: Exception) {
                cfg.appendLog("[巡检] ${e.javaClass.simpleName}")
            } finally {
                shot?.let { runCatching { it.recycle() } }
                beatBusy = false
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.let { main.removeCallbacks(it) }
        heartbeat = null
    }

    /**
     * 读一次当前屏幕并尝试作答。
     * @param allowOcr 是否允许截图识别（Android 11 以下自动降级为纯节点）
     * @param manual  用户手动触发（跳过总开关与节流，且不自动重试）
     * @param preShot 调用方已经拍好的截图（巡检用），本方法接管并负责回收
     */
    private fun readOnce(
        allowOcr: Boolean,
        manual: Boolean,
        attempt: Int = 0,
        force: Boolean = false,
        preShot: Bitmap? = null
    ) {
        if (!manual && !cfg.autoRead()) { preShot?.recycle(); return }
        if (busy) { preShot?.recycle(); return }

        val now = System.currentTimeMillis()
        if (!force && !manual) {
            if (now - lastAttemptAt < MIN_ATTEMPT_INTERVAL_MS) { preShot?.recycle(); return }
            if (now - lastAskAt < MIN_ASK_INTERVAL_MS) { preShot?.recycle(); return }
        }
        lastAttemptAt = now

        val root = runCatching { rootInActiveWindow }.getOrNull()
        val nodeText = runCatching { collectText(root) }.getOrDefault("").trim()
        pendingRoot = root

        busy = true
        busySince = now
        scope.launch {
            try {
                val done = withTimeoutOrNull(28_000) {
                    doRead(root, nodeText, allowOcr, manual, attempt, preShot)
                }
                if (done == null) cfg.appendLog("[读屏] 本轮超时，已放弃")
            } catch (e: Exception) {
                cfg.appendLog("[读屏] 异常：${e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    private suspend fun doRead(
        root: AccessibilityNodeInfo?,
        nodeText: String,
        allowOcr: Boolean,
        manual: Boolean,
        attempt: Int,
        preShot: Bitmap?
    ) {
        var text = nodeText
        var shot: Bitmap? = preShot
        var lines: List<OcrLine> = emptyList()
        var sig = ""
        var source = "节点"
        var shotB64: String? = null

        try {
            lastOcrLines = emptyList()

            if (allowOcr && ScreenshotOcr.supported) {
                if (shot == null) {
                    shot = runCatching { ScreenshotOcr.grabScaled(this@ScreenReadService) }.getOrNull()
                }
                val bmp = shot
                if (bmp != null) {
                    lastShotW = bmp.width
                    lastShotH = bmp.height
                    sig = runCatching { ScreenshotOcr.signature(bmp, ownOverlayRects(bmp)) }
                        .getOrDefault("")
                    lastPixelSig = sig
                    lastOcrAt = System.currentTimeMillis()

                    val all = runCatching { ScreenshotOcr.recognizeLines(bmp) }
                        .getOrDefault(emptyList())
                    // 把"我们自己的悬浮窗"读到的字剔掉：否则答案卡上的字会被当成题目内容，
                    // 指纹也会因为它自己刷新而变化，导致同一题被反复追问 AI。
                    lines = dropOwnOverlayLines(all)
                    lastOcrLines = lines
                    cfg.appendLog(
                        if (lines.isEmpty()) "[读图] 截图成功，但没认出文字（${bmp.width}×${bmp.height}）"
                        else "[读图] 认出 ${lines.size} 行" +
                            if (manual) "：" + lines.take(6).joinToString(" / ") { it.text.take(10) }
                            else ""
                    )
                    if (lines.isNotEmpty()) {
                        val merged = mergeText(text, lines.joinToString("\n") { it.text })
                        if (merged.length > text.length) {
                            text = merged
                            source = if (source == "节点") "节点+识别" else source
                        }
                    }
                } else {
                    cfg.appendLog("[读图] 截图失败（安全窗口或系统限制）")
                }
            }
            text = text.trim()

            // --- 文字太少：有截图就继续（题可能全是图），没截图才等渲染 ---
            if (text.length < MIN_TEXT_LEN && shot == null) {
                if (!manual && retryLater(allowOcr, attempt)) return
                if (manual) {
                    cfg.appendLog("[识屏] 没读到文字（节点 ${nodeText.length} 字）")
                    postNotification("这次没读到屏幕文字，换个页面再试")
                }
                return
            }

            // --- 内容没变，跳过（指纹 = 全文哈希 + 识别行数 + 像素签名）---
            val fp = fingerprint(text, lines, sig)
            if (fp == lastFingerprint && !manual) {
                // 页面可能只是动画/光标在动：放慢一点再来看，别每 1.2 秒都做一次识别
                lastAskAt = System.currentTimeMillis() + 800
                return
            }

            // --- 判题：本地强证据先过一遍，避免每屏都花钱问模型 ---
            // 但本地判据认不出的情况必须留一条活路：有些谜面是艺术字，
            // OCR 只认得出下面两个大按钮。这时屏幕上明明有"两个并排短词"，
            // 就值得让模型看一眼——它回 NONE 也不会亏多少，总比整题装死强。
            val gate = looksLikeQuestion(text, root, lines)
            val maybeChoice = hasChoiceStructure(lines) || runCatching {
                countSideBySideOptions(root) >= 1
            }.getOrDefault(false)
            val forceVision = !gate && maybeChoice && ai.canUseVision()

            if (!gate && !forceVision) {
                if (!manual && retryLater(allowOcr, attempt)) return
                if (manual) {
                    cfg.appendLog("[跳过] 读到 ${text.length} 字，判定不是题目：${text.take(60).replace('\n', ' ')}")
                    postNotification("读到 ${text.length} 字，但这不像题目")
                }
                return
            }

            // --- 视觉：只有"题面确实在图里"才花一次视觉调用 ---
            val now = System.currentTimeMillis()
            val visionThrottled = !manual && now - lastImageAskAt < IMAGE_INTERVAL_MS
            if (forceVision && visionThrottled) {
                // 视觉调用刚用过，等冷却过了再来这一屏（别用穷本地判据把它判死）
                if (!manual && retryLater(allowOcr, attempt)) return
            }
            val needVision = ai.canUseVision() && shot != null && !visionThrottled && (
                forceVision ||
                    lines.isEmpty() ||
                    VISUAL_WORDS.any { text.contains(it) } ||
                    !hasOptionLikeLines(lines)
                )
            if (needVision) {
                // Set-of-Mark：编号画在图上，让模型"选编号"而不是估像素位置
                val marked = ScreenshotOcr.annotate(shot!!, lines)
                val b64 = ScreenshotOcr.encodeBase64(marked ?: shot!!)
                marked?.recycle()
                if (!b64.isNullOrBlank()) {
                    shotB64 = b64
                    lastImageAskAt = now
                    source = "$source+看图"
                }
            }

            val question = buildQuestion(text, imageMode = shotB64 != null, numbered = lines)
            cfg.appendLog("[读题·$source] ${text.take(110).replace('\n', ' ')}")

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
                if (cfg.overlay() && Settings.canDrawOverlays(this@ScreenReadService)) {
                    AnswerOverlay.showThinking(this@ScreenReadService)
                }
            }
            postNotification("读到题目，正在问 AI…")
            broadcastState(true)

            var result = runCatching {
                val b64 = shotB64
                if (b64 != null) ai.answerWithImage(question, b64, cfg.explain())
                else ai.answer(question, cfg.explain())
            }.getOrElse { AiResult("", "本地", false, "异常：${it.javaClass.simpleName}") }

            // 降级链（FloatyAnswer 的 VLM→OCR 兜底思路）：
            // 看图这条路挂了（模型不支持/超时/网络），退回纯文字再问一次，不能就此没答案。
            if (!result.ok && shotB64 != null) {
                cfg.appendLog("[降级] 看图失败（${result.note}），改用文字重试")
                result = runCatching { ai.answer(buildQuestion(text), cfg.explain()) }
                    .getOrElse { AiResult("", "本地", false, "异常：${it.javaClass.simpleName}") }
            }

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
                needsImageOnly(answer) -> {
                    cfg.appendLog("[跳过] 这题要看图，当前模型不支持图片输入")
                    postNotification("这题要看图：当前模型不支持，去面板换成 Kimi / 通义")
                    lastFingerprint = fp
                    lastAskAt = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                    AnswerOverlay.hide()
                }
                else -> {
                    cfg.appendLog("[答案] ${stripMd(answer).take(120).replace('\n', ' ')}")
                    if (result.choice > 0) cfg.appendLog("[答案] 模型指向编号 ${result.choice}")
                    lastFingerprint = fp
                    lastAskAt = System.currentTimeMillis()
                    lastPx = result.px
                    lastPy = result.py
                    lastChoice = result.choice
                    lastOptionKeys = runCatching { optionKeys(root) }.getOrDefault(emptySet())
                    withContext(Dispatchers.Main) {
                        showAnswer(answer)
                        scheduleAutoClick(answer, fp, result.choice, result.px, result.py)
                    }
                }
            }
        } finally {
            runCatching { shot?.recycle() }
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
            AnswerOverlay.show(this, clean, cfg.autoAction()) { quickTap(answer) }
        } else {
            postNotification("答案：${clean.take(120)}")
        }
    }

    // ======================= 自动操控 =======================

    /**
     * 自动操控的主体：开关打开 → 延迟 0.8 秒（让你先看到答案）→ 自动点。
     * 点之前先确认"题还没被换掉"，点之后再截一屏确认"页面真的变了"。
     */
    private fun scheduleAutoClick(
        answer: String, fp: String, choice: Int, px: Float, py: Float
    ) {
        if (!cfg.autoAction()) return
        if (fp == lastClickedFp) return
        cfg.appendLog("[操控] 自动作答已开，准备点选…")
        main.postDelayed({
            scope.launch {
                val msg = runCatching { performAct(answer, choice, px, py) }
                    .getOrElse { "点选异常：${it.javaClass.simpleName}" }
                if (msg.startsWith("已点选")) {
                    lastClickedFp = fp
                    // 点完页面通常有奖励动画，别在这 1.2 秒里把同一题再问一遍
                    lastAskAt = System.currentTimeMillis() + 1200
                } else {
                    lastAskAt = System.currentTimeMillis() + 2500
                }
                withContext(Dispatchers.Main) { AnswerOverlay.noteFeedback(msg) }
            }
        }, AUTO_CLICK_DELAY_MS)
    }

    /**
     * 真正执行"点一下"的那一段（suspend，因为要截图确认）。
     *
     * 这里有一条很重要的安全线：**回答期间页面变了就不点**。
     * 你手快自己点了下一题、或者活动页自动翻页时，旧答案对应的是旧屏幕，
     * 照着旧坐标戳下去极可能点错选项——宁可这次不点，也不能乱点。
     */
    private suspend fun performAct(
        answer: String, choice: Int, px: Float, py: Float
    ): String {
        if (!cfg.autoAction()) return "自动作答未开"
        // Android 11 以下没有无障碍截图接口，也就没法做"点完再确认"
        if (!ScreenshotOcr.supported) return "系统版本过低（自动点选需 Android 11+）"

        // 安全线：问 AI 的这几秒里页面已经换了（你手快自己点了下一题），就别照着旧答案点。
        // 用节点文字比，不用像素比 —— 我们自己的悬浮窗是独立窗口，不进节点树，天然不干扰。
        if (pageChangedSinceAnswer()) {
            cfg.appendLog("[操控] 页面在作答期间已切换，本次放弃（避免点错）")
            return "页面已变，本次不自动点"
        }

        val target = resolveTarget(answer, choice, px, py)
            ?: return "没找到「${stripMd(answer).take(12)}」可点的位置"

        // 目标被答案窗挡住 → 先收卡；收完仍挡着（正好压在收起后的小胶囊上）就临时撤掉
        var card = AnswerOverlay.cardBounds()
        if (card != null && card.contains(target.x.toInt(), target.y.toInt())) {
            cfg.appendLog("[操控] 目标被答案窗遮住，先让开")
            withContext(Dispatchers.Main) { AnswerOverlay.collapseNow() }
            delay(300)
            card = AnswerOverlay.cardBounds()
            if (card != null && card.contains(target.x.toInt(), target.y.toInt())) {
                withContext(Dispatchers.Main) { AnswerOverlay.hide() }
                delay(280)
            }
        }

        val before = runCatching { ScreenshotOcr.grabScaled(this@ScreenReadService) }.getOrNull()
        val sigBefore = before?.let { ScreenshotOcr.signature(it, ownOverlayRects(it)) } ?: ""
        before?.recycle()

        val ok = tapTarget(target)
        if (!ok) {
            cfg.appendLog("[操控] 点击注入失败：${target.label}")
            return "点选失败（系统拒绝了手势）"
        }
        cfg.appendLog("[操控] 已点选：${target.label} @ (${target.x.toInt()}, ${target.y.toInt()})")

        delay(TAP_VERIFY_DELAY_MS)
        var changed = screenChanged(sigBefore)

        // 没变化就原地补一次：**同一个点**再戳一下，不会误点到别的选项。
        // 这里强制走手势——有些 H5 控件 performAction 会返回 true 但其实什么都没发生。
        if (!changed) {
            cfg.appendLog("[操控] 页面没变化，改用真实触摸原地补点")
            tapAt(target.x, target.y)
            delay(700)
            changed = screenChanged(sigBefore)
        }

        return if (changed) "已点选：${target.label}"
        else "已点选：${target.label}（页面没变化，可能只需选中后手动提交）"
    }

    /** 截一屏和基准比，判断页面到底动没动。 */
    private suspend fun screenChanged(sigBefore: String): Boolean {
        if (!ScreenshotOcr.supported) return true
        if (sigBefore.isEmpty()) return true   // 没有基准就不敢断言失败
        val bmp = runCatching { ScreenshotOcr.grabScaled(this@ScreenReadService) }.getOrNull()
            ?: return true
        val sig = runCatching { ScreenshotOcr.signature(bmp, ownOverlayRects(bmp)) }.getOrDefault("")
        bmp.recycle()
        return sig.isNotEmpty() && sig != sigBefore
    }

    /**
     * 出答案之后页面有没有被换成新题。
     *
     * 比的是"屏幕上的选项词集合"，不是整页文字——活动页往往有
     * 「已获得 3 朵花」这种会自己跳的计数，比整页文字会天天误判。
     * 只有两边都至少有 2 个选项、且一个新选项都对不上时，才认定换题。
     * 纯自绘页面没有节点选项，此时一律放行（不拦着点选）。
     */
    private fun pageChangedSinceAnswer(): Boolean {
        val before = lastOptionKeys
        if (before.size < 2) return false
        val now = optionKeys(runCatching { rootInActiveWindow }.getOrNull())
        if (now.size < 2) return false
        return now.intersect(before).isEmpty()
    }

    /** 屏幕上"像选项"的短词集合（规范化后）。 */
    private fun optionKeys(root: AccessibilityNodeInfo?): Set<String> {
        val out = HashSet<String>()
        for (l in collectShortLabels(root)) {
            val k = normalize(l.text)
            if (k.length in 1..8) out.add(k)
        }
        return out
    }

    /** 浮窗上「自动点选此答案」按钮走这条：同步点一下，把结论回显到标题。 */
    private fun quickTap(answer: String): String {
        val target = resolveTarget(answer, lastChoice, lastPx, lastPy)
            ?: return "没找到可点的位置"
        val ok = tapTarget(target)
        return if (ok) "已点选：${target.label}" else "点选失败"
    }

    /** 一个"该点哪里"的结论。x、y 一律是**屏幕坐标**，调用方不用再换算。 */
    private class TapTarget(
        val label: String,
        val x: Float,
        val y: Float,
        val node: AccessibilityNodeInfo? = null
    )

    /**
     * 定位顺序，从准到糙：
     *  1. 模型给的编号（Set-of-Mark，最准：直接落到识别框中心）
     *  2. 无障碍节点精确匹配（原生控件，performAction 最可靠）
     *  3. 识别文字精确匹配（画上去的选项）
     *  4. 识别文字包含匹配
     *  5. 节点包含匹配
     *  6. 模型给的相对坐标（最后兜底）
     */
    private fun resolveTarget(answer: String, choice: Int, px: Float, py: Float): TapTarget? {
        val cands = candidates(answer)

        // 1. 编号
        if (choice in 1..lastOcrLines.size) {
            val line = lastOcrLines[choice - 1]
            val pt = imageToScreen(line.cx, line.cy) ?: return null
            cfg.appendLog("[操控] 按编号 $choice →「${line.text}」")
            return TapTarget(line.text, pt.first, pt.second)
        }

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: pendingRoot

        // 2. 节点精确 / 3. 识别精确
        for (c in cands) {
            if (root != null) {
                findOptionNode(root, c, exactOnly = true)?.let { n ->
                    val r = Rect()
                    if (runCatching { n.getBoundsInScreen(r) }.isSuccess && r.width() > 0) {
                        return TapTarget(c, r.exactCenterX(), r.exactCenterY(), n)
                    }
                }
            }
            findOcrLine(c, exactOnly = true)?.let { line ->
                val pt = imageToScreen(line.cx, line.cy) ?: return@let
                return TapTarget(line.text, pt.first, pt.second)
            }
        }

        // 4. 识别包含 / 5. 节点包含（只在自动作答开启时才会走到这里）
        for (c in cands) {
            findOcrLine(c, exactOnly = false)?.let { line ->
                val pt = imageToScreen(line.cx, line.cy) ?: return@let
                return TapTarget(line.text, pt.first, pt.second)
            }
            if (root != null) {
                findOptionNode(root, c, exactOnly = false)?.let { n ->
                    val r = Rect()
                    if (runCatching { n.getBoundsInScreen(r) }.isSuccess && r.width() > 0) {
                        return TapTarget(c, r.exactCenterX(), r.exactCenterY(), n)
                    }
                }
            }
        }

        // 6. 字母兜底（"选 B" 这类），节点与识别两条路都试
        optionLetter(answer)?.let { letter ->
            if (root != null) {
                findLetterOption(root, letter)?.let { n ->
                    val r = Rect()
                    if (runCatching { n.getBoundsInScreen(r) }.isSuccess && r.width() > 0) {
                        return TapTarget("选项 $letter", r.exactCenterX(), r.exactCenterY(), n)
                    }
                }
            }
            findOcrLetter(letter)?.let { line ->
                val pt = imageToScreen(line.cx, line.cy) ?: return@let
                return TapTarget(line.text, pt.first, pt.second)
            }
        }

        // 7. 模型坐标
        if (px in 0f..1f && py in 0f..1f) {
            val dm = resources.displayMetrics
            return TapTarget("模型坐标", px * dm.widthPixels, py * dm.heightPixels)
        }
        return null
    }

    private fun tapTarget(t: TapTarget): Boolean =
        if (t.node != null) clickNode(t.node) else tapAt(t.x, t.y)

    /** 识别图坐标 → 屏幕坐标。截图是缩过的，必须按比例还原。 */
    private fun imageToScreen(cxImg: Float, cyImg: Float): Pair<Float, Float>? {
        if (lastShotW <= 0 || lastShotH <= 0) return null
        val dm = resources.displayMetrics
        return Pair(cxImg * dm.widthPixels / lastShotW, cyImg * dm.heightPixels / lastShotH)
    }

    /** 在最近一次识别结果里找答案对应的那一行。 */
    private fun findOcrLine(target: String, exactOnly: Boolean): OcrLine? {
        if (lastOcrLines.isEmpty()) return null
        val t = normalize(target)
        if (t.length < 2) return null
        var best: OcrLine? = null
        var bestScore = Int.MAX_VALUE
        for (line in lastOcrLines) {
            val u = normalize(line.text)
            if (u.isEmpty()) continue
            val score = when {
                u == t -> 0
                exactOnly -> -1
                u.contains(t) -> 1 + (u.length - t.length)
                t.contains(u) && u.length >= 2 -> 5 + (t.length - u.length)
                else -> -1
            }
            if (score in 0 until bestScore) {
                bestScore = score
                best = line
            }
        }
        return best
    }

    private fun findOcrLetter(letter: String): OcrLine? {
        for (line in lastOcrLines) {
            val m = Regex("^\\s*[（(]?\\s*([A-Da-d])\\s*[.、．)）:：]").find(line.text) ?: continue
            if (m.groupValues[1].equals(letter, ignoreCase = true)) return line
        }
        return null
    }

    /** 把答案拆成候选词：去 Markdown、去「答案：」前缀、按标点切。 */
    private fun candidates(raw: String): List<String> {
        val a = stripMd(raw)
            .replace(Regex("^(最终)?(正确答案|答案|结论)\\s*[:：]\\s*"), "")
            .replace(Regex("[「」『』“”\"]"), " ")
        return a.split('\n', '，', ',', '。', '；', ';', '：', ':', '、', ' ', '（', '）', '(', ')')
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
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    /** 统一的"手指送到 (x,y)"：挡住自己的窗口就让开，底部手势区不碰。 */
    private fun tapAt(x: Float, y: Float): Boolean {
        val dm = resources.displayMetrics
        if (x < 2f || y < 2f || x > dm.widthPixels - 2f) return false
        // 底部手势条区域不碰，否则会变成"返回桌面"
        if (y > dm.heightPixels * 0.955f) {
            cfg.appendLog("[操控] 目标在底部手势区，跳过")
            return false
        }
        val bubble = ControlBubble.bounds()
        if (bubble != null && bubble.contains(x.toInt(), y.toInt())) {
            cfg.appendLog("[操控] 目标被悬浮球挡住，请把它拖开再试")
            return false
        }
        val card = AnswerOverlay.cardBounds()
        if (card != null && card.contains(x.toInt(), y.toInt())) {
            cfg.appendLog("[操控] 目标被答案窗挡住，跳过")
            return false
        }
        return doGesture(x, y)
    }

    private fun doGesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L))
            .build()
        val ok = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        cfg.appendLog(
            if (ok) "[操控] 手势点击 (${x.toInt()}, ${y.toInt()})"
            else "[操控] 手势注入被系统拒绝"
        )
        return ok
    }

    // ======================= 取字与判题 =======================

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

    /** 屏幕上一个短文本（候选选项）的文字 + 位置。 */
    private class ShortLabel(val text: String, val rect: Rect)

    private fun collectShortLabels(
        node: AccessibilityNodeInfo?, depth: Int = 0, out: MutableList<ShortLabel> = mutableListOf()
    ): List<ShortLabel> {
        if (node == null || depth > 40) return out
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.length in 1..10 && !node.isEditable) {
            val r = Rect()
            if (runCatching { node.getBoundsInScreen(r) }.isSuccess && r.width() > 0) {
                out.add(ShortLabel(t, r))
            }
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectShortLabels(child, depth + 1, out)
        }
        return out
    }

    /**
     * 数"并排的短文本对"——比标点更可靠的结构证据。
     * 只有同时满足：中心高度接近、高度接近、水平相邻且间隙不大、自身够大（是按钮不是小标签）。
     */
    private fun countSideBySideOptions(root: AccessibilityNodeInfo?): Int {
        val labels = collectShortLabels(root)
        if (labels.size < 2) return 0
        val dm = resources.displayMetrics
        val tolY = (28 * dm.density).toInt()
        val tolH = (20 * dm.density).toInt()
        val minH = (26 * dm.density).toInt()
        val minW = (34 * dm.density).toInt()
        val maxGap = (dm.widthPixels * 0.42f).toInt()
        var pairs = 0
        for (i in labels.indices) {
            val a = labels[i]
            if (a.rect.height() < minH || a.rect.width() < minW) continue
            for (j in i + 1 until labels.size) {
                val b = labels[j]
                if (b.rect.height() < minH || b.rect.width() < minW) continue
                if (abs(a.rect.centerY() - b.rect.centerY()) > tolY) continue
                if (abs(a.rect.height() - b.rect.height()) > tolH) continue
                val gap = when {
                    a.rect.right <= b.rect.left -> b.rect.left - a.rect.right
                    b.rect.right <= a.rect.left -> a.rect.left - b.rect.right
                    else -> -1
                }
                if (gap < 0 || gap > maxGap) continue
                pairs++
            }
        }
        return pairs
    }

    /** 指纹：全文哈希 + 识别行数 + 像素签名。任何一项变了就算新屏。 */
    private fun fingerprint(text: String, lines: List<OcrLine>, sig: String): String {
        val norm = text.replace(Regex("[\\s\\u3000]+"), " ").trim()
        return "${norm.length}:${hash(norm)}:${lines.size}:${sig.take(16)}"
    }

    private fun hash(s: String): String = Integer.toHexString(s.hashCode())

    /**
     * 判定"这屏是不是一道题"。只有决定性证据才放行：
     *  决定性：≥2 个选项标签 / 有填空横线 / 题型措辞（含谜语）
     *          / 识别出至少 2 个并排短行且带提问语气
     *  弱证据：单独的问号 —— 必须同时至少有一个选项标签
     *  否决：只有弱证据且购物类特征词 ≥3 个
     */
    private fun looksLikeQuestion(
        text: String, root: AccessibilityNodeInfo?, lines: List<OcrLine> = emptyList()
    ): Boolean {
        if (text.length < MIN_TEXT_LEN) return false

        val optionLabels = countOptionLabels(text)
        val hasBlank = Regex("[_＿]{2,}|（\\s{0,3}）|\\(\\s{0,3}\\)").containsMatchIn(text)
        val hasQMark = text.contains('？') || text.contains('?')
        val hasStrongWord = STRONG_WORDS.any { text.contains(it) }

        // 结构证据：屏幕上真有两个并排的按钮式短文本（节点没结构时用识别结果）
        val sideBySide = when {
            hasStrongWord || optionLabels >= 2 -> 0
            else -> runCatching { countSideBySideOptions(root) }.getOrDefault(0)
                .coerceAtLeast(if (hasChoiceStructure(lines)) 1 else 0)
        }
        val askPhrase = ASK_WORDS.any { text.contains(it) }
        val structural = sideBySide >= 1 && (askPhrase || hasQMark ||
                text.contains("什么") || text.contains("哪个"))

        val decisive = optionLabels >= 2 || hasBlank || hasStrongWord || structural
        if (!decisive) return hasQMark && optionLabels >= 1
        if (optionLabels < 2 && !hasBlank && !hasStrongWord) {
            val noise = NOISE_WORDS.count { text.contains(it) }
            if (noise >= 3) return false
        }
        return true
    }

    /**
     * 统计"像选项的行"数量：以 A./B、/（C）/D： 开头的短行。
     * 这是最可靠的题型证据，也是收紧判定的核心——上一版用"短文本 ≥2"，
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

    /** 识别结果里有没有"两三个并排的短词"——活动页的选项按钮就长这样。 */
    private fun hasChoiceStructure(lines: List<OcrLine>): Boolean {
        var n = 0
        for (a in lines) {
            val t = a.text.trim()
            if (t.length !in 1..8) continue
            for (b in lines) {
                if (a === b) continue
                val u = b.text.trim()
                if (u.length !in 1..8) continue
                if (abs(a.cy - b.cy) < a.box.height() * 0.6f) n++
            }
        }
        return n >= 2
    }

    private fun hasOptionLikeLines(lines: List<OcrLine>): Boolean {
        if (hasChoiceStructure(lines)) return true
        var labeled = 0
        for (l in lines) {
            if (Regex("^\\s*[（(]?\\s*[A-DＡ-Ｄ][\\s.、．)）:：]").containsMatchIn(l.text)) labeled++
        }
        return labeled >= 2
    }

    private fun isNotAQuestion(answer: String): Boolean {
        val a = answer.trim()
        if (a == "NONE" || a.startsWith("NONE")) return true
        return a.length <= 8 && (a.contains("不是题目") || a.contains("无法判断") || a.contains("信息不足"))
    }

    /** 模型明确表示"这题要图才能答"——用于提示换视觉模型，而不是当成答不出。 */
    private fun needsImageOnly(answer: String): Boolean {
        val a = answer.trim()
        if (a.length > 14) return false
        return a.contains("需要看图") || a.contains("缺少图片") || a.contains("看不到图")
    }

    /**
     * 把节点文字和 OCR 结果合并：节点行先入，OCR 只补节点里没有的行。
     * 节点文字截到 700 字——活动页的"收货地址/助力记录"能堆出一大堆，
     * 不截断会把真正的谜面挤出上下文。
     */
    private fun mergeText(node: String, ocr: String): String {
        val head = if (node.length > 700) node.take(700) else node
        if (head.isBlank()) return ocr
        if (ocr.isBlank()) return head
        val have = head.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        val extra = ocr.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 40 && it !in have }
            .distinct()
            .take(28)
            .toList()
        if (extra.isEmpty()) return head
        val sb = StringBuilder(head)
        for (line in extra) {
            if (sb.length + line.length > MAX_QUESTION_CHARS) break
            sb.append('\n').append(line)
        }
        return sb.toString()
    }

    private fun buildQuestion(
        screen: String,
        imageMode: Boolean = false,
        numbered: List<OcrLine> = emptyList()
    ): String {
        val cleaned = screen.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc.add(s); acc }
            .joinToString("\n")
            .take(MAX_QUESTION_CHARS)

        val listPart = if (imageMode && numbered.isNotEmpty()) {
            "\n截图上的文字按编号如下（编号 = 图上的小圆牌）：\n" +
                numbered.take(24).mapIndexed { i, l -> "${i + 1}. ${l.text.take(40)}" }
                    .joinToString("\n").take(800) + "\n"
        } else ""

        val extraRule = when {
            imageMode && numbered.isEmpty() ->
                "4. 这道题的关键内容在截图里（题干或选项是画上去的），请直接看图作答，" +
                    "不要回答「信息不足」。\n" +
                    "5. 截图这次没有编号可用。如果答案对应屏幕上某个可点的按钮/选项，" +
                    "请在最后单独一行写 POINT:x,y，x、y 是 0~1 的小数（左上角 0,0，右下角 1,1）。\n"
            imageMode ->
                "4. 这道题的关键内容可能在截图里（题干或选项是画上去的），请直接看图作答，" +
                    "不要回答「信息不足」。\n" +
                    "5. 如果答案就是上面列表里的某一项，最后单独一行写 CHOICE:编号（只写数字）。" +
                    "编号不要写进答案正文。\n"
            else ->
                "4. 如果是看图猜字、认字读音类题目而文字里没有那个字，就直接回复：需要看图\n"
        }

        val body = if (cleaned.isBlank() && imageMode)
            "（这一屏几乎没有文字，题目和选项都画在图上，请看截图）" else cleaned

        return "下面是我手机当前屏幕上的文字，可能夹着 App 的标题、按钮、规则、广告等无关内容。\n" +
                "请你这样处理：\n" +
                "1. 先判断其中有没有一道可以作答的题目。没有就只回复一个词：NONE\n" +
                "2. 有题目就直接给答案。选项题请给出选项的文字（例如「杭州」），不要只给字母。" +
                "灯谜、谜语、脑筋急转弯的谜底通常就是其中一个选项，请直接写出那个选项的文字。\n" +
                "3. 如果题目信息不足、无法确定答案，回复：信息不足\n" +
                extraRule +
                listPart +
                "屏幕文字如下：\n" +
                "------\n$body\n------"
    }

    // ======================= 自家悬浮窗排除 =======================

    /**
     * 把落在我方悬浮窗里的识别行剔掉。
     * 不剔会出三个问题：答案卡上的字被当成题目内容、指纹因为它自己刷新而一直变、
     * 自动点选可能去点"答案卡上的字"。
     */
    private fun dropOwnOverlayLines(lines: List<OcrLine>): List<OcrLine> {
        if (lines.isEmpty()) return lines
        val dm = resources.displayMetrics
        val sx = if (lastShotW > 0) dm.widthPixels.toFloat() / lastShotW else 1f
        val sy = if (lastShotH > 0) dm.heightPixels.toFloat() / lastShotH else 1f
        val card = AnswerOverlay.cardBounds()
        val bubble = ControlBubble.bounds()
        return lines.filter { l ->
            if (l.text.contains("屏知")) return@filter false
            val x = l.cx * sx
            val y = l.cy * sy
            val inCard = card != null && card.contains(x.toInt(), y.toInt())
            val inBubble = bubble != null && bubble.contains(x.toInt(), y.toInt())
            !inCard && !inBubble
        }
    }

    /** 我方悬浮窗在**截图坐标系**里的矩形，给像素指纹排除用。 */
    private fun ownOverlayRects(shot: Bitmap): List<Rect> {
        val dm = resources.displayMetrics
        val sx = shot.width.toFloat() / dm.widthPixels
        val sy = shot.height.toFloat() / dm.heightPixels
        val out = ArrayList<Rect>(3)
        AnswerOverlay.cardBounds()?.let { out.add(scaleRect(it, sx, sy)) }
        ControlBubble.bounds()?.let { out.add(scaleRect(it, sx, sy)) }
        return out
    }

    private fun scaleRect(r: Rect, sx: Float, sy: Float): Rect = Rect(
        (r.left * sx).toInt(), (r.top * sy).toInt(),
        (r.right * sx).toInt(), (r.bottom * sy).toInt()
    )

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
        ControlBubble.updateAutoAction(cfg.autoAction())
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
