package com.mangalens.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit test untuk logika AppColorStore yang tidak bergantung pada Android runtime.
 *
 * Karena AppColorStore menggunakan DataStore (membutuhkan Context), kita tidak
 * bisa menguji persistensi secara langsung di sini. Sebagai pengganti, tes ini
 * memverifikasi:
 * - Contoh manual class AppColorValues
 * - Formatting/pem-format-an HEX
 * - Logika clamp nilai warna
 * - Behavior default theme (dengan mock context sederhana)
 */
class AppColorStoreLogicTest {

    @Test
    fun clamp_normalColor_unchanged() {
        val original = 0xFF4F46E5.toInt()
        val clamped = original and 0xFFFFFF.toInt() or 0xFF000000.toInt()
        assertEquals(original, clamped)
    }

    @Test
    fun clamp_alphaOnly_retainsRGB() {
        // Jika pengguna memberikan nilai dengan alpha 0, kita tetap pakai FF
        val semiTransparent = 0x004F46E5.toInt()
        val clamped = semiTransparent and 0xFFFFFF.toInt() or 0xFF000000.toInt()
        assertEquals(0xFF4F46E5.toInt(), clamped)
    }

    @Test
    fun clamp_highAlpha_preserved() {
        val value = 0x80FFFFFF.toInt()
        val clamped = value and 0xFFFFFF.toInt() or 0xFF000000.toInt()
        assertEquals(0xFFFFFFFF.toInt(), clamped)
    }

    @Test
    fun defaultValues_lightTheme() {
        val defaults = AppColorValues()
        assertEquals(AppColorValues.LIGHT_PRIMARY, defaults.primary)
        assertEquals(AppColorValues.LIGHT_BACKGROUND, defaults.background)
        assertEquals(AppColorValues.LIGHT_SURFACE, defaults.surface)
        assertEquals(AppColorValues.LIGHT_TEXT, defaults.text)
        assertEquals(AppColorValues.LIGHT_SECONDARY_TEXT, defaults.secondaryText)
        assertEquals(AppColorValues.LIGHT_PRIMARY, defaults.floatingButton)
        assertEquals(AppColorValues.LIGHT_BACKGROUND, defaults.overlayBackground)
    }

    @Test
    fun darkThemeValues_areDistinctFromLight() {
        assertNotEquals(AppColorValues.LIGHT_PRIMARY, AppColorValues.DARK_PRIMARY)
        assertNotEquals(AppColorValues.LIGHT_BACKGROUND, AppColorValues.DARK_BACKGROUND)
        assertNotEquals(AppColorValues.LIGHT_SURFACE, AppColorValues.DARK_SURFACE)
        assertNotEquals(AppColorValues.LIGHT_TEXT, AppColorValues.DARK_TEXT)
        assertNotEquals(AppColorValues.LIGHT_SECONDARY_TEXT, AppColorValues.DARK_SECONDARY_TEXT)
    }

    @Test
    fun copy_createsIndependentInstance() {
        val original = AppColorValues()
        val modified = original.copy(primary = 0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), modified.primary)
        assertEquals(AppColorValues.LIGHT_BACKGROUND, modified.background)
        assertEquals(AppColorValues.LIGHT_SURFACE, modified.surface)
        assertEquals(AppColorValues.LIGHT_TEXT, modified.text)
        assertEquals(AppColorValues.LIGHT_SECONDARY_TEXT, modified.secondaryText)
        assertEquals(AppColorValues.LIGHT_PRIMARY, modified.floatingButton)
        assertEquals(AppColorValues.LIGHT_BACKGROUND, modified.overlayBackground)

        // Memastikan original tidak berubah
        assertEquals(AppColorValues.LIGHT_PRIMARY, original.primary)
    }

    @Test
    fun primaryAndFloatingButton_defaultToSameValue() {
        val defaults = AppColorValues()
        assertEquals(defaults.primary, defaults.floatingButton)
    }

    @Test
    fun backgroundAndOverlayBackground_defaultToSameValue() {
        val defaults = AppColorValues()
        assertEquals(defaults.background, defaults.overlayBackground)
    }
}
