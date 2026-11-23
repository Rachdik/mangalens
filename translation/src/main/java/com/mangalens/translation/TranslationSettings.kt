package com.mangalens.translation

import android.content.Context

/** Provider terjemahan yang bisa dipilih pengguna. */
enum class TranslationBackend {
    /** ML Kit on-device: offline, tanpa kunci API, hasil masih literal. */
    ML_KIT,

    /** LLM lewat API OpenAI-compatible: lebih luwes, butuh kunci di .env. */
    LLM,

    /** MyMemory: gratis tanpa kunci, hasil bervariasi, bisa timeout. */
    MY_MEMORY
}

/**
 * Pilihan provider disimpan di SharedPreferences agar layar utama dan service
 * bubble memakai pengaturan yang sama.
 */
class TranslationSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var backend: TranslationBackend
        get() = runCatching {
            TranslationBackend.valueOf(
                prefs.getString(KEY_BACKEND, null) ?: TranslationBackend.ML_KIT.name
            )
        }.getOrDefault(TranslationBackend.ML_KIT)
        set(value) = prefs.edit().putString(KEY_BACKEND, value.name).apply()

    /** "auto" atau kode BCP-47 bahasa sumber (mis. "en", "es", "ja"). */
    var sourceTag: String
        get() = prefs.getString(KEY_SOURCE, null) ?: "auto"
        set(value) = prefs.edit().putString(KEY_SOURCE, value).apply()

    /** Kode BCP-47 bahasa hasil terjemahan (default Indonesia). */
    var targetTag: String
        get() = prefs.getString(KEY_TARGET, null) ?: "id"
        set(value) = prefs.edit().putString(KEY_TARGET, value).apply()

    companion object {
        /** True bila LLM_API_KEY dari .env ikut tertanam saat build. */
        val llmConfigured: Boolean
            get() = BuildConfig.LLM_API_KEY.isNotBlank()

        /** Nama model LLM yang dipakai (dari .env). */
        val llmModel: String
            get() = BuildConfig.LLM_MODEL

        private const val PREFS = "mangalens_translation"
        private const val KEY_BACKEND = "backend"
        private const val KEY_SOURCE = "source_tag"
        private const val KEY_TARGET = "target_tag"
    }
}
