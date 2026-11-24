package com.mangalens.app

import android.widget.LinearLayout
import com.mangalens.core.theme.AppColorRole
import com.mangalens.core.theme.AppColorStore

/**
 * Integrasi pengaturan warna lanjutan ke SettingsActivity.
 *
 * Dipanggil sekali di onCreate untuk menambahkan kartu warna di bawah
 * bagian tampilan. Kartu ini memakai AdvancedColorSettingsBuilder.
 */

object SettingsActivityAdvancedColors {

    private val roles = listOf(
        AppColorRole.PRIMARY to "Warna utama",
        AppColorRole.BACKGROUND to "Latar jendela",
        AppColorRole.SURFACE to "Permukaan kartu",
        AppColorRole.TEXT to "Teks utama",
        AppColorRole.SECONDARY_TEXT to "Teks sekunder",
        AppColorRole.FLOATING_BUTTON to "Tombol floating",
        AppColorRole.OVERLAY_BACKGROUND to "Latar overlay terjemahan"
    )

    fun build(activity: SettingsActivity, parent: LinearLayout) {
        val builder = AdvancedColorSettingsBuilder(activity)
        for ((role, label) in roles) {
            builder.build(role, label, parent)
        }
        builder.buildResetAllButton(parent)
    }
}
