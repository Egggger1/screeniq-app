package com.screeniq.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * @param px,py 看图时模型给出的"该点哪里"（相对坐标 0~1，左上为原点）。
 *              没有就是 -1 —— 调用方据此决定走节点点击还是坐标点击。
 * @param choice 看图时模型选的编号（截图上的 Set-of-Mark 小圆牌）。
 *               0 表示没有。有它时优先按"编号对应的文字框"点，比坐标准得多。
 */
data class AiResult(
    val answer: String,
    val model: String,
    val ok: Boolean,
    val note: String,
    val px: Float = -1f,
    val py: Float = -1f,
    val choice: Int = 0
)

/** 内置模型清单：都走 OpenAI 兼容接口。 */
object Providers {
    val list = listOf(
        mapOf("id" to "deepseek", "name" to "DeepSeek", "model" to "deepseek-chat",
              "base_url" to "https://api.deepseek.com/chat/completions",
              "note" to "中文强、价格低，推荐",
              "get_key_url" to "https://platform.deepseek.com/api_keys"),
        mapOf("id" to "kimi", "name" to "Kimi (Moonshot)", "model" to "moonshot-v1-8k",
              "base_url" to "https://api.moonshot.cn/v1/chat/completions",
              "note" to "国产长文本模型，支持看图",
              "get_key_url" to "https://platform.moonshot.cn/console/api-keys"),
        mapOf("id" to "qwen", "name" to "通义千问 (DashScope)", "model" to "qwen-plus",
              "base_url" to "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
              "note" to "阿里云兼容模式，支持看图",
              "get_key_url" to "https://dashscope.console.aliyun.com/apiKey"),
        mapOf("id" to "openai", "name" to "OpenAI", "model" to "gpt-4o-mini",
              "base_url" to "https://api.openai.com/v1/chat/completions",
              "note" to "需海外网络，支持看图",
              "get_key_url" to "https://platform.openai.com/api-keys"),
    )
    fun find(id: String) = list.find { it["id"] == id }
}

private const val PROMPT_DETAIL =
    "你是手机端答疑助手，答案要让人一眼看完。规则：\n" +
    "1. 第一行只写最终答案，格式「答案：xxx」。选择题写选项文字，例如「答案：B. 万里长征人未还」。\n" +
    "2. 之后最多 3 行要点解析，每行不超过 30 字；不必解释就只给答案。\n" +
    "3. 不要寒暄、不要复述题目、不要 Markdown 标题、不要加粗星号。\n" +
    "4. 信息不足只写「信息不足」。"

private const val PROMPT_BRIEF =
    "你是手机端答疑助手，用户只想快速核对答案。规则：\n" +
    "1. 只输出一行：答案本身。选择题写成「A. 内容」。\n" +
    "2. 不要解释、不要步骤、不要寒暄。\n" +
    "3. 信息不足只写「信息不足」。"

/**
 * 看图时追加的规则：
 *  5/6 让模型别因为"文字里没这个字"就说信息不足（猜字题的核心就在图里）；
 *  7   Set-of-Mark：截图上的每行字都编了号，让模型"选编号"而不是估坐标。
 *      这是 AppAgent / Mobile-Agent 的做法——模型认数字比认像素位置稳得多；
 *  8   兼容坐标输出，作为编号缺失时的兜底。
 */
private const val PROMPT_VISION =
    "\n5. 我同时发了一张手机屏幕截图。如果题目是「看图猜字 / 这个字读什么 / 图中是什么」，" +
    "请直接看图认字：指出那个字怎么写、读什么、拼音（带声调）是什么，不要因为文字里没有这个字就说信息不足。\n" +
    "6. 截图里可能混着 App 的广告、按钮、活动说明，以及一个写着「屏知」的小悬浮条，全部忽略，只关注题目本身。\n" +
    "7. 截图里每一行文字左边都有一个带白色数字的小圆牌（编号）。这组编号只用来定位：" +
    "如果答案就是屏幕上某一个可点选项，请在最后单独一行写 CHOICE:编号（只写数字）。" +
    "编号绝对不要写进答案正文。\n" +
    "8. 如果看不到编号但能确定位置，也可以写一行 POINT:x,y，x、y 是 0~1 的小数（左上角是 0,0）。" +
    "能看到编号就只写 CHOICE，不要写 POINT。"

class AiClient(private val cfg: ConfigStore) {

    /** 「POINT:0.18,0.62」这类坐标行的解析式。模型偶尔写成中文冒号或全角逗号，一并兜住。 */
    private val pointRe = Regex(
        "(?:POINT|point|Point|坐标|位置)\\s*[:：]?\\s*[（(]?\\s*(-?\\d+(?:\\.\\d+)?)\\s*[，,\\s]\\s*(-?\\d+(?:\\.\\d+)?)"
    )

    /** 「CHOICE:3」这类编号行。带「选项编号/编号」写法也认。 */
    private val choiceRe = Regex(
        "(?:CHOICE|choice|Choice|选项编号|选择编号|编号)\\s*[:：]\\s*(\\d{1,3})"
    )

    /**
     * 把坐标/编号行从答案正文里摘出来：正文给用户看，坐标与编号给自动点选用。
     * 编号是 0 基还是 1 基？截图上的圆牌是从 1 开始写的，所以这里原样返回，由调用方减 1。
     */
    private fun parseTags(raw: String): AiResult {
        var text = raw
        var px = -1f
        var py = -1f
        var choice = 0

        pointRe.find(text)?.let { m ->
            val x = m.groupValues[1].toFloatOrNull()
            val y = m.groupValues[2].toFloatOrNull()
            if (x != null && y != null && x >= 0f && y >= 0f) {
                px = x
                py = y
                text = text.replace(m.value, "")
            }
        }
        choiceRe.find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            if (n > 0) {
                choice = n
                text = text.replace(m.value, "")
            }
        }
        return AiResult(text.trim(), "", true, "", px, py, choice)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        // 总超时：视觉请求偶尔会"连上了但一直不吐字"，没有它整条链路会卡死在 busy 里
        .callTimeout(24, TimeUnit.SECONDS)
        .build()

    /**
     * 当前配置能不能"看图答题"。
     * 猜字/认字/看图类题目，题干的核心（那个字）往往是一张图或 Canvas，
     * 离线 OCR 对生僻字基本无能为力（"嫑"这种），只有视觉模型认得出来。
     */
    fun canUseVision(): Boolean = visionModel() != null

    /** 视觉模型名；当前服务商没有视觉能力时返回 null（DeepSeek 就是这种情况）。 */
    private fun visionModel(): String? {
        val pid = cfg.provider()
        val custom = cfg.model()
        return when (pid) {
            // Moonshot 的视觉模型走同一个 endpoint，只需换 model 名
            "kimi" -> if (custom.contains("vision") || custom.contains("kimi-latest")) custom
                      else "moonshot-v1-8k-vision-preview"
            "qwen" -> if (custom.contains("vl")) custom else "qwen-vl-max"
            "openai" -> custom.ifBlank { "gpt-4o-mini" }
            else -> null
        }
    }

    suspend fun answer(question: String, explain: Boolean): AiResult =
        request(question, null, explain)

    /** 带截图的提问：截图只在本机内存里压成 JPEG，随请求发出，不落盘。 */
    suspend fun answerWithImage(question: String, imageBase64: String, explain: Boolean): AiResult =
        request(question, imageBase64, explain)

    private suspend fun request(
        question: String, imageBase64: String?, explain: Boolean
    ): AiResult = withContext(Dispatchers.IO) {
        val key = cfg.apiKey()
        val pid = cfg.provider()
        val provider = Providers.find(pid)

        if (key.isBlank() || provider == null) {
            return@withContext mockAnswer(question, explain)
        }

        val baseUrl = cfg.baseUrl().ifBlank { provider["base_url"] as String }
        val model = if (imageBase64 != null) {
            visionModel() ?: (provider["model"] as String)
        } else {
            cfg.model().ifBlank { provider["model"] as String }
        }
        val name = provider["name"] as String
        val prompt = (if (explain) PROMPT_DETAIL else PROMPT_BRIEF) +
                if (imageBase64 != null) PROMPT_VISION else ""

        try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", prompt))
                    if (imageBase64 == null) {
                        put(JSONObject().put("role", "user").put("content", question))
                    } else {
                        // OpenAI 兼容的多模态格式：content 是数组
                        val parts = JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", question))
                            put(
                                JSONObject().put("type", "image_url").put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                                )
                            )
                        }
                        put(JSONObject().put("role", "user").put("content", parts))
                    }
                })
                put("temperature", 0.3)
                put("stream", false)
                // 限长=限时：不限的话模型会写一大段解析，光生成就要好几秒
                put("max_tokens", if (explain) 360 else 90)
            }.toString()

            val req = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val hint = if (imageBase64 != null && (resp.code == 400 || resp.code == 404))
                        "（$model 可能不支持图片，换个模型或关掉看图）" else ""
                    return@withContext AiResult("", name, false, "接口返回 ${resp.code}$hint")
                }
                val data = JSONObject(resp.body!!.string())
                val text = data.getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                val tagged = parseTags(text)
                AiResult(tagged.answer, "$name · $model", true, "", tagged.px, tagged.py, tagged.choice)
            }
        } catch (e: Exception) {
            AiResult("", name, false, "调用失败：${e.javaClass.simpleName}")
        }
    }

    /** 假答题兜底：没密钥/失败时也能出答案，跟电脑原型一致。 */
    private fun mockAnswer(q: String, explain: Boolean): AiResult {
        val text = if (!explain) {
            when {
                "f(x)" in q || "函数" in q -> "3"
                "1+1" in q || "一加一" in q -> "2"
                else -> "（示例答案：给完整算式才能算）"
            }
        } else {
            if (q.isBlank()) "（没读到题目文字）"
            else "我读到的题目是：$q\n（当前为假答题演示。点右上角设置填入 API 密钥即可切换到真实模型）"
        }
        return AiResult(text, "假答题(Mock)", false, "未配置真实模型")
    }
}
