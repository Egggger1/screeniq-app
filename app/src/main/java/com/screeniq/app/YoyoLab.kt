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
 * 依据真机调研（荣耀官方俱乐部 + MagicOS 使用教程）纠正的关键认知：
 *  1. 唤醒 YOYO 靠「语音 / 长按电源键 / ACTION_ASSIST 意图」，不是底部小白条；
 *  2. 普通唤醒的 YOYO **默认看不到当前屏幕**，必须走「YOYO 问屏」入口；
 *  3. 进入问屏有三条路：唤醒后对话框输入「问屏」、双指按压、截屏后「问YOYO」。
 *
 * 本实验按无障碍能做到的部分，依次尝试并记录：
 *   ① 用 ACTION_ASSIST 意图唤醒（等价于电源键唤醒）；
 *   ② 在对话框写「问屏」并发送，进入识屏模式；
 *   ③ 再写题目问题并发送；
 *   ④ 连续截图 OCR 读回回答。
 * 双指按压属于系统级手势，无障碍不一定注入得了，作为诊断项说明，不强行依赖。
 *
 * 这一版**只诊断、不自动点选、不调云 API**。看到结果再决定要不要往下走。
 *
 * 安全边界：只用无障碍的公开意图/手势/节点写入接口，不碰私有 API、不 root；
 * 只写我们自己的问题文本，绝不读取或上报用户的其它屏幕内容。
 */
object YoyoLab {

    data class Trace(
        val title: String,
        val wake: String = "",
        val screenMode: String = "",
        val ask: String = "",
        val read: String = "",
        val answer: String = "",
        val hint: String = "",
        val finishedAt: Long = System.currentTimeMillis()
    ) {
        fun summary(): String = buildString {
            append(title).append('\n')
            if (wake.isNotEmpty()) append(wake).append('\n')
            if (screenMode.isNotEmpty()) append(screenMode).append('\n')
            if (ask.isNotEmpty()) append(ask).append('\n')
            if (read.isNotEmpty()) append(read).append('\n')
            if (hint.isNotEmpty()) append("\n").append(hint).append('\n')
            if (answer.isNotEmpty()) {
                append("\n──── YOYO 的回答 ────\n").append(answer)
            }
        }
    }

    private const val MAGIC_WORD = "问屏"
    private const val QUESTION = "这道题选哪个选项？只回答选项字母。"

    /**
     * 跑一次完整实验。必须在无障碍服务里调用。
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

        // 记下唤起前的屏幕文字，读回时用来排除题面本身
        val beforeText = if (ScreenshotOcr.supported) {
            ScreenshotOcr.grabScaled(service)?.let { bmp ->
                val s = ScreenshotOcr.recognizeLines(bmp).joinToString(" ") { it.text }
                bmp.recycle()
                s
            }
        } else "" ?: ""

        // ---------- ① 唤醒：ACTION_ASSIST（等价于电源键唤醒 YOYO）----------
        val wakeOk = fireAssist(service)
        var wakeDetail = "① 唤醒YOYO(助手意图)：${if (wakeOk) "已发出" else "无响应"}"
        t = t.copy(wake = wakeDetail)
        onTrace(t)
        delay(2000)

        // 助手意图没反应的话，再试一次「双指按压」问屏手势（官方推荐入口，但无障碍不一定能注入）
        if (!wakeOk) {
            val twoOk = twoFingerPress(service)
            wakeDetail += " · 双指按压问屏：${if (twoOk) "手势已注入" else "被系统拒绝"}"
            t = t.copy(wake = wakeDetail)
            onTrace(t)
            delay(1800)
        }

        // 唤醒后，找输入框。YOYO 面板的输入框通常是可编辑节点。
        var root = service.rootInActiveWindow
        var inputNode = root?.let { findEditable(it) }

        if (inputNode == null) {
            // 输入框找不到时，尝试截屏「问YOYO」这条路行不通（那是系统截图弹窗），
            // 这里明确记录失败点，便于判断是不是压根没唤醒。
            t = t.copy(
                screenMode = "② 问屏：没找到输入框——可能没唤醒成功，或YOYO输入不是标准控件",
                hint = "提示：请先在系统里开启「荣耀AI&YOYO > YOYO智能体 > YOYO问屏」开关，" +
                       "并确认YOYO版本支持（MagicOS 9.0+）。双指按压识屏无障碍无法可靠注入。"
            )
            onTrace(t)
            root?.recycle()
            finishEmpty(t, cfg, onTrace)
            return
        }

        // ---------- ② 输入「问屏」进入识屏模式 ----------
        val wroteMagic = setText(inputNode, MAGIC_WORD)
        val sentMagic = wroteMagic && clickSend(service)
        t = t.copy(screenMode = "② 输入「问屏」：${if (wroteMagic) "已写入" else "写入被拒"} / " +
                                  "发送：${if (sentMagic) "成功" else "失败或没找到发送键"}")
        onTrace(t)
        inputNode.recycle()
        root?.recycle()
        delay(2500)

        // ---------- ③ 再输入题目问题并发送 ----------
        root = service.rootInActiveWindow
        inputNode = root?.let { findEditable(it) }
        if (inputNode == null) {
            t = t.copy(ask = "③ 问题目：问屏后找不到输入框（界面可能已切换到识屏视图）")
            onTrace(t)
            root?.recycle()
            finishEmpty(t, cfg, onTrace)
            return
        }
        val wroteQ = setText(inputNode, QUESTION)
        val sentQ = wroteQ && clickSend(service)
        t = t.copy(ask = "③ 问题目：${if (wroteQ) "已写入" else "写入被拒"} / " +
                          "发送：${if (sentQ) "成功" else "失败或没找到发送键"}")
        onTrace(t)
        inputNode.recycle()
        root?.recycle()

        // ---------- ④ 连续截图 OCR 读回 ----------
        val answer = if (ScreenshotOcr.supported) {
            waitAndRead(service, cfg, beforeText, onTrace, t)
        } else {
            t = t.copy(read = "④ 读回：系统版本过低，无截图接口")
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

    private fun finishEmpty(t: Trace, cfg: ConfigStore, onTrace: (Trace) -> Unit) {
        val done = t.copy(
            title = "◈ YOYO 实验 · 中断",
            answer = "（在某一步卡住了，没走到读回答。按上面的提示检查设置后重试）"
        )
        onTrace(done)
        cfg.appendLog("[YOYO实验] 中断")
    }

    /** 发系统助手意图，尝试唤醒 YOYO（等价于电源键唤醒的路径）。 */
    private fun fireAssist(service: Context): Boolean = runCatching {
        val i = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(i)
        true
    }.getOrDefault(false)

    /**
     * 模拟双指按压（问屏手势）——留作诊断，不一定被系统接受。
     * 两条同时按下的 stroke，坐标在屏幕中部。
     */
    private suspend fun twoFingerPress(service: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val p1 = Path().apply { moveTo(cx - 60f, cy) }
        val p2 = Path().apply { moveTo(cx + 60f, cy) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p1, 0L, 500L))
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p2, 0L, 500L))
            .build()
        return withTimeoutOrNull(1500L) {
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

    /** 遍历节点树找可编辑输入框。 */
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var found: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val n = queue.removeFirstOrNull() ?: break
            if (n.isVisibleToUser) {
                val cls = n.className?.toString().orEmpty()
                if (n.isEditable || cls.contains("EditText") || cls.contains("InputField")) {
                    found = n
                    break
                }
                for (i in 0 until n.childCount) {
                    val child = runCatching { n.getChild(i) }.getOrNull()
                    if (child != null) queue.add(child)
                }
            }
        }
        return found
    }

    /** 找发送按钮：文字/描述含 发送/send/➤/提交，且可点击；找不到就用回车手势兜底。 */
    private fun clickSend(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keys = listOf("发送", "send", "➤", "➜", "提交", "完成", "搜索")
        var target: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val n = queue.removeFirstOrNull() ?: break
            if (n.isVisibleToUser) {
                val text = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).lowercase()
                if (n.isClickable && keys.any { text.contains(it.lowercase()) }) {
                    target = n
                    break
                }
                for (i in 0 until n.childCount) {
                    val child = runCatching { n.getChild(i) }.getOrNull()
                    if (child != null) queue.add(child)
                }
            }
        }
        val clicked = target?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
        root.recycle()
        return clicked
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
            t = t.copy(read = "④ 读回第 $round/5 轮 · 新增 ${fresh.length} 字")
            onTrace(t)
            if (fresh.length > 40) break
        }
        cfg.appendLog("[YOYO实验] OCR 读回 ${best.length} 字")
        return best.take(1200)
    }
}
