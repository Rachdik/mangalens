package com.mangalens.floating

import android.content.Context
import com.mangalens.ocr.LanguageScript

/** Preferensi ringan khusus bubble terjemahan. */
internal class FloatingSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var script: LanguageScript
        get() = prefs.getString(KEY_SCRIPT, null)
            ?.let { runCatching { LanguageScript.valueOf(it) }.getOrNull() }
            ?: LanguageScript.JAPANESE
        set(value) = prefs.edit().putString(KEY_SCRIPT, value.name).apply()

    var languageTag: String
        get() = prefs.getString(KEY_TAG, null) ?: "ja"
        set(value) = prefs.edit().putString(KEY_TAG, value).apply()

    private companion object {
        const val PREFS = "mangalens_floating"
        const val KEY_SCRIPT = "script"
        const val KEY_TAG = "language_tag"
    }
}
