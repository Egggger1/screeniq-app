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

class AiClient(private val cfg: ConfigStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
