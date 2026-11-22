package com.screeniq.app

import android.content.Context
import androidx.core.content.edit

/** 配置存储：模型与密钥只存本机 SharedPreferences，不上传、不联网回传。 */
class ConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val sp = context.getSharedPreferences("screeniq_config", Context.MODE_PRIVATE)

    companion object {
        const val ACTION_LOG = "com.screeniq.LOG_CHANGED"
    }

    private fun notifyLogChanged() {
        appContext.sendBroadcast(android.content.Intent(ACTION_LOG).setPackage(appContext.packageName))
    }

    fun provider(): String = sp.getString("provider", "deepseek") ?: "deepseek"
    fun apiKey(): String = sp.getString("api_key", "") ?: ""
    fun baseUrl(): String = sp.getString("base_url", "") ?: ""
    fun model(): String = sp.getString("model", "") ?: ""

    // ===== 后台识屏开关（全部默认关闭，用户手动开启 = opt-in）=====
    /** 后台自动识屏总开关：无障碍服务是否真正去读屏并答题 */
    fun autoRead(): Boolean = sp.getBoolean("auto_read", false)
    fun setAutoRead(v: Boolean) = sp.edit { putBoolean("auto_read", v) }

    /** 自动操控：找到答案后是否自动点击选项/填入。默认关，需单独显式开启 */
    fun autoAction(): Boolean = sp.getBoolean("auto_action", false)
    fun setAutoAction(v: Boolean) = sp.edit { putBoolean("auto_action", v) }

    /** 悬浮窗显示答案；关闭时降级为通知 */
    fun overlay(): Boolean = sp.getBoolean("overlay", true)
    fun setOverlay(v: Boolean) = sp.edit { putBoolean("overlay", v) }

    /** 详细解释开关，答题服务要用 */
    fun explain(): Boolean = sp.getBoolean("explain", true)
    fun setExplain(v: Boolean) = sp.edit { putBoolean("explain", v) }

    /** 无障碍服务是否真正连接上（由服务在 onServiceConnected/onDestroy 写入，供界面区分"开关开了"与"服务连上了"） */
    fun serviceConnected(): Boolean = sp.getBoolean("service_connected", false)
    fun setServiceConnected(v: Boolean) = sp.edit { putBoolean("service_connected", v) }

    /** 全局运行光边框：开启后切到任意 App，屏幕四周都有流光表示在后台工作。默认开（视觉提示，不涉隐私） */
    fun globalBorder(): Boolean = sp.getBoolean("global_border", true)
    fun setGlobalBorder(v: Boolean) = sp.edit { putBoolean("global_border", v) }

    /** 常驻控制悬浮球：退出 App 后靠它开始/暂停，不用再回面板。默认开 */
    fun bubble(): Boolean = sp.getBoolean("bubble", true)
    fun setBubble(v: Boolean) = sp.edit { putBoolean("bubble", v) }

    /** 读屏日志（诊断用，最近若干条），只存文字摘要不存屏幕内容全文 */
    fun log(): String = sp.getString("read_log", "") ?: ""
    fun appendLog(line: String) {
        val existing = log().lines().takeLast(30).toMutableList()
        existing.add(line)
        sp.edit { putString("read_log", existing.takeLast(30).joinToString("\n")) }
        notifyLogChanged()
    }
    fun clearLog() {
        sp.edit { putString("read_log", "") }
        notifyLogChanged()
    }

    fun save(newProvider: String, apiKey: String, baseUrl: String, model: String) {
        val oldProvider = this.provider()
        sp.edit {
            putString("provider", newProvider)
            // 空字符串表示"不改动密钥"，避免界面打码值覆盖真密钥
            if (apiKey.isNotBlank()) putString("api_key", apiKey.trim())
            if (baseUrl.isNotBlank()) putString("base_url", baseUrl.trim())
            else if (oldProvider != newProvider) remove("base_url")   // 换模型要清掉旧接口地址，否则会拿新钥匙敲旧门
            if (model.isNotBlank()) putString("model", model.trim())
            else if (oldProvider != newProvider) remove("model")
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
            put("auto_read", autoRead())
            put("auto_action", autoAction())
            put("overlay", overlay())
            put("explain", explain())
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
