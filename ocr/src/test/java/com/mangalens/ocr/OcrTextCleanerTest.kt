package com.mangalens.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextCleanerTest {

    @Test
    fun numbersAndPunctuation_arePreserved() {
        assertEquals("Chapter 12: 100%!", OcrTextCleaner.clean("  Chapter   12:   100%!  "))
        assertEquals("待って… 2人", OcrTextCleaner.clean("待って…   2人"))
    }

    @Test
    fun blankAndSymbolOnlyLines_areDropped() {
        assertEquals("Halo\nDunia", OcrTextCleaner.clean("Halo\n\n---\nDunia\n"))
    }

    @Test
    fun invisibleCharacters_areRemoved() {
        val raw = "A\u200Bb\uFEFFc"
        assertEquals("Abc", OcrTextCleaner.clean(raw))
    }

    @Test
    fun whitespaceVariants_collapseToSingleSpace() {
        assertEquals("satu dua", OcrTextCleaner.clean("satu\u00A0\u3000dua"))
    }

    @Test
    fun negativeNumbers_areNotAltered() {
        assertEquals("-12.5 + 3", OcrTextCleaner.clean("-12.5   + 3"))
    }
}
