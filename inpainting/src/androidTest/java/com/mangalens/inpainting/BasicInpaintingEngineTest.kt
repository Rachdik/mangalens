package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uji perilaku [BasicInpaintingEngine] pada latar berbeda.
 *
 * Fokus uji ini adalah membuktikan area mask benar-benar diisi ulang dari
 * piksel asli di sekitarnya (bukan sekadar ditutup satu warna) dan bahwa
 * piksel di luar mask tidak berubah. Uji ini TIDAK membuktikan kualitas
 * artistik pada manga sungguhan.
 */
@RunWith(AndroidJUnit4::class)
class BasicInpaintingEngineTest {

    private val engine = BasicInpaintingEngine()

    private val maskRect = Rect(50, 50, 110, 70)

    private fun blank(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun solidPage(width: Int, height: Int, background: Int, textColor: Int): Bitmap {
        val bitmap = blank(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint().apply { color = textColor }
        canvas.drawRect(
            maskRect.left.toFloat(),
            maskRect.top.toFloat(),
            maskRect.right.toFloat(),
            maskRect.bottom.toFloat(),
            paint
        )
        return bitmap
    }

    private fun gradientPage(width: Int, height: Int, textColor: Int): Bitmap {
        val bitmap = blank(width, height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val t = y.toFloat() / (height - 1)
            val r = (255 * (1 - t)).toInt()
            val g = (255 * (1 - t * 0.5f)).toInt()
            val b = 200
            val color = Color.rgb(r, g, b)
            for (x in 0 until width) pixels[y * width + x] = color
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = textColor }
        canvas.drawRect(
            maskRect.left.toFloat(),
            maskRect.top.toFloat(),
            maskRect.right.toFloat(),
            maskRect.bottom.toFloat(),
            paint
        )
        return bitmap
    }

    private fun screentonePage(width: Int, height: Int): Bitmap {
        val bitmap = blank(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val grey = Paint().apply { color = Color.rgb(140, 140, 140) }
        var y = 0
        while (y < height) {
            canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 6).toFloat(), grey)
            y += 12
        }
        val black = Paint().apply { color = Color.BLACK }
        canvas.drawRect(
            maskRect.left.toFloat(),
            maskRect.top.toFloat(),
            maskRect.right.toFloat(),
            maskRect.bottom.toFloat(),
            black
        )
        return bitmap
    }

    private fun maskFor(width: Int, height: Int, pad: Int = 2): Bitmap {
        val mask = blank(width, height)
        val canvas = Canvas(mask)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(
            (maskRect.left - pad).toFloat(),
            (maskRect.top - pad).toFloat(),
            (maskRect.right + pad).toFloat(),
            (maskRect.bottom + pad).toFloat(),
            paint
        )
        return mask
    }

    private fun luminance(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    @Test
    fun solidBackground_textFullyRemoved() {
        val width = 160
        val height = 120
        val source = solidPage(width, height, Color.WHITE, Color.BLACK)
        val mask = maskFor(width, height)

        val result = engine.inpaint(source, mask)

        var darkest = 255
        var sum = 0L
        var count = 0
        for (y in maskRect.top + 4 until maskRect.bottom - 4) {
            for (x in maskRect.left + 4 until maskRect.right - 4) {
                val value = luminance(result.getPixel(x, y))
                darkest = minOf(darkest, value)
                sum += value
                count++
            }
        }
        val average = sum / count
        assertTrue("masih ada piksel teks gelap (min=$darkest)", darkest > 200)
        assertTrue("hasil bukan latar putih (avg=$average)", average > 230)
    }

    @Test
    fun gradientBackground_fillFollowsSurroundingColour() {
        val width = 120
        val height = 200
        val source = gradientPage(width, height, Color.BLACK)
        val mask = maskFor(width, height)

        val result = engine.inpaint(source, mask)

        // Bandingkan isi mask dengan gradien asli di baris yang sama (di luar mask).
        for (y in maskRect.top + 6 until maskRect.bottom - 6 step 4) {
            val reference = luminance(source.getPixel(5, y))
            for (x in maskRect.left + 6 until maskRect.right - 6 step 8) {
                val filled = luminance(result.getPixel(x, y))
                val delta = kotlin.math.abs(filled - reference)
                assertTrue(
                    "isi mask (x=$x,y=$y) menyimpang $delta dari gradien sekitar",
                    delta < 70
                )
            }
        }
    }

    @Test
    fun screentone_blackTextRemovedEvenIfPatternBlurred() {
        val width = 160
        val height = 120
        val source = screentonePage(width, height)
        val mask = maskFor(width, height)

        val result = engine.inpaint(source, mask)

        var dark = 0
        for (y in maskRect.top + 4 until maskRect.bottom - 4) {
            for (x in maskRect.left + 4 until maskRect.right - 4) {
                if (luminance(result.getPixel(x, y)) < 40) dark++
            }
        }
        assertEquals("masih ada sisa teks hitam di area mask", 0, dark)
    }

    @Test
    fun pixelsOutsideMask_areUntouched() {
        val width = 160
        val height = 120
        val source = solidPage(width, height, Color.rgb(30, 120, 220), Color.BLACK)
        val mask = maskFor(width, height)
        val before = IntArray(width * height).also {
            source.getPixels(it, 0, width, 0, 0, width, height)
        }

        val result = engine.inpaint(source, mask)
        val after = IntArray(width * height).also {
            result.getPixels(it, 0, width, 0, 0, width, height)
        }

        var changedOutside = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val insideMask = x >= maskRect.left - 2 && x <= maskRect.right + 2 &&
                    y >= maskRect.top - 2 && y <= maskRect.bottom + 2
                if (!insideMask && before[y * width + x] != after[y * width + x]) changedOutside++
            }
        }
        assertEquals("piksel di luar mask ikut berubah", 0, changedOutside)
    }

    @Test
    fun emptyMask_returnsCopyWithoutChanges() {
        val width = 80
        val height = 60
        val source = solidPage(width, height, Color.WHITE, Color.BLACK)
        val mask = blank(width, height).apply { Canvas(this).drawColor(Color.BLACK) }
        val before = IntArray(width * height).also {
            source.getPixels(it, 0, width, 0, 0, width, height)
        }

        val result = engine.inpaint(source, mask)
        val after = IntArray(width * height).also {
            result.getPixels(it, 0, width, 0, 0, width, height)
        }
        assertTrue("mask kosong seharusnya tidak mengubah gambar", before.contentEquals(after))
    }
}
