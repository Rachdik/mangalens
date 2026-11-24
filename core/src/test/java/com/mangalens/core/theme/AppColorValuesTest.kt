package com.mangalens.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppColorValuesTest {

    @Test
    fun defaultValues_haveSevenRoles() {
        val defaults = AppColorValues()
        val roles = listOf(
            defaults.primary,
            defaults.background,
            defaults.surface,
            defaults.text,
            defaults.secondaryText,
            defaults.floatingButton,
            defaults.overlayBackground
        )
        assertEquals(7L, roles.size.toLong())
    }

    @Test
    fun defaultValues_areLightThemeDefaults() {
        val d = AppColorValues()
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), d.primary.toLong())
        assertEquals(AppColorValues.LIGHT_BACKGROUND.toLong(), d.background.toLong())
        assertEquals(AppColorValues.LIGHT_SURFACE.toLong(), d.surface.toLong())
        assertEquals(AppColorValues.LIGHT_TEXT.toLong(), d.text.toLong())
        assertEquals(AppColorValues.LIGHT_SECONDARY_TEXT.toLong(), d.secondaryText.toLong())
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), d.floatingButton.toLong())
        assertEquals(AppColorValues.LIGHT_BACKGROUND.toLong(), d.overlayBackground.toLong())
    }

    @Test
    fun defaultValues_surface_isWhite() {
        val d = AppColorValues()
        assertEquals(-1L, d.surface.toLong())
    }

    @Test
    fun defaultValues_text_isDark() {
        val d = AppColorValues()
        assertEquals(-15263963L, d.text.toLong())
    }

    @Test
    fun parseHex_6digit_valid() {
        assertEquals(5195493L, AppColorValues.parseHex("4F46E5").toLong())
        assertEquals(0L, AppColorValues.parseHex("000000").toLong())
    }

    @Test
    fun parseHex_6digit_withHash_fails() {
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), AppColorValues.parseHex("#4F46E5").toLong())
    }

    @Test
    fun parseHex_5digit_and_7digit_withinRange() {
        assertEquals(74565L, AppColorValues.parseHex("12345").toLong())
        assertEquals(19088743L, AppColorValues.parseHex("1234567").toLong())
    }

    @Test
    fun parseHex_shortLength() {
        // "123" = 0x123 = 291 within int range
        assertEquals(291L, AppColorValues.parseHex("123").toLong())
    }

    @Test
    fun parseHex_longLength_exceedsInt() {
        // 9 digit "123456789" → parseInt throws
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), AppColorValues.parseHex("123456789").toLong())
    }

    @Test
    fun parseHex_null_blank_invalidChars() {
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), AppColorValues.parseHex(null).toLong())
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), AppColorValues.parseHex("").toLong())
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), AppColorValues.parseHex("   ").toLong())
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), AppColorValues.parseHex("XYZ").toLong())
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), AppColorValues.parseHex("12345G").toLong())
        assertEquals(AppColorValues.LIGHT_PRIMARY.toLong(), AppColorValues.parseHex("##123456").toLong())
    }

    @Test
    fun isValidHex_6digit() {
        assertTrue(AppColorValues.isValidHex("4F46E5"))
        assertTrue(AppColorValues.isValidHex("ABCDEF"))
        assertTrue(AppColorValues.isValidHex("000000"))
        assertTrue(AppColorValues.isValidHex("FFFFFF"))
    }

    @Test
    fun isValidHex_8digit() {
        assertTrue(AppColorValues.isValidHex("FF4F46E5"))
        assertTrue(AppColorValues.isValidHex("FF000000"))
        assertTrue(AppColorValues.isValidHex("ff0000"))
    }

    @Test
    fun isValidHex_rejectsWrongLength() {
        assertFalse(AppColorValues.isValidHex(""))
        assertFalse(AppColorValues.isValidHex("123"))
        assertFalse(AppColorValues.isValidHex("12345"))
        assertFalse(AppColorValues.isValidHex("123456789"))
    }

    @Test
    fun isValidHex_rejectsInvalidChars() {
        assertFalse(AppColorValues.isValidHex("12345G"))
        assertFalse(AppColorValues.isValidHex("zzzzzz"))
        assertFalse(AppColorValues.isValidHex("##123456"))
    }

    @Test
    fun isValidHex_rejectsHashPrefix() {
        assertFalse(AppColorValues.isValidHex("#FFFFFF"))
        assertFalse(AppColorValues.isValidHex("#4F46E5"))
    }

    @Test
    fun intToHex_formatsCorrectly() {
        assertEquals("FF4F46E5", AppColorValues.intToHex(0xFF4F46E5.toInt()))
        assertEquals("FF000000", AppColorValues.intToHex(0xFF000000.toInt()))
        assertEquals("FFFFFFFF", AppColorValues.intToHex(0xFFFFFFFF.toInt()))
        assertEquals("00000000", AppColorValues.intToHex(0))
    }

    @Test
    fun copy_preservesUnchangedRoles() {
        val original = AppColorValues()
        val modified = original.copy(primary = 0xFF000000.toInt())
        assertEquals(-16777216L, modified.primary.toLong())
        assertEquals(original.background.toLong(), modified.background.toLong())
        assertEquals(original.surface.toLong(), modified.surface.toLong())
        assertEquals(original.text.toLong(), modified.text.toLong())
        assertEquals(original.secondaryText.toLong(), modified.secondaryText.toLong())
        assertEquals(original.floatingButton.toLong(), modified.floatingButton.toLong())
        assertEquals(original.overlayBackground.toLong(), modified.overlayBackground.toLong())
    }
}
