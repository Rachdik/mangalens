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
import java.util.Locale

/**
 * Peran warna lanjutan yang dikustomisasi pengguna.
 *
 * Ketujuh peran ini mencakup seluruh elemen UI yang pernah butuh warna
 * terpisah dari aksen tema: tombol utama, latar jendela, permukaan kartu,
 * teks utama, teks sekunder, tombol floating, dan latar overlay terjemahan.
 */
enum class AppColorRole {
    PRIMARY,
    BACKGROUND,
    SURFACE,
    TEXT,
    SECONDARY_TEXT,
    FLOATING_BUTTON,
    OVERLAY_BACKGROUND
}

/**
 * Nilai ARGB untuk ketujuh peran.
 *
 * Default adalah WARNA NETRAL tema (bukan aksen) sehingga aplikasi tetap
 * membaca baik di Light dan Dark. Pengguna boleh mengubah semuanya.
 *
 * Format penyimpanan: string HEX 8-karakter uppercase tanpa tanda pound,
 * mis. "FF4F46E5". Alpha selalu FF (opaque) karena warna UI aplikasi
 * seharusnya tidak transparan.
 */
data class AppColorValues(
    val primary: Int = LIGHT_PRIMARY,
    val background: Int = LIGHT_BACKGROUND,
    val surface: Int = LIGHT_SURFACE,
    val text: Int = LIGHT_TEXT,
    val secondaryText: Int = LIGHT_SECONDARY_TEXT,
    val floatingButton: Int = LIGHT_PRIMARY,
    val overlayBackground: Int = LIGHT_BACKGROUND
) {
    companion object {
        /** Warna Light mode — netral, bukan aksen. */
        const val LIGHT_PRIMARY = 0xFF4F46E5.toInt()
        const val LIGHT_BACKGROUND = 0xFFF5F6FA.toInt()
        const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
        const val LIGHT_TEXT = 0xFF171725.toInt()
        const val LIGHT_SECONDARY_TEXT = 0xFF6B6B7B.toInt()

        /** Warna Dark mode — netral, bukan aksen. */
        const val DARK_PRIMARY = 0xFF818CF8.toInt()
        const val DARK_BACKGROUND = 0xFF0F1116.toInt()
        const val DARK_SURFACE = 0xFF191C22.toInt()
        const val DARK_TEXT = 0xFFECEDF2.toInt()
        const val DARK_SECONDARY_TEXT = 0xFF9AA0AE.toInt()

        fun isDarkMode(context: Context): Boolean =
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        fun defaultsFor(context: Context): AppColorValues {
            return if (isDarkMode(context)) {
                AppColorValues(
                    primary = DARK_PRIMARY,
                    background = DARK_BACKGROUND,
                    surface = DARK_SURFACE,
                    text = DARK_TEXT,
                    secondaryText = DARK_SECONDARY_TEXT,
                    floatingButton = DARK_PRIMARY,
                    overlayBackground = DARK_BACKGROUND
                )
            } else {
                AppColorValues()
            }
        }

        /** Parse string HEX menjadi ARGB. Nilai kosong/invalid tetap valid: */

        fun parseHex(value: String?): Int =
            if (!value.isNullOrBlank()) {
                runCatching { Integer.parseInt(value.trim(), 16) }
                    .getOrDefault(LIGHT_PRIMARY)
            } else {
                LIGHT_PRIMARY
            }

        /**
         * Validasi HEX: benar-benar 6 atau 8 digit heksadesimal.
         * Keluarkan true hanya bila format cocok, tanpa memandang nilainya.
         */

        fun isValidHex(value: String): Boolean {
            if (value.isNullOrBlank()) return false
            val trimmed = value.trim()
            if (trimmed.length !in 6..8) return false
            val digits = if (trimmed.length == 8) trimmed else "FF$trimmed"
            return digits.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
        }

        /** Format ARGB ke string HEX 8 digit uppercase. */

        fun intToHex(value: Int): String =
            String.format(Locale.US, "%08X", value.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * Penyimpanan warna lanjutan terpusat.
 *
 * Arsitektur: ThemeDefaults → AppColorStore Overrides → UI.
 * - ThemeDefaults: warna netral tema Light/Dark/System (bukan aksen).
 * - AppColorStore hanya simpan override pengguna; bila belum ada override
 *   gunakan default tema aktif.
 * - Perubahan tema tidak menghapus override yang sudah tersimpan.
 * - Reset per-warna mengembalikan role tersebut ke default tema.
 * - Reset semua menghapus seluruh override.
 *
 * Reader dan Floating membaca dari sini via bridge terpisah
 * (ReaderAppColors / FloatingAppColors) agar tidak ada penyimpanan
 * warna per Activity.
 */

object AppColorStore {

    private const val PREFS = "app_color_roles"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var context: Context? = null

    @Volatile
    private var values = AppColorValues()

    /** Inisialisasi: baca override yang tersimpan, jangan timpa dengan default. */

    fun init(context: Context) {
        val app = context.applicationContext
        this.context = app
        runBlocking {
            runCatching {
                val prefs = app.colorRoleStore.data.first()
                values = AppColorValues(
                    primary = parseStored(prefs[stringPreferencesKey(AppColorRole.PRIMARY.name)]),
                    background = parseStored(prefs[stringPreferencesKey(AppColorRole.BACKGROUND.name)]),
                    surface = parseStored(prefs[stringPreferencesKey(AppColorRole.SURFACE.name)]),
                    text = parseStored(prefs[stringPreferencesKey(AppColorRole.TEXT.name)]),
                    secondaryText = parseStored(prefs[stringPreferencesKey(AppColorRole.SECONDARY_TEXT.name)]),
                    floatingButton = parseStored(prefs[stringPreferencesKey(AppColorRole.FLOATING_BUTTON.name)]),
                    overlayBackground = parseStored(prefs[stringPreferencesKey(AppColorRole.OVERLAY_BACKGROUND.name)])
                )
            }
        }
    }

    fun get(): AppColorValues = values

    /**
     * Set warna untuk satu peran.
     *
     * Nilai disimpan sebagai HEX; bila nilai tidak valid (di luar ARGB) nilai
     * tetap disimpan tapi dibatasi ke rentang yang aman.
     */

    fun set(role: AppColorRole, value: Int) {
        val clamped = value and 0xFFFFFF.toInt() or 0xFF000000.toInt()
        values = when (role) {
            AppColorRole.PRIMARY -> values.copy(primary = clamped)
            AppColorRole.BACKGROUND -> values.copy(background = clamped)
            AppColorRole.SURFACE -> values.copy(surface = clamped)
            AppColorRole.TEXT -> values.copy(text = clamped)
            AppColorRole.SECONDARY_TEXT -> values.copy(secondaryText = clamped)
            AppColorRole.FLOATING_BUTTON -> values.copy(floatingButton = clamped)
            AppColorRole.OVERLAY_BACKGROUND -> values.copy(overlayBackground = clamped)
        }
        val app = context ?: return
        scope.launch {
            runCatching {
                app.colorRoleStore.edit { prefs ->
                    prefs[stringPreferencesKey(role.name)] = AppColorValues.intToHex(clamped)
                }
            }
        }
    }

    /**
     * Reset satu peran ke default tema saat ini.
     * Override untuk role lain tetap utuh.
     */

    fun resetRole(role: AppColorRole) {
        val defaults = currentDefaults()
        values = when (role) {
            AppColorRole.PRIMARY -> values.copy(primary = defaults.primary)
            AppColorRole.BACKGROUND -> values.copy(background = defaults.background)
            AppColorRole.SURFACE -> values.copy(surface = defaults.surface)
            AppColorRole.TEXT -> values.copy(text = defaults.text)
            AppColorRole.SECONDARY_TEXT -> values.copy(secondaryText = defaults.secondaryText)
            AppColorRole.FLOATING_BUTTON -> values.copy(floatingButton = defaults.floatingButton)
            AppColorRole.OVERLAY_BACKGROUND -> values.copy(overlayBackground = defaults.overlayBackground)
        }
        val app = context ?: return
        scope.launch {
            runCatching {
                app.colorRoleStore.edit { prefs ->
                    prefs.remove(stringPreferencesKey(role.name))
                }
            }
        }
    }

    /**
     * Hapus seluruh override dan kembalikan ke default tema saat ini.
     */

    fun resetAll() {
        values = currentDefaults()
        val app = context ?: return
        scope.launch {
            runCatching {
                app.colorRoleStore.edit { it.clear() }
            }
        }
    }

    /**
     * Default tema saat ini untuk inisialisasi ulang/in-memory.
     * Dipanggil saat theme change dan saat nilai belum tersimpan.
     */

    private fun currentDefaults(): AppColorValues {
        val app = context ?: return AppColorValues()
        return AppColorValues.defaultsFor(app)
    }

    private fun parseStored(value: String?): Int =
        if (!value.isNullOrBlank()) {
            runCatching { Integer.parseInt(value.trim(), 16) }
                .getOrDefault(currentDefaults().primary)
        } else {
            currentDefaults().primary
        }

    private val Context.colorRoleStore by preferencesDataStore(name = PREFS)
}
