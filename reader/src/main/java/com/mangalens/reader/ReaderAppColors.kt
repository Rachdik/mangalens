package com.mangalens.reader

import android.graphics.Color
import com.mangalens.core.theme.AppColorRole
import com.mangalens.core.theme.AppColorStore
import com.mangalens.core.theme.AppColorValues

/**
 * Sumber warna terpusat untuk Reader.
 *
 * Reader tidak punya penyimpanan warna sendiri; seluruh warna overlay
 * terbaca dari AppColorStore sehingga pengaturan di Settings langsung
 * memengaruhi Reader dan Floating secara bersamaan.
 *
 * Warna yang dipakai:
 * - Overlay background → overlayBackground (tempat teks terjemahan)
 * - Teks utama → text (warna teks terjemahan bila tidak AUTO)
 * - Permukaan balon → surface
 */

object ReaderAppColors {

    /** Nilai terkini; dalam praktiksi selalu dibaca dari AppColorStore. */

    val values: AppColorValues get() = AppColorStore.get()

    /** Warna latar belakang area teks terjemahan. */

    val overlayBackground: Int get() = values.overlayBackground

    /** Warna teks overlay bila pengaturan teks bukan AUTO. */

    val textColor: Int get() = values.text

    /** Warna permukaan kartu/balon. */

    val surface: Int get() = values.surface

    /** Warna utama untuk elemen UI Reader bila diperlukan. */

    val primary: Int get() = values.primary

    /** Ubah warna latar overlay (dipanggil dari Settings bila ada kaitan). */

    fun setOverlayBackground(color: Int) {
        AppColorStore.set(AppColorRole.OVERLAY_BACKGROUND, color)
    }

    /** Ubah warna teks. */

    fun setTextColor(color: Int) {
        AppColorStore.set(AppColorRole.TEXT, color)
    }
}
