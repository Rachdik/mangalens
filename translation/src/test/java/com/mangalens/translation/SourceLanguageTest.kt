package com.mangalens.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLanguageTest {

    @Test
    fun japaneseKana_detectedAsJapanese() {
        assertEquals("ja", SourceLanguage.detect("まって"))
        assertEquals("ja", SourceLanguage.detect("だめだ！"))
        assertEquals("ja", SourceLanguage.detect("そうか… 123"))
    }

    @Test
    fun koreanHangul_detectedAsKorean() {
        assertEquals("ko", SourceLanguage.detect("기다려"))
    }

    @Test
    fun hanOnly_detectedAsChinese() {
        assertEquals("zh", SourceLanguage.detect("等等我"))
    }

    @Test
    fun latinText_detectedAsEnglish() {
        assertEquals("en", SourceLanguage.detect("WAIT FOR ME!"))
        assertEquals("en", SourceLanguage.detect("Chapter 12 - 2026"))
    }

    @Test
    fun numbersAlone_doNotBecomeAsianLanguage() {
        assertEquals("en", SourceLanguage.detect("12345 !!"))
    }

    @Test
    fun eachDialogDetectedIndependently() {
        // Halaman campuran: satu dialog Jepang, satu dialog Inggris.
        val items = listOf("待って", "LET'S GO", "だめだ")
        assertEquals(listOf("ja", "en", "ja"), items.map(SourceLanguage::detect))
    }

    @Test
    fun normalize_mapsCommonAliases() {
        assertEquals("ja", SourceLanguage.normalize("jp"))
        assertEquals("ko", SourceLanguage.normalize("kr"))
        assertEquals("zh", SourceLanguage.normalize("zh-CN"))
        assertEquals("id", SourceLanguage.normalize("in"))
        assertEquals(SourceLanguage.AUTO, SourceLanguage.normalize("AUTO"))
        assertTrue(SourceLanguage.isAuto("auto"))
    }
}
