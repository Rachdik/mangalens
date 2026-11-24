package com.mangalens.translation

import android.content.Context

/**
 * Tingkat transparansi latar belakang overlay terjemahan.
 */
enum class OverlayOpacity(val alpha: Int) {
    TRANSPARENT(0),
    QUARTER(64),
    HALF(128),
    THREE_QUARTER(192),
    SOLID(255)
}

/**
 * Ukuran teks terjemahan.
 */
enum class TextSize(val sp: Float) {
    SMALL(12f),
    NORMAL(14f),
    LARGE(16f)
}

/**
 * Warna teks terjemahan.
 */
enum class TextColor {
    AUTO,
    WHITE,
    BLACK
}

/**
 * Warna latar belakang kotak terjemahan.
 */
enum class BackgroundColor {
    AUTO,
    WHITE,
    BLACK,
    TRANSPARENT
}

/**
 * Pilihan provider terjemahan yang bisa dipilih pengguna.
 */
enum class TranslationBackend {
    /**
     * ML Kit on-device: offline, tanpa kunci API, hasil masih literal.
     */
    ML_KIT,

    /**
     * LLM lewat API OpenAI-compatible: lebih luwes, butuh kunci di .env.
     */
    LLM,

    /**
     * MyMemory: gratis tanpa kunci, hasil bervariasi, bisa timeout.
     */
    /**
     * Google Gemini: format API berbeda, butuh kunci pengguna.
     */
    GEMINI,

    MY_MEMORY
}

/**
 * Pilihan provider disimpan di SharedPreferences agar layar utama dan service
 * bubble memakai pengaturan yang sama.
 */
class TranslationSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        SecureLlmStore.init(context)
    }

    /** Kunci API LLM; disimpan terenkripsi, tidak pernah di BuildConfig/APK. */
    var llmApiKey: String
        get() = SecureLlmStore.apiKey()
        set(value) = SecureLlmStore.setApiKey(value)

    /** Endpoint OpenAI-compatible, boleh diubah pengguna (bukan rahasia). */
    var llmBaseUrl: String
        get() = prefs.getString(KEY_LLM_BASE_URL, null)
            ?: BuildConfig.LLM_BASE_URL
        set(value) = prefs.edit().putString(KEY_LLM_BASE_URL, value.trim()).apply()

    var llmModel: String
        get() = prefs.getString(KEY_LLM_MODEL, null) ?: BuildConfig.LLM_MODEL
        set(value) = prefs.edit().putString(KEY_LLM_MODEL, value.trim()).apply()

    val llmConfigured: Boolean get() = llmApiKey.isNotBlank()

    fun llmConfig(): LlmConfig = LlmConfig(
        apiKey = llmApiKey,
        baseUrl = llmBaseUrl,
        model = llmModel,
        timeoutMs = translationTimeoutSeconds * 1000
    )

    /** Provider LLM yang dipilih; hanya memengaruhi nilai awal base URL/model. */
    var llmProviderKey: String
        get() = prefs.getString(KEY_LLM_PROVIDER, null) ?: LlmPreset.OPENAI.key
        set(value) = prefs.edit().putString(KEY_LLM_PROVIDER, value).apply()

    /** Terjemahkan ulang dengan ML Kit bila provider utama gagal. */
    var autoFallback: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FALLBACK, value).apply()

    /** Batas waktu satu permintaan terjemahan (detik). */
    var translationTimeoutSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_SEC, 30).coerceIn(5, 120)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_SEC, value.coerceIn(5, 120)).apply()

    /** Aktifkan inpainting di Reader (hapus teks asli pada gambar). */
    var inpaintingEnabled: Boolean
        get() = prefs.getBoolean(KEY_INPAINTING, false)
        set(value) = prefs.edit().putBoolean(KEY_INPAINTING, value).apply()

    /** Kembalikan seluruh preferensi terjemahan/tampilan ke nilai awal. */
    fun resetToDefaults() {
        prefs.edit()
            .remove(KEY_BACKEND)
            .remove(KEY_SOURCE)
            .remove(KEY_TARGET)
            .remove(KEY_OPACITY)
            .remove(KEY_TEXT_SIZE)
            .remove(KEY_TEXT_COLOR)
            .remove(KEY_BG_COLOR)
            .remove(KEY_AUTO_FALLBACK)
            .remove(KEY_TIMEOUT_SEC)
            .remove(KEY_LLM_PROVIDER)
            .remove(KEY_LLM_MODEL)
            .remove(KEY_LLM_BASE_URL)
            .apply()
        SecureLlmStore.clear()
    }

    var backend: TranslationBackend
        get() = runCatching {
            TranslationBackend.valueOf(
                prefs.getString(KEY_BACKEND, null) ?: TranslationBackend.ML_KIT.name
            )
        }.getOrDefault(TranslationBackend.ML_KIT)
        set(value) = prefs.edit().putString(KEY_BACKEND, value.name).apply()

    /**
     * "auto" atau kode BCP-47 bahasa sumber (mis. "en", "es", "ja").
     */
    var sourceTag: String
        get() = prefs.getString(KEY_SOURCE, null) ?: "auto"
        set(value) = prefs.edit().putString(KEY_SOURCE, value).apply()

    /**
     * Kode BCP-47 bahasa hasil terjemahan (default Indonesia).
     */
    var targetTag: String
        get() = prefs.getString(KEY_TARGET, null) ?: "id"
        set(value) = prefs.edit().putString(KEY_TARGET, value).apply()

    var overlayOpacity: OverlayOpacity
        get() = runCatching {
            OverlayOpacity.valueOf(
                prefs.getString(KEY_OPACITY, null) ?: OverlayOpacity.HALF.name
            )
        }.getOrDefault(OverlayOpacity.HALF)
        set(value) = prefs.edit().putString(KEY_OPACITY, value.name).apply()

    var textSize: TextSize
        get() = runCatching {
            TextSize.valueOf(
                prefs.getString(KEY_TEXT_SIZE, null) ?: TextSize.NORMAL.name
            )
        }.getOrDefault(TextSize.NORMAL)
        set(value) = prefs.edit().putString(KEY_TEXT_SIZE, value.name).apply()

    var textColor: TextColor
        get() = runCatching {
            TextColor.valueOf(
                prefs.getString(KEY_TEXT_COLOR, null) ?: TextColor.AUTO.name
            )
        }.getOrDefault(TextColor.AUTO)
        set(value) = prefs.edit().putString(KEY_TEXT_COLOR, value.name).apply()

    var backgroundColor: BackgroundColor
        get() = runCatching {
            BackgroundColor.valueOf(
                prefs.getString(KEY_BG_COLOR, null) ?: BackgroundColor.AUTO.name
            )
        }.getOrDefault(BackgroundColor.AUTO)
        set(value) = prefs.edit().putString(KEY_BG_COLOR, value.name).apply()

    companion object {
        private const val PREFS = "mangalens_translation"
        private const val KEY_LLM_BASE_URL = "llm_base_url"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_BACKEND = "backend"
        private const val KEY_SOURCE = "source_tag"
        private const val KEY_TARGET = "target_tag"
        private const val KEY_OPACITY = "overlay_opacity"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_BG_COLOR = "bg_color"
        private const val KEY_AUTO_FALLBACK = "auto_fallback"
        private const val KEY_TIMEOUT_SEC = "translation_timeout_sec"
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_INPAINTING = "inpainting_enabled"
    }
}
