package com.mangalens.translation

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Preset endpoint yang kompatibel dengan Chat Completions OpenAI.
 * Provider dengan format berbeda (mis. Gemini) butuh adapter sendiri dan
 * sengaja tidak dipaksakan lewat jalur ini.
 */
enum class LlmPreset(
    val key: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String
) {
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    CUSTOM("custom", "Custom / kompatibel OpenAI", "", ""),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-flash");

    companion object {
        fun fromKey(key: String?): LlmPreset = entries.firstOrNull { it.key == key } ?: OPENAI
    }
}

/**
 * Konfigurasi provider LLM.
 *
 * [apiKey] hanya berasal dari penyimpanan terenkripsi yang diisi pengguna;
 * tidak pernah dari source code, BuildConfig, atau file .env.
 */
data class LlmConfig(
    val apiKey: String = "",
    val baseUrl: String = BuildConfig.LLM_BASE_URL,
    val model: String = BuildConfig.LLM_MODEL,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val MIN_TIMEOUT_MS = 5_000
        const val MAX_TIMEOUT_MS = 120_000
    }
    val configured: Boolean get() = apiKey.isNotBlank()
}

/**
 * Penyimpanan kunci API dengan EncryptedSharedPreferences (AES256-GCM).
 * Nilai tidak pernah ditulis ke Logcat maupun file proyek.
 */
object SecureLlmStore {

    private const val PREFS = "mangalens_llm_secure"
    private const val KEY_API = "llm_api_key"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            runCatching {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        }
    }

    fun apiKey(): String = prefs?.getString(KEY_API, "").orEmpty()

    fun setApiKey(value: String) {
        prefs?.edit()?.putString(KEY_API, value.trim())?.apply()
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_API)?.apply()
    }
}
