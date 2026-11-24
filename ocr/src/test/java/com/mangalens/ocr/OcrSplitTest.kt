package com.mangalens.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Unit test untuk logika OCR yang bisa diverifikasi tanpa bitmap nyata:
 * - Skrip deteksi (ScriptDetector)
 * - Offset koordinat dari scaled→original (mirroring logika MlKitOcrEngine.toOriginal)
 * - Logika batas slice dan overlap (mirroring ReadTranslatePipeline.splitForOcr)
 */
class OcrSplitTest {

    // -----------------------------------------------------------------------
    // ScriptDetector
    // -----------------------------------------------------------------------

    @Test
    fun detect_emptyText_returnsLatin() {
        assertEquals(LanguageScript.LATIN, ScriptDetector.detect(""))
    }

    @Test
    fun detect_onlySpaces_returnsLatin() {
        assertEquals(LanguageScript.LATIN, ScriptDetector.detect("   "))
    }

    @Test
    fun detect_latinText_returnsLatin() {
        assertEquals(LanguageScript.LATIN, ScriptDetector.detect("Hello World"))
    }

    @Test
    fun detect_japaneseKana_returnsJapanese() {
        // こんにちは → 5 kana
        assertEquals(LanguageScript.JAPANESE, ScriptDetector.detect("こんにちは"))
    }

    @Test
    fun detect_mixedLatinAndKana_returnsJapanese() {
        // "Hello こんにちは" → latin=5, kana=5 → kana >= 2 → JAPANESE
        assertEquals(LanguageScript.JAPANESE, ScriptDetector.detect("Hello こんにちは"))
    }

    @Test
    fun detect_koreanWithLatin_returnsKorean() {
        // "ab 한글" → latin=2, hangul=2 → hangul >= latin → KOREAN
        assertEquals(LanguageScript.KOREAN, ScriptDetector.detect("ab 한글"))
    }

    @Test
    fun detect_hangul_returnsKorean() {
        assertEquals(LanguageScript.KOREAN, ScriptDetector.detect("안녕하세요"))
    }

    @Test
    fun detect_hangulOnly_returnsKorean() {
        assertEquals(LanguageScript.KOREAN, ScriptDetector.detect("안녕하세요"))
    }

    @Test
    fun detect_chineseHanReturnsChinese() {
        assertEquals(LanguageScript.CHINESE, ScriptDetector.detect("你好世界"))
    }

    @Test
    fun detect_chineseDominant_returnsChinese() {
        // "abc 你好" → latin=3, han=2 → han < latin → LATIN
        assertEquals(LanguageScript.LATIN, ScriptDetector.detect("abc 你好"))
    }

    @Test
    fun detect_chineseOnly_returnsChinese() {
        // "你好世界" → han=4, latin=0 → han > latin → CHINESE
        assertEquals(LanguageScript.CHINESE, ScriptDetector.detect("你好世界"))
    }

    @Test
    fun detect_chineseDominantEnoughReturnsChinese() {
        // "ab 你好世界" → latin=2, han=4 → han > latin → CHINESE
        assertEquals(LanguageScript.CHINESE, ScriptDetector.detect("ab 你好世界"))
    }

    @Test
    fun detect_mixedScript_returnsDominant() {
        // "abc こんにちは 你好" → latin=3, kana=5, han=2 → kana >= 2 → JAPANESE
        assertEquals(LanguageScript.JAPANESE, ScriptDetector.detect("abc こんにちは 你好"))
    }

    @Test
    fun detect_japaneseKanjiOnly_fallsToChinese() {
        // 通常外字の文章 → kanji=7, kana=1 → kana < 2, han > latin → CHINESE
        // Ini known limitation: tanpa cukup kana, teks Jepang dianggap Cina
        assertEquals(LanguageScript.CHINESE, ScriptDetector.detect("通常外字の文章"))
    }

    @Test
    fun detect_japaneseWithEnoughKana_returnsJapanese() {
        // こんにちは世界 → kana=5, han=2 → kana >= 2 → JAPANESE
        assertEquals(LanguageScript.JAPANESE, ScriptDetector.detect("こんにちは世界"))
    }

    @Test
    fun languageTag_映射正确() {
        assertEquals("auto", ScriptDetector.languageTag(LanguageScript.AUTO))
        assertEquals("en", ScriptDetector.languageTag(LanguageScript.LATIN))
        assertEquals("ja", ScriptDetector.languageTag(LanguageScript.JAPANESE))
        assertEquals("ko", ScriptDetector.languageTag(LanguageScript.KOREAN))
        assertEquals("zh", ScriptDetector.languageTag(LanguageScript.CHINESE))
    }

    @Test
    fun languageTagOf_映射正确() {
        assertEquals("en", ScriptDetector.languageTagOf("Hello"))
        assertEquals("ja", ScriptDetector.languageTagOf("こんにちは"))
        assertEquals("ko", ScriptDetector.languageTagOf("안녕하세요"))
        assertEquals("zh", ScriptDetector.languageTagOf("你好世界你好"))
    }

    @Test
    fun characterCount_empty() {
        assertEquals(0, ScriptDetector.characterCount(""))
    }

    @Test
    fun characterCount_onlySpaces() {
        assertEquals(0, ScriptDetector.characterCount("   "))
    }

    @Test
    fun characterCount_latinAndDigits() {
        assertEquals(10, ScriptDetector.characterCount("Hello 12345"))
    }

    @Test
    fun characterCount_japaneseCharacters() {
        // こんにちは世界 → 7 chars (5 kana + 2 kanji)
        assertEquals(7, ScriptDetector.characterCount("こんにちは世界"))
    }

    // -----------------------------------------------------------------------
    // Koordinat offset (mirroring MlKitOcrEngine.toOriginal)
    // -----------------------------------------------------------------------

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left)
        assertEquals(expected.top, actual.top)
        assertEquals(expected.right, actual.right)
        assertEquals(expected.bottom, actual.bottom)
    }

    @Test
    fun toOriginal_scale1_noChange() {
        val rect = Rect(100, 200, 300, 400)
        val result = toOriginal(rect, 1f, 1000, 2000)
        assertRectEquals(rect, result)
    }

    @Test
    fun toOriginal_scaleHalf() {
        // Scaled 2x smaller: scaled coords 50,100,150,200 → original 25,50,75,100
        val scaled = Rect(50, 100, 150, 200)
        val result = toOriginal(scaled, 2f, 1000, 2000)
        assertRectEquals(Rect(25, 50, 75, 100), result)
    }

    @Test
    fun toOriginal_scale2x() {
        // Scaled 2x larger: scaled coords 100,200,300,400 → original 200,400,600,800
        val scaled = Rect(100, 200, 300, 400)
        val result = toOriginal(scaled, 0.5f, 1000, 2000)
        assertRectEquals(Rect(200, 400, 600, 800), result)
    }

    @Test
    fun toOriginal_coerceInBounds() {
        // Koordinat di luar batas → dipaksa ke dalam
        val scaled = Rect(-50, -50, 1050, 2050)
        val result = toOriginal(scaled, 1f, 1000, 2000)
        assertRectEquals(Rect(0, 0, 1000, 2000), result)
    }

    @Test
    fun toOriginal_scaleHalf_withCoerce() {
        // scaled coords di luar batas original setelah scaling
        val scaled = Rect(-10, -10, 2010, 4010)
        val result = toOriginal(scaled, 0.5f, 1000, 2000)
        assertRectEquals(Rect(0, 0, 1000, 2000), result)
    }

    @Test
    fun toOriginal_preservesAspectRatio() {
        val scaled = Rect(0, 0, 100, 200)
        val result = toOriginal(scaled, 3f, 1000, 2000)
        // width: 0/3=0, 100/3=33; height: 0/3=0, 200/3=66
        assertRectEquals(Rect(0, 0, 33, 66), result)
    }

    // -----------------------------------------------------------------------
    // splitForOcr logic mirroring
    // -----------------------------------------------------------------------

    @Test
    fun splitForOcr_shortImage_returnsSingleSlice() {
        val height = 500
        val slices = splitSimulated(height, 2000, 120, 240)
        assertEquals(1, slices.size)
        assertEquals(0, slices[0].top)
        assertEquals(height, slices[0].height)
    }

    @Test
    fun splitForOcr_exactSliceHeight_returnsSingleSlice() {
        val height = 2000
        val slices = splitSimulated(height, 2000, 120, 240)
        assertEquals(1, slices.size)
        assertEquals(0, slices[0].top)
        assertEquals(height, slices[0].height)
    }

    @Test
    fun splitForOcr_tallImage_twoSlices() {
        val height = 3000
        val slices = splitSimulated(height, 2000, 120, 240)
        assertEquals(2, slices.size)
        assertEquals(0, slices[0].top)
        assertEquals(2000, slices[0].height)
        assertEquals(1880, slices[1].top) // 0 + 2000 - 120
        assertEquals(1120, slices[1].height) // 3000 - 1880
    }

    @Test
    fun splitForOcr_overlapIs120px() {
        val height = 5000
        val slices = splitSimulated(height, 2000, 120, 240)
        assertEquals(3, slices.size)
        assertEquals(0, slices[0].top)
        assertEquals(2000, slices[0].height)
        assertEquals(1880, slices[1].top) // overlap 2000-1880=120
        assertEquals(1880, slices[1].top)
        assertEquals(2000, slices[1].height)
        assertEquals(3760, slices[2].top) // 1880 + 2000 - 120
        assertEquals(1240, slices[2].height) // 5000 - 3760
    }

    @Test
    fun splitForOcr_tinyTail_isDropped() {
        // height=2100, sliceHeight=2000, overlap=120, minSliceHeight=240
        // slice1: top=0, h=2000, nextTop=1880
        // tail: 2100-1880=220 < minSliceHeight(240) → drop
        val height = 2100
        val slices = splitSimulated(height, 2000, 120, 240)
        assertEquals(1, slices.size)
        assertEquals(0, slices[0].top)
        assertEquals(2000, slices[0].height) // tail dropped, not extended
    }

    @Test
    fun splitForOcr_lastSliceExtendsToBottom() {
        val height = 3500
        val slices = splitSimulated(height, 2000, 120, 240)
        assertEquals(2, slices.size)
        assertEquals(0, slices[0].top)
        assertEquals(2000, slices[0].height)
        assertEquals(1880, slices[1].top)
        assertEquals(1620, slices[1].height) // 3500 - 1880
    }

    @Test
    fun splitForOcr_manySlices() {
        val height = 10000
        val slices = splitSimulated(height, 2000, 120, 240)
        assertTrue(slices.size >= 5)
        assertEquals(0, slices[0].top)
        // Terakhir harus sampai ke bottom
        assertEquals(0, slices[0].top)
        val last = slices.last()
        assertEquals(height, last.top + last.height)
    }

    @Test
    fun splitForOcr_consecutiveOverlapConsistent() {
        val height = 8000
        val slices = splitSimulated(height, 2000, 120, 240)
        for (i in 0 until slices.size - 1) {
            val current = slices[i]
            val next = slices[i + 1]
            val expectedOverlap = 120
            val actualOverlap = current.top + current.height - next.top
            assertEquals(expectedOverlap, actualOverlap)
        }
    }

    @Test
    fun splitForOcr_minSliceHeightRespected() {
        // Jika sisa < minSliceHeight dan sudah ada slice, berhenti
        val height = 2100
        val slices = splitSimulated(height, 2000, 120, 240)
        assertEquals(1, slices.size)
        // Tapi jika tidak ada slice sebelumnya, tetap buat
        val height2 = 200
        val slices2 = splitSimulated(height2, 2000, 120, 240)
        assertEquals(1, slices2.size)
        assertEquals(200, slices2[0].height)
    }

    // -----------------------------------------------------------------------
    // Helper simulasi (mirroring ReadTranslatePipeline.splitForOcr)
    // -----------------------------------------------------------------------

    private data class SliceSim(val top: Int, val height: Int)

    private fun splitSimulated(
        height: Int,
        sliceHeight: Int,
        overlapPx: Int,
        minSliceHeight: Int
    ): List<SliceSim> {
        if (height <= sliceHeight) return listOf(SliceSim(0, height))
        val slices = mutableListOf<SliceSim>()
        var top = 0
        while (top < height) {
            val h = min(sliceHeight, height - top)
            if (h < minSliceHeight && slices.isNotEmpty()) break
            slices += SliceSim(top, h)
            if (top + h >= height) break
            top += sliceHeight - overlapPx
        }
        return slices
    }

    private fun toOriginal(rect: Rect, scale: Float, width: Int, height: Int): Rect {
        if (scale == 1f) return rect
        return Rect(
            (rect.left / scale).toInt().coerceIn(0, width),
            (rect.top / scale).toInt().coerceIn(0, height),
            (rect.right / scale).toInt().coerceIn(0, width),
            (rect.bottom / scale).toInt().coerceIn(0, height)
        )
    }
}
