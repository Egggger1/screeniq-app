package com.screeniq.app

import android.content.Context
import androidx.core.content.edit

/** 配置存储：模型与密钥只存本机 SharedPreferences，不上传、不联网回传。 */
class ConfigStore(context: Context) {
    private val sp = context.getSharedPreferences("screeniq_config", Context.MODE_PRIVATE)

    fun provider(): String = sp.getString("provider", "deepseek") ?: "deepseek"
    fun apiKey(): String = sp.getString("api_key", "") ?: ""
    fun baseUrl(): String = sp.getString("base_url", "") ?: ""
    fun model(): String = sp.getString("model", "") ?: ""

    fun save(provider: String, apiKey: String, baseUrl: String, model: String) {
        sp.edit {
            putString("provider", provider)
            // 空字符串表示"不改动密钥"，避免界面打码值覆盖真密钥
            if (apiKey.isNotBlank()) putString("api_key", apiKey.trim())
            if (baseUrl.isNotBlank()) putString("base_url", baseUrl.trim())
            if (model.isNotBlank()) putString("model", model.trim())
        }
    }

    /** 给界面读的公开配置：密钥只回显打码，绝不回明文。 */
    fun publicJson(): String {
        val key = apiKey()
        val masked = if (key.length >= 8) "${key.take(4)}••••${key.takeLast(4)}"
                     else if (key.isNotEmpty()) "••••" else ""
        return buildJson {
            put("provider", provider())
            put("has_key", key.isNotEmpty())
            put("api_key_masked", masked)
            put("base_url", baseUrl())
            put("model", model())
        }
    }
}

/** 极简 JSON 构造器（不引 Gson，保持依赖少、包体小）。 */
class JsonBuilder {
    private val sb = StringBuilder("{")
    private var first = true
    fun put(key: String, value: String) = apply { add(key, "\"${escape(value)}\"") }
    fun put(key: String, value: Boolean) = apply { add(key, value.toString()) }
    fun put(key: String, value: Int) = apply { add(key, value.toString()) }
    private fun add(key: String, v: String) {
        if (!first) sb.append(',') else first = false
        sb.append("\"").append(key).append("\":").append(v)
    }
    fun build() = sb.append('}').toString()
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}

inline fun buildJson(block: JsonBuilder.() -> Unit): String = JsonBuilder().apply(block).build()
