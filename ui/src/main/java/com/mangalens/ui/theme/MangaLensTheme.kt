package com.mangalens.ui.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import com.mangalens.core.theme.AccentTheme
import com.mangalens.core.theme.ThemeMode
import com.mangalens.core.theme.ThemeStore
import com.mangalens.ui.R

/**
 * Pemetaan terpusat dari pilihan pengguna ke style Android.
 *
 * Activity memanggil [apply] SEBELUM `super.onCreate` agar tema sudah benar saat
 * layout di-inflate, dan [applySystemBars] setelah `setContentView` untuk
 * menyesuaikan warna ikon status bar.
 */
object MangaLensTheme {

    fun styleFor(accent: AccentTheme): Int = when (accent) {
        AccentTheme.PURPLE -> R.style.Theme_MangaLens_Purple
        AccentTheme.BLUE -> R.style.Theme_MangaLens_Blue
        AccentTheme.CYAN -> R.style.Theme_MangaLens_Cyan
        AccentTheme.GREEN -> R.style.Theme_MangaLens_Green
        AccentTheme.RED -> R.style.Theme_MangaLens_Red
        AccentTheme.ORANGE -> R.style.Theme_MangaLens_Orange
        AccentTheme.PINK -> R.style.Theme_MangaLens_Pink
        AccentTheme.TEAL -> R.style.Theme_MangaLens_Teal
    }

    fun nightModeFor(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Terapkan mode malam global + style aksen untuk Activity ini. */
    fun apply(activity: Activity) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(ThemeStore.mode))
        activity.setTheme(styleFor(ThemeStore.accent))
    }

    /** Simpan pilihan lalu terapkan ulang (Activity dibuat ulang oleh pemanggil). */
    fun select(accent: AccentTheme, mode: ThemeMode) {
        ThemeStore.setAccent(accent)
        ThemeStore.setMode(mode)
        AppCompatDelegate.setDefaultNightMode(nightModeFor(mode))
    }

    /** Ikon status bar gelap di mode terang, terang di mode gelap. */
    fun applySystemBars(activity: Activity) {
        val night = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        runCatching {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars = !night
        }
    }
}
