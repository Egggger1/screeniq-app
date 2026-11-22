package com.screeniq.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后台自动识屏核心服务。
 *
 * 工作流程：屏幕内容变化 → 读文字节点 → 去抖 → 判定像不像题目 →
 *          送 AI 答题 → 悬浮窗就地显示答案 →（可选）自动点击选项。
 *
 * 合规设计（个人备考自测定位）：
 * - 只在用户开启「自动识屏」后才真正读屏答题；
 * - 自动点击选项/填答案默认关闭，需用户单独显式开启「自动操控」；
 * - 程序不判断使用场景，是否启用完全由使用者决定；
 * - 屏幕内容只在内存处理，不上传、不落盘，日志只记摘要。
 */
class ScreenReadService : AccessibilityService() {

    private lateinit var cfg: ConfigStore
    private lateinit var ai: AiClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private var lastFingerprint = ""
    private var lastFireAt = 0L
    private var running = false

    companion object {
        const val ACTION_STATE = "com.screeniq.READ_STATE"
        const val EXTRA_RUNNING = "running"
        private const val CHANNEL_ID = "screeniq_read_channel"
        private const val MIN_INTERVAL_MS = 4000L      // 同题去重 + 最短答题间隔
        private const val MIN_TEXT_LEN = 8
    }

    override fun onCreate() {
        super.onCreate()
        cfg = ConfigStore(this)
        ai = AiClient(cfg)
        createChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 部分 ROM 连接后会把 flag 重置，这里重新确保能读到文字
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        cfg.setServiceConnected(true)
        cfg.appendLog("[服务] 无障碍读屏服务已连接")
        postNotification()
        broadcastState(true)
        main.post { android.widget.Toast.makeText(this, "屏知读屏服务已连接", android.widget.Toast.LENGTH_SHORT).show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 总开关：没开就什么都不做
        if (!cfg.autoRead()) return
        // 不读自己 App 的界面，避免自我触发
        if (event.packageName?.toString() == packageName) return

        val root = rootInActiveWindow ?: return
        val text = collectText(root).trim()
        if (text.length < MIN_TEXT_LEN) return

        // 去抖：内容指纹一样、且距上次答题太近，就跳过
        val fingerprint = text.take(400)
        val now = System.currentTimeMillis()
        if (fingerprint == lastFingerprint && now - lastFireAt < MIN_INTERVAL_MS) return
        if (now - lastFireAt < MIN_INTERVAL_MS) return

        if (!looksLikeQuestion(text)) return

        lastFingerprint = fingerprint
        lastFireAt = now
        running = true
        broadcastState(true)

        val preview = text.take(140).replace('\n', ' ')
        cfg.appendLog("[读屏] $preview")
        postNotification("读到题目：$preview")

        scope.launch {
            val result = ai.answer(text, cfg.explain())
            val answer = result.answer.ifBlank { result.note.ifBlank { "（没读到题目文字）" } }
            cfg.appendLog("[答案] ${answer.take(160).replace('\n', ' ')}")
            main.post {
                if (cfg.overlay()) {
                    AnswerOverlay.show(
                        context = this@ScreenReadService,
                        answer = answer,
                        explain = cfg.explain(),
                        onAutoClick = { if (cfg.autoAction()) autoClickAnswer(root, answer) }
                    )
                } else {
                    postNotification("答案：${answer.take(120)}")
                }
            }
        }
    }

    /** 遍历当前窗口，收集所有可见文字节点，用换行拼起来。只读 getText，绝不写。 */
    private fun collectText(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null || depth > 30) return ""
        val sb = StringBuilder()
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) sb.append(t).append('\n')
        // 内容描述也收一下，有些自定义控件文字放在 contentDescription
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != t) sb.append(desc).append('\n')
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(collectText(child, depth + 1))
            child.recycle()
        }
        return sb.toString()
    }

    /**
     * 判断读到的文字"像不像题目"——宁可漏判也别老乱弹。
     * 命中问号、选项标记、常见题干词就算题目。
     */
    private fun looksLikeQuestion(text: String): Boolean {
        if (text.length < MIN_TEXT_LEN) return false
        val marks = listOf("？", "?", "（ ）", "( )", "下列", "以下", "正确", "错误", "选项", "求", "计算", "多少", "哪项", "判断", "填空", "A.", "B.", "C.", "D.", "A、", "B、")
        return marks.count { text.contains(it) } >= 1
    }

    /**
     * 自动操控：从答案里提取选项字母（A/B/C/D），在当前界面找可点击的选项节点并点它。
     * 只有「自动操控」开关打开时才会被调用；默认关闭。
     * 找不到就返回 false，不瞎点。
     */
    private fun autoClickAnswer(root: AccessibilityNodeInfo, answer: String) {
        val letter = Regex("\\b([ABCDabcd])[.、．）)]").find(answer)?.groupValues?.get(1)?.uppercase()
            ?: Regex("(?:选?答案?[为是:：]?\\s*)([ABCDabcd])\\b").find(answer)?.groupValues?.get(1)?.uppercase()
            ?: return
        val target = findClickableByText(root, letter)
        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cfg.appendLog("[操控] 已自动点击选项 $letter")
        } else {
            cfg.appendLog("[操控] 未找到选项 $letter 的可点击节点，跳过")
        }
    }

    private fun findClickableByText(node: AccessibilityNodeInfo?, letter: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        val t = node.text?.toString()?.trim() ?: ""
        if (node.isClickable && (t.startsWith("$letter.") || t.startsWith("$letter、") || t.startsWith("$letter ") || t == letter)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableByText(child, letter, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun broadcastState(isRunning: Boolean) {
        sendBroadcast(Intent(ACTION_STATE).putExtra(EXTRA_RUNNING, isRunning).setPackage(packageName))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(text: String = "屏知正在后台识屏，点击打开") {
        val intent = Intent(this, ControlActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("屏知 ScreenIQ")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1001, notification)
    }

    override fun onInterrupt() {
        running = false
        cfg.setServiceConnected(false)
        broadcastState(false)
    }

    override fun onDestroy() {
        running = false
        cfg.setServiceConnected(false)
        broadcastState(false)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1001)
        AnswerOverlay.hide()
        super.onDestroy()
    }
}
