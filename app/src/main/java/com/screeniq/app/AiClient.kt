package com.screeniq.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiResult(val answer: String, val model: String, val ok: Boolean, val note: String)

/** 内置模型清单：都走 OpenAI 兼容接口。 */
object Providers {
    val list = listOf(
        mapOf("id" to "deepseek", "name" to "DeepSeek", "model" to "deepseek-chat",
              "base_url" to "https://api.deepseek.com/chat/completions",
              "note" to "中文强、价格低，推荐",
              "get_key_url" to "https://platform.deepseek.com/api_keys"),
        mapOf("id" to "kimi", "name" to "Kimi (Moonshot)", "model" to "moonshot-v1-8k",
              "base_url" to "https://api.moonshot.cn/v1/chat/completions",
              "note" to "国产长文本模型",
              "get_key_url" to "https://platform.moonshot.cn/console/api-keys"),
        mapOf("id" to "qwen", "name" to "通义千问 (DashScope)", "model" to "qwen-plus",
              "base_url" to "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
              "note" to "阿里云兼容模式",
              "get_key_url" to "https://dashscope.console.aliyun.com/apiKey"),
        mapOf("id" to "openai", "name" to "OpenAI", "model" to "gpt-4o-mini",
              "base_url" to "https://api.openai.com/v1/chat/completions",
              "note" to "需海外网络",
              "get_key_url" to "https://platform.openai.com/api-keys"),
    )
    fun find(id: String) = list.find { it["id"] == id }
}

private const val PROMPT_DETAIL =
    "你是一个手机端的贴身答疑助手，用户会把屏幕上看到的题目发给你。要求：\n" +
    "1. 先给出最终答案，再讲简明解题步骤，重点突出。\n" +
    "2. 用中文回答；公式写得清楚，能用 LaTeX 就用。\n" +
    "3. 如果是选择题，明确指出正确选项并解释为什么。\n" +
    "4. 如果题目信息不足，直接说明缺什么，不要瞎编。\n" +
    "5. 回答精炼，不啰嗦。"

private const val PROMPT_BRIEF =
    "你是一个手机端的贴身答疑助手，用户只想快速核对答案。要求：\n" +
    "1. 只输出最终答案，不要解题步骤、不要解释、不要寒暄。\n" +
    "2. 选择题只给选项字母和答案内容，例如「B. 3」。\n" +
    "3. 能用一个数或一个词回答，就不要写完整句子。\n" +
    "4. 用中文回答。信息不足时只说「信息不足」。"

class AiClient(private val cfg: ConfigStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun answer(question: String, explain: Boolean): AiResult = withContext(Dispatchers.IO) {
        val key = cfg.apiKey()
        val pid = cfg.provider()
        val provider = Providers.find(pid)

        if (key.isBlank() || provider == null) {
            return@withContext mockAnswer(question, explain)
        }

        val baseUrl = cfg.baseUrl().ifBlank { provider["base_url"] as String }
        val model = cfg.model().ifBlank { provider["model"] as String }
        val name = provider["name"] as String
        val prompt = if (explain) PROMPT_DETAIL else PROMPT_BRIEF

        try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", prompt))
                    put(JSONObject().put("role", "user").put("content", question))
                })
                put("temperature", 0.3)
                put("stream", false)
            }.toString()

            val req = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext AiResult("", name, false, "接口返回 ${resp.code}")
                }
                val data = JSONObject(resp.body!!.string())
                val text = data.getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                AiResult(text, "$name · $model", true, "")
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
