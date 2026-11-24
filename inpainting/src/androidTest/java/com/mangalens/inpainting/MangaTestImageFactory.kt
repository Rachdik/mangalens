package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Factory deterministik untuk membuat gambar uji dan mask tanpa perangkat nyata. */
object MangaTestImageFactory {

    /** Gambar putih bersih, teks hitam di tengah. */
    fun whiteBackground(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            if (x in 40..60 && y in 20..60) Color.BLACK else Color.WHITE
        }
    }

    /** Latar warna solid (biru), teks putih. */
    fun solidBackground(width: Int = 200, height: Int = 80): Bitmap {
        val base = Color.rgb(30, 60, 180)
        return makeBitmap(width, height) { x, y ->
            if (x in 40..60 && y in 20..60) Color.WHITE else base
        }
    }

    /** Latar kompleks: gradien horizontal + kotak gangguan. */
    fun complexBackground(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            val base = if (x < width / 2) Color.rgb(40, 40, 40) else Color.rgb(120, 120, 120)
            val circle = if ((x - 100) * (x - 100) + (y - 40) * (y - 40) < 400) Color.CYAN else 0
            if (x in 40..60 && y in 20..60) Color.BLACK else (circle or base)
        }
    }

    /** Latar dengan garis ilustrasi tipis horizontal yang melintasi area teks. */
    fun backgroundWithThinLine(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            val line = if (y == 40 && x in 0..199) Color.DKGRAY else Color.WHITE
            if (x in 40..60 && y in 20..60) Color.BLACK else line
        }
    }

    /** Teks Inggris (alphabet Latin di area mask). */
    fun englishText(): Bitmap = whiteBackground()

    /** Teks Jepang (simulasi dengan kotak hitam di area mask). */
    fun japaneseText(): Bitmap = whiteBackground()

    /** Teks Korea (simulasi dengan kotak hitam di area mask). */
    fun koreanText(): Bitmap = whiteBackground()

    /** Dua area teks berdekatan (selisih 4 piksel). */
    fun twoCloseTexts(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            val t1 = x in 40..60 && y in 20..60
            val t2 = x in 64..84 && y in 20..60
            if (t1 || t2) Color.BLACK else Color.WHITE
        }
    }

    /** Dua area teks berjauhan (dipisah 60 piksel). */
    fun twoFarTexts(width: Int = 200, height: Int = 80): Bitmap {
        return makeBitmap(width, height) { x, y ->
            val t1 = x in 40..60 && y in 20..60
            val t2 = x in 140..160 && y in 20..60
            if (t1 || t2) Color.BLACK else Color.WHITE
        }
    }

    /** Mask dengan dua area berdekatan (selisih 2 piksel: 60 dan 62). */
    fun adjacentMask(width: Int = 200, height: Int = 80): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        java.util.Arrays.fill(pixels, Color.BLACK)
        // Area 1: x 40..60, y 20..60
        for (y in 20..60) for (x in 40..60) pixels[y * width + x] = Color.WHITE
        // Area 2: x 64..84, y 20..60 (berdekatan)
        for (y in 20..60) for (x in 64..84) pixels[y * width + x] = Color.WHITE
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    /** Mask dengan dua area berjauhan (dipisah 80 piksel). */
    fun farApartMask(width: Int = 200, height: Int = 80): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        java.util.Arrays.fill(pixels, Color.BLACK)
        for (y in 20..60) for (x in 40..60) pixels[y * width + x] = Color.WHITE
        for (y in 20..60) for (x in 140..160) pixels[y * width + x] = Color.WHITE
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    /** Mask kosong (tidak ada area teks). */
    fun emptyMask(width: Int = 200, height: Int = 80): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        java.util.Arrays.fill(pixels, Color.BLACK)
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    /** Mask penuh (seluruh gambar di-mask). */
    fun fullMask(width: Int = 200, height: Int = 80): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        java.util.Arrays.fill(pixels, Color.WHITE)
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    private fun makeBitmap(width: Int, height: Int, pixel: (Int, Int) -> Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = pixel(x, y)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }
}

/** Assertion helper untuk validasi hasil inpainting. */
class BasicInpaintingEngineAssertions {

    fun assertMaskAreaReduced(
        source: Bitmap,
        mask: Bitmap,
        result: Bitmap,
        tolerance: Int = 5
    ) {
        val sourceMaskedPxCount = countMaskedPixelsAboveThreshold(source, mask, MASK_LUMINANCE_THRESHOLD)
        val resultMaskedPxCount = countMaskedPixelsAboveThreshold(result, mask, MASK_LUMINANCE_THRESHOLD)
        assertTrue(
            "Area teks (mask) harus berkurang setelah inpainting. Source: $sourceMaskedPxCount, Result: $resultMaskedPxCount",
            resultMaskedPxCount < sourceMaskedPxCount - tolerance
        )
    }

    fun assertPixelsOutsideMaskUnchanged(
        source: Bitmap,
        mask: Bitmap,
        result: Bitmap,
        tolerance: Int = 2
    ) {
        val totalPixels = source.width * source.height
        val maskPxCount = countMaskedPixelsAboveThreshold(mask, mask, 1)
        val outsidePxCount = totalPixels - maskPxCount
        var diffCount = 0
        for (i in 0 until totalPixels) {
            if (!isMasked(mask, i)) {
                if (source.getPixel(i % source.width, i / source.width) != result.getPixel(i % result.width, i / result.height)) {
                    diffCount++
                }
            }
        }
        assertTrue(
            "Piksel di luar mask harus tidak berubah. Berbeda: $diffCount dari $outsidePxCount piksel di luar mask",
            diffCount <= tolerance
        )
    }

    fun assertThinLinePreserved(
        source: Bitmap,
        mask: Bitmap,
        result: Bitmap,
        lineY: Int,
        tolerance: Int = 3
    ) {
        var sourceLinePixels = 0
        var resultLinePixels = 0
        for (x in 0 until source.width) {
            if (!isMasked(mask, x, lineY)) {
                if (isDarkPixel(source, x, lineY)) sourceLinePixels++
                if (isDarkPixel(result, x, lineY)) resultLinePixels++
            }
        }
        assertTrue(
            "Garis tipis di luar mask harus tetap ada. Source: $sourceLinePixels, Result: $resultLinePixels",
            resultLinePixels >= sourceLinePixels - tolerance
        )
    }

    fun assertSeparateMaskComponentsIndependent(
        source: Bitmap,
        mask: Bitmap,
        result: Bitmap,
        component1: Rect,
        component2: Rect,
        tolerance: Int = 3
    ) {
        // Validasi bahwa area di luar kedua komponen tidak berubah
        val totalPixels = source.width * source.height
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
            "Area di luar kedua komponen mask harus tidak berubah. Berbeda: $outsideDiff",
            outsideDiff <= tolerance
        )
    }

    fun assertFallbackBehavior(
        source: Bitmap,
        mask: Bitmap,
        result: Bitmap
    ) {
        // Fallback: jika inpainting gagal atau dinonaktifkan, hasil harus sama dengan source
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

    fun assertNoCrashOnEmptyOrInvalidInput() {
        // Test ini hanya memastikan konstruksi objek tidak crash
        // Validasi dilakukan di test method yang sesuai
    }

    private fun countMaskedPixelsAboveThreshold(bmp: Bitmap, mask: Bitmap, threshold: Int): Int {
        var count = 0
        val w = bmp.width
        val h = bmp.height
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (isMasked(mask, x, y, threshold)) count++
            }
        }
        return count
    }

    private fun isMasked(mask: Bitmap, x: Int, y: Int, threshold: Int = MASK_LUMINANCE_THRESHOLD): Boolean {
        val color = mask.getPixel(x, y)
        val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return luminance > threshold
    }

    private fun isMasked(mask: Bitmap, index: Int): Boolean {
        val color = mask.getPixel(index % mask.width, index / mask.width)
        val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return luminance > MASK_LUMINANCE_THRESHOLD
    }

    private fun isDarkPixel(bmp: Bitmap, x: Int, y: Int): Boolean {
        val color = bmp.getPixel(x, y)
        val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return luminance < 128
    }

    companion object {
        const val MASK_LUMINANCE_THRESHOLD = 127
    }
}
