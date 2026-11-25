package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Kualitas inpainting pada latar berbeda (Phase 9J, Task E).
 *
 * Semua metrik diukur dari bitmap hasil engine produksi
 * [BasicInpaintingEngine] — tidak ada gambar yang ditempel manual. Latar
 * sintetis dipakai agar tersedia ground truth yang pasti (warna/pola asli di
 * area mask diketahui), sesuatu yang tidak mungkin pada halaman manga nyata.
 */
@RunWith(AndroidJUnit4::class)
class InpaintingQualityMatrixTest {

    private val engine = BasicInpaintingEngine()
    private val maskRect = Rect(220, 150, 380, 250)
    private val width = 600
    private val height = 400

    @Test
    fun plainWhiteBackgroundIsReconstructed() {
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(page).drawColor(Color.WHITE)
        drawTextLines(page, Color.BLACK)

        val result = engine.inpaint(page, mask())
        val residual = countDarkInsideMask(result)
        val meanError = meanAbsErrorInsideMask(result, { _, _ -> Color.WHITE })
        println("QUALITY plain residualDark=$residual meanError=${fmt(meanError)}")

        assertTrue("teks asli harus ada sebelum inpainting", countDarkInsideMask(page) > 50)
        assertTrue("sisa tinta $residual terlalu banyak", residual <= 5)
        assertTrue("galat warna ${fmt(meanError)} terlalu besar", meanError < 4.0)
    }

    @Test
    fun gradientBackgroundIsInterpolated() {
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        for (x in 0 until width) {
            val value = 200 + (x * 55 / width)
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), Paint().apply {
                color = Color.rgb(value, value, value)
            })
        }
        drawTextLines(page, Color.BLACK)
        val result = engine.inpaint(page, mask())

        val expected = { x: Int, _: Int ->
            val value = 200 + (x * 55 / width)
            Color.rgb(value, value, value)
        }
        val error = meanAbsErrorInsideMask(result, expected)
        val residual = countDarkInsideMask(result)
        println("QUALITY gradient residualDark=$residual deviation=${fmt(error)}")
        assertTrue("sisa tinta $residual terlalu banyak", residual <= 5)
        assertTrue("deviasi gradien ${fmt(error)} terlalu besar", error < 12.0)
    }

    @Test
    fun coloredBackgroundIsReconstructed() {
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.rgb(70, 110, 190))
        drawTextLines(page, Color.WHITE)
        val result = engine.inpaint(page, mask())

        val meanError = meanAbsErrorInsideMask(result) { _, _ -> Color.rgb(70, 110, 190) }
        val whiteLeft = countWhiteInsideMask(result)
        println("QUALITY colored deviation=${fmt(meanError)} residualWhite=$whiteLeft")
        assertTrue("galat warna ${fmt(meanError)} terlalu besar", meanError < 8.0)
        assertTrue("sisa teks putih $whiteLeft terlalu banyak", whiteLeft <= 5)
    }

    @Test
    fun screentoneIsFlattenedAndReported() {
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        // Screentone: titik hitam berjarak 4 px.
        val dot = Paint().apply { color = Color.rgb(110, 110, 110) }
        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                canvas.drawCircle(x.toFloat(), y.toFloat(), 1.2f, dot)
            }
        }
        val referenceVariance = varianceInsideMask(page)
        drawTextLines(page, Color.BLACK)
        val result = engine.inpaint(page, mask())
        val resultVariance = varianceInsideMask(result)
        val retention = if (referenceVariance <= 0.0) 0.0 else resultVariance / referenceVariance
        println(
            "QUALITY screentone referenceVariance=${fmt(referenceVariance)} " +
                "resultVariance=${fmt(resultVariance)} retained=${fmt(retention * 100)}%"
        )
        // Ini BUKAN target lulus: nilainya dilaporkan sebagai batas kualitas.
        // Yang diuji hanya bahwa engine tidak merusak area di luar mask.
        assertTrue("area di luar mask tidak boleh berubah", outsideMaskUnchanged(page, result))
    }

    @Test
    fun artworkLineThroughMaskIsContinuedAndTextRemoved() {
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        // Garis ilustrasi melewati area teks, di antara baris teks ke-2 dan ke-3.
        val lineTop = 215
        val lineBottom = 225
        canvas.drawRect(100f, lineTop.toFloat(), 500f, lineBottom.toFloat(), Paint().apply { color = Color.BLACK })
        drawTextLines(page, Color.BLACK)
        val result = engine.inpaint(page, mask())

        val darkRows = (maskRect.top until maskRect.bottom).filter { y ->
            (maskRect.left until maskRect.right).count { x -> luminance(result.getPixel(x, y)) < 110 } >= 20
        }
        val textRowsLeft = darkRows.count { it < lineTop - 1 || it > lineBottom + 1 }
        println("QUALITY artwork darkRows=${darkRows.size} textRowsLeft=$textRowsLeft rows=$darkRows")

        // Teks harus hilang seluruhnya; garis ilustrasi yang melewati mask ikut
        // tersambung (perilaku engine: interpolasi dari piksel gelap di tepi mask).
        assertEquals("baris teks masih tersisa: $darkRows", 0, textRowsLeft)
        assertTrue("garis ilustrasi seharusnya tersambung, darkRows=${darkRows.size}", darkRows.isNotEmpty())
    }

    private fun mask(): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawColor(Color.BLACK)
        Canvas(mask).drawRect(maskRect, Paint().apply { color = Color.WHITE })
        return mask
    }

    /** Tiga baris teks hitam di dalam area mask. */
    private fun drawTextLines(bitmap: Bitmap, color: Int) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            this.color = color
            strokeWidth = 7f
        }
        for (row in 0..2) {
            val y = maskRect.top + 20 + row * 32
            canvas.drawLine(maskRect.left + 10f, y.toFloat(), maskRect.right - 10f, y.toFloat(), paint)
        }
    }

    private fun countDarkInsideMask(bitmap: Bitmap): Int = countInsideMask(bitmap) { luminance(it) < 110 }

    private fun countWhiteInsideMask(bitmap: Bitmap): Int = countInsideMask(bitmap) { luminance(it) > 220 }

    private fun countInsideMask(bitmap: Bitmap, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in maskRect.top until maskRect.bottom) {
            for (x in maskRect.left until maskRect.right) {
                if (predicate(bitmap.getPixel(x, y))) count++
            }
        }
        return count
    }

    private fun meanAbsErrorInsideMask(bitmap: Bitmap, expected: (Int, Int) -> Int): Double {
        var sum = 0.0
        var count = 0
        for (y in maskRect.top until maskRect.bottom) {
            for (x in maskRect.left until maskRect.right) {
                val actual = bitmap.getPixel(x, y)
                val want = expected(x, y)
                sum += (abs(Color.red(actual) - Color.red(want)) +
                    abs(Color.green(actual) - Color.green(want)) +
                    abs(Color.blue(actual) - Color.blue(want))) / 3.0
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun varianceInsideMask(bitmap: Bitmap): Double {
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in maskRect.top until maskRect.bottom) {
            for (x in maskRect.left until maskRect.right) {
                val value = luminance(bitmap.getPixel(x, y)).toDouble()
                sum += value
                sumSq += value * value
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - mean * mean
    }

    private fun outsideMaskUnchanged(before: Bitmap, after: Bitmap): Boolean {
        for (y in 0 until height step 3) {
            for (x in 0 until width step 3) {
                if (maskRect.contains(x, y)) continue
                if (before.getPixel(x, y) != after.getPixel(x, y)) return false
            }
        }
        return true
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
