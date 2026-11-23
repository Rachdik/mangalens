package com.mangalens.floating

import android.content.Context
import com.mangalens.ocr.LanguageScript

/** Preferensi ringan khusus bubble terjemahan. */
internal class FloatingSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Default AUTO: satu bubble yang sama harus bisa dipakai di manga Jepang
     * maupun komik/webtoon Inggris tanpa kembali ke MangaLens.
     */
    var script: LanguageScript
        get() = prefs.getString(KEY_SCRIPT, null)
            ?.let { runCatching { LanguageScript.valueOf(it) }.getOrNull() }
            ?: LanguageScript.AUTO
        set(value) = prefs.edit().putString(KEY_SCRIPT, value.name).apply()

    var languageTag: String
        get() = prefs.getString(KEY_TAG, null) ?: "auto"
        set(value) = prefs.edit().putString(KEY_TAG, value).apply()

    private companion object {
        const val PREFS = "mangalens_floating"
        const val KEY_SCRIPT = "script"
        const val KEY_TAG = "language_tag"
    }
}
