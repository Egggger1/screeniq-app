package com.screeniq.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.Key
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * 配置存储。
 *
 * 三处刻意分开，避免一个备份/一次泄露带走全部：
 *  - screeniq_config：开关、模型、接口地址；密钥按服务商分槽存放，值用 Android Keystore 加密。
 *  - screeniq_log：诊断日志，只记长度/哈希，不记题目与答案原文。
 *  - AndroidManifest 里 allowBackup=false，两者都不进云备份。
 */
class ConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val sp = context.getSharedPreferences("screeniq_config", Context.MODE_PRIVATE)
    private val logSp = context.getSharedPreferences("screeniq_log", Context.MODE_PRIVATE)
    private val logLock = Any()

    companion object {
        const val ACTION_LOG = "com.screeniq.LOG_CHANGED"
        private const val KEY_ALIAS = "screeniq_api_key_v1"
        private const val LEGACY_KEY = "api_key"
        private fun slot(provider: String) = "key_$provider"
    }

    private fun notifyLogChanged() {
        appContext.sendBroadcast(android.content.Intent(ACTION_LOG).setPackage(appContext.packageName))
    }

    fun provider(): String = sp.getString("provider", "deepseek") ?: "deepseek"

    /** 密钥按服务商分槽；旧版单槽 api_key 只在首次读取时迁移一次。 */
    fun apiKey(): String {
        val p = provider()
        var stored = sp.getString(slot(p), "") ?: ""
        if (stored.isEmpty()) {
            val legacy = sp.getString(LEGACY_KEY, "") ?: ""
            if (legacy.isNotEmpty()) {
                stored = legacy
                sp.edit { putString(slot(p), seal(legacy)).remove(LEGACY_KEY) }
            }
        }
        return unseal(stored)
    }

    fun keyIsEncrypted(): Boolean = sp.getString(slot(provider()), "")?.startsWith("E:") == true
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

    /** 全局运行光边框：默认开（视觉提示，不涉隐私） */
    fun globalBorder(): Boolean = sp.getBoolean("global_border", true)
    fun setGlobalBorder(v: Boolean) = sp.edit { putBoolean("global_border", v) }

    /** 常驻控制悬浮球：默认开 */
    fun bubble(): Boolean = sp.getBoolean("bubble", true)
    fun setBubble(v: Boolean) = sp.edit { putBoolean("bubble", v) }

    /** 诊断日志：单独 prefs、上限 30 行，读改写加锁防丢行。 */
    fun log(): String = synchronized(logLock) { logSp.getString("read_log", "") ?: "" }
    fun appendLog(line: String) {
        synchronized(logLock) {
            val lines = (logSp.getString("read_log", "") ?: "").lines().takeLast(29).toMutableList()
            lines.add(line)
            logSp.edit { putString("read_log", lines.takeLast(30).joinToString("\n")) }
        }
        notifyLogChanged()
    }
    fun clearLog() {
        synchronized(logLock) { logSp.edit { putString("read_log", "") } }
        notifyLogChanged()
    }

    /**
     * 保存模型配置。安全规则：
     *  - 换服务商且密钥留空 → 清掉**新**服务商槽里的旧 key，强制重新填；绝不能拿 A 家的 key 请求 B 家。
     *  - 同服务商留空 → 视为"不改动密钥"（界面回显是打码的）。
     *  - base_url / model 换服务商时一并清空，避免新钥匙敲旧门。
     */
    fun save(newProvider: String, apiKey: String, baseUrl: String, model: String) {
        val old = provider()
        sp.edit {
            putString("provider", newProvider)
            if (apiKey.isNotBlank()) {
                putString(slot(newProvider), seal(apiKey.trim()))
            } else if (old != newProvider) {
                remove(slot(newProvider))
            }
            if (baseUrl.isNotBlank()) putString("base_url", baseUrl.trim())
            else if (old != newProvider) remove("base_url")
            if (model.isNotBlank()) putString("model", model.trim())
            else if (old != newProvider) remove("model")
            remove(LEGACY_KEY)
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
            put("key_encrypted", keyIsEncrypted())
            put("base_url", baseUrl())
            put("model", model())
            put("auto_read", autoRead())
            put("auto_action", autoAction())
            put("overlay", overlay())
            put("explain", explain())
        }
    }

    // ===== 密钥封存：Android Keystore + AES/GCM，无额外依赖 =====
    /** E: = 已加密；P: = 设备不支持 Keystore 时的明文降级（界面会提示）。 */
    private fun seal(plain: String): String = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val blob = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        "E:" + Base64.encodeToString(blob, Base64.NO_WRAP)
    } catch (e: Exception) {
        "P:" + plain
    }

    private fun unseal(stored: String): String = when {
        stored.isEmpty() -> ""
        stored.startsWith("P:") -> stored.removePrefix("P:")
        stored.startsWith("E:") -> try {
            val blob = Base64.decode(stored.removePrefix("E:"), Base64.NO_WRAP)
            if (blob.size < 28) ""
            else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    keystoreKey(),
                    GCMParameterSpec(128, blob.copyOfRange(0, 12))
                )
                String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
        else -> stored   // 兼容还没迁移的旧明文
    }

    private fun keystoreKey(): Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply { init(spec) }.generateKey()
        }
        return ks.getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("Keystore 拿不到密钥")
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
