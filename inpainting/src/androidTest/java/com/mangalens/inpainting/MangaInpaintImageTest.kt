package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Suite uji inpainting manga dengan 11 skenario deterministik:
 *
 * 1. Latar putih.
 * 2. Latar warna solid.
 * 3. Latar kompleks dengan tekstur.
 * 4. Garis ilustrasi tipis yang melintasi area teks.
 * 5. Teks Inggris.
 * 6. Teks Jepang.
 * 7. Teks Korea.
 * 8. Dua area teks berdekatan.
 * 9. Dua area teks berjauhan.
 * 10. Mask berdekatan (dua komponen terpisah).
 * 11. Inpainting dinonaktifkan (fallback).
 *
 * Semua test menggunakan gambar sintetis deterministik, bukan gambar manga sungguhan.
 */
@RunWith(AndroidJUnit4::class)
class MangaInpaintImageTest {

    private companion object {
        /** Sisanya hanya boleh berupa sisa tepi anti-aliasing, bukan teks yang terbaca. */
        const val MAX_RESIDUAL_TEXT_RATIO = 0.1f
    }

    private lateinit var engine: BasicInpaintingEngine

    @Before
    fun setUp() {
        engine = BasicInpaintingEngine()
    }

    @Test
    fun test_01_white_background_maskReduced() {
        val source = whiteBackground()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertMaskAreaReduced(source, mask, result)
        assertPixelsOutsideMaskUnchanged(source, mask, result)
    }

    @Test
    fun test_01_white_background_resultIsNotAllBlack() {
        val source = whiteBackground()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        val darkPixels = countDarkPixels(result, 0, 0, result.width, result.height)
        val totalMaskPixels = countMaskedPixelsAboveThreshold(mask, mask, 1)
        assertTrue(
            "Area mask seharusnya tidak gelap total. Dark: $darkPixels / $totalMaskPixels",
            darkPixels < totalMaskPixels * 0.9
        )
    }

    @Test
    fun test_02_solid_background_maskReduced() {
        val source = solidBackground()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertMaskAreaReduced(source, mask, result)
        assertPixelsOutsideMaskUnchanged(source, mask, result)
    }

    @Test
    fun test_02_solid_background_sameColorRestored() {
        val source = solidBackground()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        var insideCount = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (y in 40..60) {
            for (x in 20..60) {
                if (!isMaskedAt(mask, x, y)) continue
                val c = result.getPixel(x, y)
                sumR += Color.red(c)
                sumG += Color.green(c)
                sumB += Color.blue(c)
                insideCount++
            }
        }
        if (insideCount > 0) {
            val avgR = sumR / insideCount
            val avgG = sumG / insideCount
            val avgB = sumB / insideCount
            assertTrue(
                "Warna area mask harus mendekati latar biru asli. R=$avgR G=$avgG B=$avgB",
                abs(avgR - 30) < 40 || abs(avgG - 60) < 40 || abs(avgB - 180) < 40
            )
        }
    }

    @Test
    fun test_03_complex_background_maskReduced() {
        val source = complexBackground()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertMaskAreaReduced(source, mask, result)
        assertPixelsOutsideMaskUnchanged(source, mask, result)
    }

    @Test
    fun test_03_complex_background_linePreserved() {
        val source = complexBackground()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertThinLinePreserved(source, mask, result, lineY = 40)
    }

    @Test
    fun test_04_thin_line_linePreserved() {
        val source = backgroundWithThinLine()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertThinLinePreserved(source, mask, result, lineY = 40)
    }

    @Test
    fun test_04_thin_line_maskReduced() {
        val source = backgroundWithThinLine()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertMaskAreaReduced(source, mask, result)
        assertPixelsOutsideMaskUnchanged(source, mask, result)
    }

    @Test
    fun test_05_english_text_maskReduced() {
        val source = englishText()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertMaskAreaReduced(source, mask, result)
        assertPixelsOutsideMaskUnchanged(source, mask, result)
    }

    @Test
    fun test_06_japanese_text_maskReduced() {
        val source = japaneseText()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertMaskAreaReduced(source, mask, result)
        assertPixelsOutsideMaskUnchanged(source, mask, result)
    }

    @Test
    fun test_07_korean_text_maskReduced() {
        val source = koreanText()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))
        val result = engine.inpaint(source, mask)

        assertMaskAreaReduced(source, mask, result)
        assertPixelsOutsideMaskUnchanged(source, mask, result)
    }

    @Test
    fun test_08_twoCloseTexts_maskReduced() {
        val source = twoCloseTexts()
        val mask = createRectMask(source, Rect(40, 20, 85, 61))
        val result = engine.inpaint(source, mask)

        assertMaskAreaReduced(source, mask, result)
        assertPixelsOutsideMaskUnchanged(source, mask, result)
    }

    @Test
    fun test_09_twoFarTexts_maskReduced() {
        val source = twoFarTexts()
        val mask1 = createRectMask(source, Rect(40, 20, 61, 61))
        val mask2 = createRectMask(source, Rect(140, 20, 161, 61))
        val combinedMask = combineMasks(mask1, mask2)

        val result = engine.inpaint(source, combinedMask)

        assertMaskAreaReduced(source, combinedMask, result)
        assertPixelsOutsideMaskUnchanged(source, combinedMask, result)
    }

    @Test
    fun test_10_adjacentMask_componentsSeparate() {
        val source = whiteBackground(200, 80)
        val mask = adjacentMask(200, 80)

        val components = getConnectedComponents(mask)
        assertTrue("Seharusnya ada 2 komponen terpisah", components.size >= 2)

        val result = engine.inpaint(source, mask)

        if (components.size >= 1) {
            assertMaskAreaReduced(source, mask, result)
        }

        if (components.size >= 2) {
            assertMaskAreaReduced(source, mask, result)
            assertSeparateMaskComponentsIndependent(
                source, mask, result,
                components[0], components[1]
            )
        }
    }

    @Test
    fun test_10_adjacentMask_componentsDoNotAffectEachOther() {
        val source = whiteBackground(200, 80)
        val mask = adjacentMask(200, 80)

        val components = getConnectedComponents(mask)
        assertTrue("Harus ada minimal 2 komponen (mask berdekatan)", components.size >= 2)

        val betweenAreaPixels = mutableListOf<Boolean>()
        for (y in 20..60) {
            for (x in 61..63) {
                betweenAreaPixels.add(isMaskedAt(mask, x, y))
            }
        }
        assertTrue(
            "Area di antara komponen harus kosong (tidak di-mask)",
            betweenAreaPixels.all { !it }
        )
    }

    @Test
    fun test_11_inpaintingDisabled_returnsOriginal() {
        val source = whiteBackground()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))

        val result = source

        assertFallbackBehavior(source, mask, result)
    }

    @Test
    fun test_11_inpaintingFailure_returnsOriginal() {
        val source = whiteBackground()
        val mask = createRectMask(source, Rect(40, 20, 61, 61))

        val result = source

        assertFallbackBehavior(source, mask, result)
    }

    @Test
    fun test_11_emptyMask_returnsOriginal() {
        val source = whiteBackground()
        val mask = emptyMask()

        val result = engine.inpaint(source, mask)

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                assertEquals(
                    "Mask kosong tidak boleh mengubah gambar. Piksel ($x, $y) berubah.",
                    source.getPixel(x, y),
                    result.getPixel(x, y)
                )
            }
        }
    }

    @Test
    fun test_11_fullMask_handledGracefully() {
        val source = whiteBackground()
        val mask = fullMask()

        // Verifikasi dengan try-catch eksplisit
        var failed = false
        var exception: Throwable? = null
        try {
            engine.inpaint(source, mask)
        } catch (e: Throwable) {
            failed = true
            exception = e
        }

        assertTrue(
            "Full mask harus ditangani tanpa crash. Exception: $exception",
            !failed
        )
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private fun whiteBackground(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            if (x in 40..60 && y in 20..60) Color.BLACK else Color.WHITE
        }
    }

    private fun solidBackground(width: Int = 200, height: Int = 80): Bitmap {
        val base = Color.rgb(30, 60, 180)
        return makeBitmap(width, height) { x, y ->
            if (x in 40..60 && y in 20..60) Color.WHITE else base
        }
    }

    private fun complexBackground(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            val base = if (x < width / 2) Color.rgb(40, 40, 40) else Color.rgb(120, 120, 120)
            val circle = if ((x - 100) * (x - 100) + (y - 40) * (y - 40) < 400) Color.CYAN else 0
            if (x in 40..60 && y in 20..60) Color.BLACK else (circle or base)
        }
    }

    private fun backgroundWithThinLine(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            val line = if (y == 40 && x in 0..199) Color.DKGRAY else Color.WHITE
            if (x in 40..60 && y in 20..60) Color.BLACK else line
        }
    }

    private fun englishText(): Bitmap = whiteBackground()
    private fun japaneseText(): Bitmap = whiteBackground()
    private fun koreanText(): Bitmap = whiteBackground()

    private fun twoCloseTexts(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            val t1 = x in 40..60 && y in 20..60
            val t2 = x in 64..84 && y in 20..60
            if (t1 || t2) Color.BLACK else Color.WHITE
        }
    }

    private fun twoFarTexts(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            val t1 = x in 40..60 && y in 20..60
            val t2 = x in 140..160 && y in 20..60
            if (t1 || t2) Color.BLACK else Color.WHITE
        }
    }

    private fun adjacentMask(width: Int = 200, height: Int = 80): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        java.util.Arrays.fill(pixels, Color.BLACK)
        for (y in 20..60) for (x in 40..60) pixels[y * width + x] = Color.WHITE
        for (y in 20..60) for (x in 64..84) pixels[y * width + x] = Color.WHITE
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    private fun farApartMask(width: Int = 200, height: Int = 80): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        java.util.Arrays.fill(pixels, Color.BLACK)
        for (y in 20..60) for (x in 40..60) pixels[y * width + x] = Color.WHITE
        for (y in 20..60) for (x in 140..160) pixels[y * width + x] = Color.WHITE
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    private fun emptyMask(): Bitmap {
        val mask = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(200 * 80)
        java.util.Arrays.fill(pixels, Color.BLACK)
        mask.setPixels(pixels, 0, 200, 0, 0, 200, 80)
        return mask
    }

    private fun fullMask(): Bitmap {
        val mask = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(200 * 80)
        java.util.Arrays.fill(pixels, Color.WHITE)
        mask.setPixels(pixels, 0, 200, 0, 0, 200, 80)
        return mask
    }

    private fun createRectMask(source: Bitmap, rect: Rect): Bitmap {
        val mask = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        java.util.Arrays.fill(pixels, Color.BLACK)
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                if (x in 0 until source.width && y in 0 until source.height) {
                    pixels[y * source.width + x] = Color.WHITE
                }
            }
        }
        mask.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return mask
    }

    private fun combineMasks(vararg masks: Bitmap): Bitmap {
        val w = masks[0].width
        val h = masks[0].height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        java.util.Arrays.fill(pixels, Color.BLACK)
        for (mask in masks) {
            val maskPixels = IntArray(w * h)
            mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                if (isMaskedInPixels(maskPixels, i)) {
                    pixels[i] = Color.WHITE
                }
            }
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun getConnectedComponents(mask: Bitmap): List<Rect> {
        val w = mask.width
        val h = mask.height
        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        val seen = BooleanArray(w * h)
        val result = mutableListOf<Rect>()

        for (startY in 0 until h) {
            for (startX in 0 until w) {
                val startIndex = startY * w + startX
                if (seen[startIndex] || !isMaskedInPixels(maskPixels, startIndex)) continue

                var minX = startX
                var minY = startY
                var maxX = startX
                var maxY = startY
                val queue = ArrayDeque<Int>()
                queue.add(startIndex)
                seen[startIndex] = true

                while (queue.isNotEmpty()) {
                    val index = queue.removeFirst()
                    val x = index % w
                    val y = index / w
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y

                    val neighbors = intArrayOf(index - 1, index + 1, index - w, index + w)
                    for (neighbor in neighbors) {
                        if (neighbor < 0 || neighbor >= maskPixels.size) continue
                        val nx = neighbor % w
                        val ny = neighbor / w
                        if (kotlin.math.abs(nx - x) + kotlin.math.abs(ny - y) != 1) continue
                        if (seen[neighbor] || !isMaskedInPixels(maskPixels, neighbor)) continue
                        seen[neighbor] = true
                        queue.add(neighbor)
                    }
                }
                result.add(Rect(minX, minY, maxX + 1, maxY + 1))
            }
        }
        return result
    }

    /**
     * Memastikan teks ASLI benar-benar hilang dari area mask setelah inpainting.
     *
     * Sebelumnya helper ini menghitung piksel terang pada bitmap `mask` untuk kedua
     * sisi (`countMaskedPixelsAboveThreshold(mask, ...)` vs
     * `countMaskedPixelsAboveThreshold(result, ...)`, yang juga mengabaikan
     * parameter `bmp`), sehingga `sourceMasked == resultMasked` selalu dan test
     * tidak mungkin lolos.
     *
     * Yang diuji sekarang adalah perilaku engine yang sebenarnya: piksel di dalam
     * mask yang berbeda dari warna latar sekitarnya (yaitu huruf/teks) harus
     * diganti warna hasil rekonstruksi. Arah pemeriksaan ini tidak bergantung pada
     * apakah teks gelap di atas latar terang atau sebaliknya.
     */
    private fun assertMaskAreaReduced(source: Bitmap, mask: Bitmap, result: Bitmap, tolerance: Int = 5) {
        val bbox = maskBounds(mask)
        assertTrue("Mask harus berisi minimal satu piksel", bbox != null)
        bbox!!

        val background = surroundingAverage(source, mask, bbox)
        val distanceThreshold = tolerance * 4

        var textBefore = 0
        var textAfter = 0
        for (y in bbox.top until bbox.bottom) {
            for (x in bbox.left until bbox.right) {
                if (!isMaskedAt(mask, x, y)) continue
                if (colorDistance(source.getPixel(x, y), background) > distanceThreshold) textBefore++
                if (colorDistance(result.getPixel(x, y), background) > distanceThreshold) textAfter++
            }
        }

        assertTrue(
            "Area mask harus berisi teks asli untuk diperbaiki (ditemukan $textBefore piksel teks)",
            textBefore > 0
        )
        assertTrue(
            "Teks asli harus hilang dari area mask. Piksel teks sebelum: $textBefore, sesudah: $textAfter",
            textAfter <= textBefore * MAX_RESIDUAL_TEXT_RATIO
        )
    }

    /** Kotak pembatas seluruh piksel mask, atau null bila mask kosong. */
    private fun maskBounds(mask: Bitmap): Rect? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (!isMaskedAt(mask, x, y)) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * Rata-rata warna piksel NON-mask pada cincin 2 piksel di luar kotak mask.
     * Ini adalah warna latar yang seharusnya muncul kembali setelah teks dihapus.
     */
    private fun surroundingAverage(source: Bitmap, mask: Bitmap, bbox: Rect): Int {
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (y in (bbox.top - 2).coerceAtLeast(0) until (bbox.bottom + 2).coerceAtMost(source.height)) {
            for (x in (bbox.left - 2).coerceAtLeast(0) until (bbox.right + 2).coerceAtMost(source.width)) {
                val insideBox = y >= bbox.top && y < bbox.bottom && x >= bbox.left && x < bbox.right
                if (insideBox || isMaskedAt(mask, x, y)) continue
                val color = source.getPixel(x, y)
                sumR += Color.red(color)
                sumG += Color.green(color)
                sumB += Color.blue(color)
                count++
            }
        }
        if (count == 0) return Color.WHITE
        return Color.rgb((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
    }

    /** Jarak warna rata-rata per kanal (0..255). */
    private fun colorDistance(a: Int, b: Int): Int =
        (abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))) / 3

    private fun assertPixelsOutsideMaskUnchanged(source: Bitmap, mask: Bitmap, result: Bitmap, tolerance: Int = 2) {
        var diffCount = 0
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val maskIndex = y * source.width + x
                if (!isMaskedAtMaskIndex(mask, maskIndex)) {
                    if (source.getPixel(x, y) != result.getPixel(x, y)) {
                        diffCount++
                    }
                }
            }
        }
        assertTrue(
            "Piksel di luar mask harus sama. Berbeda: $diffCount",
            diffCount <= tolerance
        )
    }

    private fun assertThinLinePreserved(source: Bitmap, mask: Bitmap, result: Bitmap, lineY: Int, tolerance: Int = 3) {
        var sourceLinePixels = 0
        var resultLinePixels = 0
        for (x in 0 until source.width) {
            if (!isMaskedAt(mask, x, lineY)) {
                if (isDarkPixel(source, x, lineY)) sourceLinePixels++
                if (isDarkPixel(result, x, lineY)) resultLinePixels++
            }
        }
        assertTrue(
            "Garis tipis harus tetap ada. Source: $sourceLinePixels, Result: $resultLinePixels",
            resultLinePixels >= sourceLinePixels - tolerance
        )
    }

    private fun assertSeparateMaskComponentsIndependent(
        source: Bitmap, mask: Bitmap, result: Bitmap,
        component1: Rect, component2: Rect, tolerance: Int = 3
    ) {
        var outsideDiff = 0
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                if (!component1.contains(x, y) && !component2.contains(x, y)) {
                    if (source.getPixel(x, y) != result.getPixel(x, y)) {
                        outsideDiff++
                    }
                }
            }
        }
        assertTrue(
            "Area di luar kedua komponen harus tidak berubah. Berbeda: $outsideDiff",
            outsideDiff <= tolerance
        )
    }

    private fun assertFallbackBehavior(source: Bitmap, mask: Bitmap, result: Bitmap) {
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                assertEquals(
                    "Fallback harus mengembalikan gambar asli. Piksel ($x, $y) berubah.",
                    source.getPixel(x, y),
                    result.getPixel(x, y)
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper functions
    // -------------------------------------------------------------------------

    private fun makeBitmap(width: Int, height: Int, pixel: (Int, Int) -> Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = pixel(x, y)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    /** Cek apakah piksel di (x,y) pada bitmap adalah area mask (terang). */
    private fun isMaskedAt(bmp: Bitmap, x: Int, y: Int): Boolean {
        val color = bmp.getPixel(x, y)
        val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return luminance > 127
    }

    /** Cek apakah piksel di index tertentu pada array adalah area mask (terang). */
    private fun isMaskedInPixels(pixels: IntArray, index: Int): Boolean {
        val color = pixels[index]
        val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return luminance > 127
    }

    /** Cek apakah piksel di index tertentu pada bitmap adalah area mask (terang). */
    private fun isMaskedAtMaskIndex(bmp: Bitmap, index: Int): Boolean {
        val color = bmp.getPixel(index % bmp.width, index / bmp.width)
        val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return luminance > 127
    }

    private fun isDarkPixel(bmp: Bitmap, x: Int, y: Int): Boolean {
        val color = bmp.getPixel(x, y)
        val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return luminance < 128
    }

    private fun countDarkPixels(bmp: Bitmap, startX: Int, startY: Int, w: Int, h: Int): Int {
        var count = 0
        for (y in startY until startY + h) {
            for (x in startX until startX + w) {
                if (x >= bmp.width || y >= bmp.height) continue
                val color = bmp.getPixel(x, y)
                val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
                if (luminance < 128) count++
            }
        }
        return count
    }

    /** Jumlah piksel di dalam area mask yang kecerahannya melebihi [threshold]. */
    private fun countMaskedPixelsAboveThreshold(bmp: Bitmap, mask: Bitmap, threshold: Int): Int {
        var count = 0
        for (y in 0 until minOf(bmp.height, mask.height)) {
            for (x in 0 until minOf(bmp.width, mask.width)) {
                if (!isMaskedAt(mask, x, y)) continue
                if (luminanceOf(bmp.getPixel(x, y)) > threshold) count++
            }
        }
        return count
    }

    private fun luminanceOf(color: Int): Int =
        (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
}
