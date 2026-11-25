package com.screeniq.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * YOYO 唤出实验（v0.7.0-lab 专用，不进正式版主流程）。
 *
 * 目的：验证"用系统自带 AI 代替云 API"这条路在真机上到底通不通。
 * 一次实验把三关的成败全部记录下来：
 *   ① 唤出：长按小白条手势 / ACTION_ASSIST 意图，两条都试；
 *   ② 输入：找输入框写问题 + 找发送按钮点下去；
 *   ③ 读回：连续截图 OCR，等文字稳定后取 YOYO 的回答。
 *
 * 这一版**只诊断、不自动点选、不调云 API**。看到结果再决定要不要往下走。
 *
 * 安全边界：
 *  - 只用无障碍的公开手势/节点写入接口，不碰系统私有 API、不 root；
 *  - 只写我们自己的问题文本，绝不读取/上报用户其他内容；
 *  - 唤出手势只在屏幕底部中央一小段，超时即停，不会卡在系统导航里。
 */
object YoyoLab {

    /** 实验的逐步记录，既显示在卡片上，也写进日志。 */
    data class Trace(
        val title: String,
        val wakeGesture: String = "",
        val wakeAssist: String = "",
        val input: String = "",
        val send: String = "",
        val read: String = "",
        val answer: String = "",
        val finishedAt: Long = System.currentTimeMillis()
    ) {
        fun summary(): String = buildString {
            append(title).append('\n')
            if (wakeGesture.isNotEmpty()) append(wakeGesture).append('\n')
            if (wakeAssist.isNotEmpty()) append(wakeAssist).append('\n')
            if (input.isNotEmpty()) append(input).append('\n')
            if (send.isNotEmpty()) append(send).append('\n')
            if (read.isNotEmpty()) append(read).append('\n')
            if (answer.isNotEmpty()) {
                append("\n──── YOYO 的回答 ────\n").append(answer)
            }
        }
    }

    private const val QUESTION = "请直接回答：图中这道题选哪个选项？只回答选项字母，比如 A 或 B。"

    /**
     * 跑一次完整实验。必须在无障碍服务里调用（要用到 dispatchGesture / takeScreenshot / 节点写入）。
     * 结果通过 [onTrace] 回传，调用方负责显示到答案卡。
     */
    suspend fun run(
        service: AccessibilityService,
        cfg: ConfigStore,
        onTrace: (Trace) -> Unit
    ) {
        var t = Trace("◈ YOYO 实验 · 开始")
        onTrace(t)
        cfg.appendLog("[YOYO实验] 开始")

        // ---------- 第 0 步：记下唤起前的屏幕指纹，用于判断唤出有没有生效 ----------
        val before = if (ScreenshotOcr.supported) {
            ScreenshotOcr.grabScaled(service)?.let { bmp ->
                val sig = ScreenshotOcr.signature(bmp)
                val lines = ScreenshotOcr.recognizeLines(bmp).joinToString(" ") { it.text }
                bmp.recycle()
                Pair(sig, lines)
            }
        } else null

        // ---------- 第 1 步 A：模拟长按小白条 ----------
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        // 小白条在最底部；留 8px 余量，落在导航手势区里
        val bottomY = dm.heightPixels - 8f
        val gestureOk = longPress(service, cx, bottomY)
        t = t.copy(wakeGesture = "① 长按小白条手势：${if (gestureOk) "已注入" else "被系统拒绝"}")
        onTrace(t)

        delay(1400)

        // ---------- 判断唤出有没有生效（截图变化 or 出现 YOYO 字样）----------
        var awake = false
        if (before != null && ScreenshotOcr.supported) {
            val after = ScreenshotOcr.grabScaled(service)?.let { bmp ->
                val sig = ScreenshotOcr.signature(bmp)
                val lines = ScreenshotOcr.recognizeLines(bmp).joinToString(" ") { it.text }
                bmp.recycle()
                Pair(sig, lines)
            }
            if (after != null) {
                val changed = after.first != before.first
                val hasYoyo = after.second.contains("YOYO", ignoreCase = true) ||
                              after.second.contains("悠悠") ||
                              after.second.contains("问我")
                awake = changed || hasYoyo
                t = t.copy(wakeGesture = t.wakeGesture +
                    " · 屏幕${if (changed) "变了" else "没变"}${if (hasYoyo) " · 见到YOYO字样" else ""}")
                onTrace(t)
            }
        }

        // ---------- 第 1 步 B：ACTION_ASSIST 意图（长按如果没唤出，补这一路）----------
        if (!awake) {
            val assistOk = fireAssist(service)
            t = t.copy(wakeAssist = "② 系统助手意图：${if (assistOk) "已发出" else "无应用响应"}")
            onTrace(t)
            delay(1800)
        } else {
            t = t.copy(wakeAssist = "② 系统助手意图：跳过（长按已生效）")
            onTrace(t)
        }

        // ---------- 第 2 步：找输入框写问题 ----------
        val root = service.rootInActiveWindow
        val inputNode = root?.let { findEditable(it) }
        val wrote = inputNode?.let { setText(it, QUESTION) } ?: false
        t = t.copy(input = "③ 输入框：${when {
            inputNode == null -> "没找到（YOYO 输入可能不是标准EditText）"
            wrote -> "已写入问题"
            else -> "找到了但写入被拒"
        }}")
        onTrace(t)
        inputNode?.recycle()

        // ---------- 第 3 步：找发送按钮 ----------
        val root2 = service.rootInActiveWindow
        val sendNode = root2?.let { findSendButton(it) }
        val sent = sendNode?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
        t = t.copy(send = "④ 发送：${when {
            sendNode == null -> "没找到发送按钮（可尝试回车，实验版暂未注入）"
            sent -> "已点发送"
            else -> "找到了但点击失败"
        }}")
        onTrace(t)
        sendNode?.recycle()
        root?.recycle()
        root2?.recycle()

        // ---------- 第 4 步：连续截图 OCR 等回答稳定 ----------
        val answer = if (ScreenshotOcr.supported) waitAndRead(service, cfg, before?.second.orEmpty(), onTrace, t) else {
            t = t.copy(read = "⑤ 读回：系统版本过低，无截图接口")
            onTrace(t)
            ""
        }

        t = t.copy(
            title = "◈ YOYO 实验 · 完成",
            answer = answer.ifEmpty { "（没读到回答。请看上面每一步的成败，判断卡在哪一关）" }
        )
        onTrace(t)
        cfg.appendLog("[YOYO实验] 结束，读回 ${answer.length} 字")
    }

    /** 长按手势：按住 bottomY 620ms 再松开。true=系统回调 onCompleted（手势跑完）。 */
    private suspend fun longPress(service: AccessibilityService, x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 620L)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke).build()
        // 关键：dispatchGesture 返回 true 只代表"手势已排队"，真正成功与否看 callback。
        // 用 2 秒超时兜住回调不来的情况，避免实验永久卡死。
        return withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val cb = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val sent = runCatching { service.dispatchGesture(gesture, cb, null) }.getOrDefault(false)
                if (!sent && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    /** 发系统助手意图，尝试唤出 YOYO。 */
    private fun fireAssist(service: Context): Boolean = runCatching {
        val i = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(i)
        true
    }.getOrDefault(false)

    /** 遍历节点树找可编辑输入框。 */
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val n = queue.removeFirstOrNull() ?: break
            if (!n.isVisibleToUser) { recycleChildren(n); continue }
            val cls = n.className?.toString().orEmpty()
            if (n.isEditable || cls.contains("EditText") || cls.contains("InputField")) {
                fallback = n
                break
            }
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }?.let { queue.add(it) }
            }
        }
        // 回收没进结果队列的其余节点（简化：只回收 fallback 之外的首层已取子节点）
        return fallback
    }

    /** 找发送按钮：文字/描述含"发送/回车/send/➤"，且可点击。 */
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keys = listOf("发送", "send", "➤", "➜", "提交", "完成")
        while (queue.isNotEmpty()) {
            val n = queue.removeFirstOrNull() ?: break
            if (!n.isVisibleToUser) { recycleChildren(n); continue }
            val text = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).lowercase()
            if (n.isClickable && keys.any { text.contains(it.lowercase()) }) return n
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun recycleChildren(n: AccessibilityNodeInfo) {
        for (i in 0 until n.childCount) {
            runCatching { n.getChild(i)?.recycle() }
        }
    }

    /** 往节点写文本（无障碍标准接口）。 */
    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    /**
     * 连续截图 OCR：最多 5 轮，每轮间隔 1.2 秒（系统截图接口有节流）。
     * 取"新增文字"作为回答——排除唤起前屏幕上已有的题面文字。
     */
    private suspend fun waitAndRead(
        service: AccessibilityService,
        cfg: ConfigStore,
        beforeText: String,
        onTrace: (Trace) -> Unit,
        seed: Trace
    ): String {
        var t = seed
        val beforeWords = beforeText.split(Regex("\\s+")).filter { it.length >= 2 }.toSet()
        var best = ""
        for (round in 1..5) {
            delay(1200)
            val lines = ScreenshotOcr.grabScaled(service)?.let { bmp ->
                val ls = ScreenshotOcr.recognizeLines(bmp).map { it.text }
                bmp.recycle()
                ls
            } ?: emptyList()

            val fresh = lines.filter { line ->
                val w = line.trim()
                w.isNotEmpty() && !beforeWords.any { bw -> w.contains(bw) || bw.contains(w) }
            }.joinToString("\n")

            if (fresh.length > best.length) best = fresh
            t = t.copy(read = "⑤ 读回第 $round/5 轮 · 新增 ${fresh.length} 字")
            onTrace(t)
            if (fresh.length > 40) break
        }
        cfg.appendLog("[YOYO实验] OCR 读回 ${best.length} 字")
        return best.take(1200)
    }
}
