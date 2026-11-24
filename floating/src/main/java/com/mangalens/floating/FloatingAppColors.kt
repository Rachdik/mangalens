package com.mangalens.floating

import com.mangalens.core.theme.AppColorRole
import com.mangalens.core.theme.AppColorStore
import com.mangalens.core.theme.AppColorValues

/**
 * Sumber warna terpusat untuk Floating bubble.
 *
 * Warna bubble dan panel mengikuti AppColorStore sehingga:
 * - Pengaturan warna di Settings langsung terlihat di bubble.
 * - Tidak ada konflik antara warna di MainActivity, Reader, dan Floating.
 * - Perubahan tema (Light/Dark) tidak memengaruhi warna yang disimpan.
 *
 * Warna yang dipakai:
 * - Tombol bubble → primary (warna aksen utama)
 * - Panel floating → surface
 * - Teks panel → text
 * - Teks sekunder → secondaryText
 */

object FloatingAppColors {

    val values: AppColorValues get() = AppColorStore.get()

    /** Warna tombol bubble dan panel header. */

    val primary: Int get() = values.primary

    /** Warna permukaan panel. */

    val surface: Int get() = values.surface

    /** Warna teks utama di panel. */

    val text: Int get() = values.text

    /** Warna teks sekunder/status di panel. */

    val secondaryText: Int get() = values.secondaryText

    /** Warna tombol floating bila dipakai elemen terpisah. */

    val floatingButton: Int get() = values.floatingButton

    /** Warna background overlay screencapture. */

    val overlayBackground: Int get() = values.overlayBackground
}
