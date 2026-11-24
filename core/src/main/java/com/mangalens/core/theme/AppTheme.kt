package com.mangalens.core.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Warna aksen yang bisa dipilih pengguna. */
enum class AccentTheme(
    val key: String,
    val label: String,
    /** Warna utama, dipakai untuk pratinjau swatch di UI. */
    val previewColor: Int
) {
    PURPLE("purple", "Purple", 0xFF4F46E5.toInt()),
    BLUE("blue", "Blue", 0xFF2563EB.toInt()),
    CYAN("cyan", "Cyan", 0xFF0891B2.toInt()),
    GREEN("green", "Green", 0xFF15803D.toInt()),
    RED("red", "Red", 0xFFDC2626.toInt()),
    ORANGE("orange", "Orange", 0xFFEA580C.toInt()),
    PINK("pink", "Pink", 0xFFDB2777.toInt()),
    TEAL("teal", "Teal", 0xFF0F766E.toInt());

    companion object {
        fun fromKey(key: String?): AccentTheme =
            entries.firstOrNull { it.key == key } ?: PURPLE
    }
}

/** Mode tampilan aplikasi. */
enum class ThemeMode(val key: String, val label: String) {
    LIGHT("light", "Terang"),
    DARK("dark", "Gelap"),
    SYSTEM("system", "Ikuti Sistem");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

private val Context.appearanceStore by preferencesDataStore(name = "appearance")

object ThemeStore {

    private const val KEY_ACCENT = "accent"
    private const val KEY_MODE = "mode"
    private const val KEY_CUSTOM_ACCENT = "custom_accent"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var context: Context? = null

    @Volatile
    var accent: AccentTheme = AccentTheme.PURPLE
        private set

    @Volatile
    var customAccentColor: Int? = null
        private set

    @Volatile
    var mode: ThemeMode = ThemeMode.SYSTEM
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        this.context = app
        runBlocking {
            runCatching {
                val prefs = app.appearanceStore.data.first()
                accent = AccentTheme.fromKey(prefs[stringPreferencesKey(KEY_ACCENT)])
                mode = ThemeMode.fromKey(prefs[stringPreferencesKey(KEY_MODE)])
                val custom = prefs[stringPreferencesKey(KEY_CUSTOM_ACCENT)]
                customAccentColor = custom?.toIntOrNull()
            }
        }
    }

    fun setAccent(value: AccentTheme) {
        accent = value
        customAccentColor = null
        val app = context ?: return
        scope.launch {
            runCatching {
                app.appearanceStore.edit {
                    it[stringPreferencesKey(KEY_ACCENT)] = value.key
                    it.remove(stringPreferencesKey(KEY_CUSTOM_ACCENT))
                }
            }
        }
    }

    fun setCustomAccent(color: Int) {
        customAccentColor = color
        val app = context ?: return
        scope.launch {
            runCatching {
                app.appearanceStore.edit { it[stringPreferencesKey(KEY_CUSTOM_ACCENT)] = color.toString() }
            }
        }
    }

    fun accentColor(): Int = customAccentColor ?: accent.previewColor

    fun setMode(value: ThemeMode) {
        mode = value
        val app = context ?: return
        scope.launch {
            runCatching {
                app.appearanceStore.edit { it[stringPreferencesKey(KEY_MODE)] = value.key }
            }
        }
    }
}
