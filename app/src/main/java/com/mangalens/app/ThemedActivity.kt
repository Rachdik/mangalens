package com.mangalens.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mangalens.ui.theme.MangaLensTheme

/**
 * Activity dasar yang menerapkan tema pilihan pengguna SEBELUM layout di-inflate.
 *
 * Tanpa ini, urutan `super.onCreate` → `setContentView` akan memakai tema default
 * dan warna baru hanya muncul setelah Activity dibuat ulang dua kali.
 */
open class ThemedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MangaLensTheme.apply(this)
        super.onCreate(savedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        MangaLensTheme.applySystemBars(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        MangaLensTheme.applySystemBars(this)
    }
}
