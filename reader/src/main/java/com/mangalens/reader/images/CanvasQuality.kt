package com.mangalens.reader.images

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Validasi kualitas yang dipakai SEMUA jalur ekstraksi canvas:
 * native `toDataURL`, native HTTP fetch, dan PixelCopy.
 *
 * Tujuannya menolak hasil kosong/transparan/terlalu kecil tanpa menolak
 * halaman manga yang memang didominasi putih atau hitam. Ukuran file saja
 * tidak cukup: canvas putih 800x2400 dapat terkompresi menjadi beberapa KB
 * tetapi tetap bukan halaman.
 *
 * Yang diperiksa (pada sampel <= 96x96):
 * - dimensi minimum,
 * - alpha rata-rata (transparan),
 * - deviasi standar luminansi (gambar seragam),
 * - fraksi tinta dan kertas (konten nyata).
 */
object CanvasQuality {

    data class Verdict(
        val ok: Boolean,
        val reason: String? = null,
        val stdDev: Float = 0f,
        val inkFraction: Float = 0f,
        val lightFraction: Float = 0f
    )

    fun validate(bitmap: Bitmap): Verdict {
        if (bitmap.isRecycled) return Verdict(false, "recycled")
        if (bitmap.width < MIN_DIMENSION || bitmap.height < MIN_DIMENSION) {
            return Verdict(false, "terlalu kecil ${bitmap.width}x${bitmap.height}")
        }

        val sampleW = min(MAX_SAMPLE, bitmap.width)
        val sampleH = min(MAX_SAMPLE, bitmap.height)
        val stepX = max(1, bitmap.width / sampleW)
        val stepY = max(1, bitmap.height / sampleH)

        var count = 0
        var alphaSum = 0L
        var lumaSum = 0L
        var lumaSq = 0.0
        var dark = 0
        var light = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val luma = (
                    Color.red(pixel) * 299 +
                        Color.green(pixel) * 587 +
                        Color.blue(pixel) * 114
                    ) / 1000
                alphaSum += alpha
                lumaSum += luma
                lumaSq += (luma * luma).toDouble()
                if (luma < INK_LUMA) dark++
                if (luma > PAPER_LUMA) light++
                count++
                x += stepX
            }
            y += stepY
        }

        if (count == 0) return Verdict(false, "tanpa piksel")

        val total = count.toDouble()
        val meanAlpha = alphaSum / total
        val mean = lumaSum / total
        val variance = (lumaSq / total - mean * mean).coerceAtLeast(0.0)
        val stdDev = kotlin.math.sqrt(variance).toFloat()
        val ink = (dark / total).toFloat()
        val paper = (light / total).toFloat()

        if (meanAlpha < 16.0) {
            return Verdict(false, "transparan", stdDev, ink, paper)
        }
        if (stdDev < MIN_STD_DEV) {
            return Verdict(false, "seragam/kosong (stdDev=%.1f)".format(stdDev), stdDev, ink, paper)
        }
        // Halaman manga boleh dominan putih, tetapi harus punya garis/huruf.
        if (ink < MIN_INK && paper > 0.9f) {
            return Verdict(false, "tanpa konten (ink=%.4f)".format(ink), stdDev, ink, paper)
        }
        return Verdict(true, null, stdDev, ink, paper)
    }

    private const val MIN_DIMENSION = 80
    private const val MAX_SAMPLE = 96
    private const val MIN_STD_DEV = 4.0f
    private const val MIN_INK = 0.0015f
    private const val INK_LUMA = 80
    private const val PAPER_LUMA = 200
}
