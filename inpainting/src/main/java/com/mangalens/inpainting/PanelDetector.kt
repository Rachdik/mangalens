package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max

/**
 * Deteksi panel manga berbasis analisis celah (gutter) dan garis batas.
 *
 * Pendekatan (tanpa OpenCV/model, offline, ukuran APK tidak bertambah):
 * 1. Perkecil halaman ke sisi terpanjang [MAX_ANALYSIS_DIMENSION] agar cepat.
 * 2. Untuk setiap baris dan kolom hitung dua rasio: piksel putih (celah antar
 *    panel) dan piksel gelap (garis batas panel).
 * 3. Baris/kolom yang hampir seluruhnya putih ATAU hampir seluruhnya gelap
 *    dianggap pemisah; deretan pemisah yang bersambung digabung.
 * 4. Ruang di antara pemisah menjadi pita baris/kolom; irisan keduanya adalah
 *    panel.
 *
 * Keterbatasan (jujur):
 * - Panel tanpa garis batas dan tanpa celah putih TIDAK bisa dipisahkan; hasilnya
 *   satu panel seukuran halaman (degradasi aman, bukan error).
 * - Panel bersudut miring/overlap hanya didekati dengan kotak pembatas.
 * - Balon bicara tidak pernah dianggap panel karena balon tidak membentang
 *   selebar/setinggi halaman (syarat pemisah).
 * - Panel yang isinya nyaris kosong (mis. panel putih) dibuang agar tidak
 *   menghasilkan kotak palsu.
 *
 * Komponen ini belum dipakai pipeline OCR/terjemahan; pipeline tetap bekerja
 * per halaman bila [detect] mengembalikan satu panel.
 */
object PanelDetector {

    /** Sisi terpanjang gambar analisis; cukup untuk garis batas, tetap cepat. */
    private const val MAX_ANALYSIS_DIMENSION = 600

    /** Rasio piksel terang pada satu baris/kolom agar dianggap celah. */
    private const val GUTTER_WHITE_RATIO = 0.97f

    /** Rasio piksel gelap pada satu baris/kolom agar dianggap garis batas. */
    private const val BORDER_DARK_RATIO = 0.80f

    /** Lebar minimum satu pita panel (fraksi dari ukuran halaman). */
    private const val MIN_BAND_FRACTION = 0.06f

    /** Batas jumlah panel agar hasil tidak meledak pada halaman berderau. */
    private const val MAX_PANELS = 20

    /** Di bawah kecerahan ini piksel dianggap gelap (garis batas). */
    private const val DARK_LUMA = 90

    /** Di atas kecerahan ini piksel dianggap putih (celah). */
    private const val WHITE_LUMA = 235

    /** Hasil analisis antara, dipakai test untuk menjelaskan keputusan detektor. */
    internal data class PanelAnalysis(
        val rowBands: List<Pair<Int, Int>>,
        val colBands: List<Pair<Int, Int>>,
        val analysisWidth: Int,
        val analysisHeight: Int
    )

    fun detect(bitmap: Bitmap): List<Rect> {
        if (bitmap.width < 8 || bitmap.height < 8) return emptyList()

        val scale = analysisScale(bitmap)
        val analysis = analyzeBands(bitmap)
        val pixels = pixelsOf(bitmap, scale)
        val width = pixels.bitmap.width

        if (analysis.rowBands.size == 1 && analysis.colBands.size == 1) {
            if (pixels.bitmap !== bitmap) pixels.bitmap.recycle()
            // Tidak ada pemisah yang bisa dipercaya: satu panel = seluruh halaman.
            return listOf(Rect(0, 0, bitmap.width, bitmap.height))
        }

        val panels = mutableListOf<Rect>()
        for (row in analysis.rowBands) {
            for (col in analysis.colBands) {
                if (panels.size >= MAX_PANELS) break
                val rect = Rect(col.first, row.first, col.second, row.second)
                if (isBlank(pixels.pixels, width, rect)) continue
                panels += rect
            }
        }
        if (pixels.bitmap !== bitmap) pixels.bitmap.recycle()
        if (panels.isEmpty()) return listOf(Rect(0, 0, bitmap.width, bitmap.height))

        return panels
            .map { rect ->
                Rect(
                    (rect.left / scale).toInt().coerceIn(0, bitmap.width),
                    (rect.top / scale).toInt().coerceIn(0, bitmap.height),
                    (rect.right / scale).toInt().coerceIn(0, bitmap.width),
                    (rect.bottom / scale).toInt().coerceIn(0, bitmap.height)
                )
            }
            .filter { it.width() > 0 && it.height() > 0 }
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** Pita baris/kolom yang dipakai [detect]; diekspos untuk diagnostik test. */
    internal fun analyzeBands(bitmap: Bitmap): PanelAnalysis {
        val scale = analysisScale(bitmap)
        val pixels = pixelsOf(bitmap, scale)
        val width = pixels.bitmap.width
        val height = pixels.bitmap.height
        val rowWhite = FloatArray(height)
        val rowDark = FloatArray(height)
        val colWhite = FloatArray(width)
        val colDark = FloatArray(width)
        for (y in 0 until height) {
            var white = 0
            var dark = 0
            for (x in 0 until width) {
                val luma = luminance(pixels.pixels[y * width + x])
                if (luma >= WHITE_LUMA) white++
                if (luma <= DARK_LUMA) dark++
            }
            rowWhite[y] = white.toFloat() / width
            rowDark[y] = dark.toFloat() / width
        }
        for (x in 0 until width) {
            var white = 0
            var dark = 0
            for (y in 0 until height) {
                val luma = luminance(pixels.pixels[y * width + x])
                if (luma >= WHITE_LUMA) white++
                if (luma <= DARK_LUMA) dark++
            }
            colWhite[x] = white.toFloat() / height
            colDark[x] = dark.toFloat() / height
        }
        val result = PanelAnalysis(
            rowBands = bands(separators(rowWhite, rowDark), height),
            colBands = bands(separators(colWhite, colDark), width),
            analysisWidth = width,
            analysisHeight = height
        )
        if (pixels.bitmap !== bitmap) pixels.bitmap.recycle()
        return result
    }

    private fun analysisScale(bitmap: Bitmap): Float =
        if (max(bitmap.width, bitmap.height) > MAX_ANALYSIS_DIMENSION) {
            MAX_ANALYSIS_DIMENSION.toFloat() / max(bitmap.width, bitmap.height)
        } else {
            1f
        }

    private class Pixels(val bitmap: Bitmap, val pixels: IntArray)

    private fun pixelsOf(bitmap: Bitmap, scale: Float): Pixels {
        val image = if (scale == 1f) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return Pixels(image, pixels)
    }

    /** Baris/kolom pemisah (putih penuh atau gelap penuh), sudah digabung. */
    private fun separators(white: FloatArray, dark: FloatArray): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (index in white.indices) {
            val separator = white[index] >= GUTTER_WHITE_RATIO || dark[index] >= BORDER_DARK_RATIO
            if (separator && start < 0) start = index
            if (!separator && start >= 0) {
                runs += start until index
                start = -1
            }
        }
        if (start >= 0) runs += start until white.size
        return runs
    }

    /** Pita di antara pemisah; pemisah di tepi halaman tidak menghasilkan pita kosong. */
    private fun bands(separators: List<IntRange>, size: Int): List<Pair<Int, Int>> {
        if (separators.isEmpty()) return listOf(0 to size)
        val minBand = max(8, (size * MIN_BAND_FRACTION).toInt())
        val result = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        for (run in separators) {
            if (run.first - cursor >= minBand) result += cursor to run.first
            cursor = run.last + 1
        }
        if (size - cursor >= minBand) result += cursor to size
        return if (result.isEmpty()) listOf(0 to size) else result
    }

    /** True bila area panel nyaris tidak berisi gambar (celah palsu). */
    private fun isBlank(pixels: IntArray, width: Int, rect: Rect): Boolean {
        var samples = 0
        var ink = 0
        val stepX = max(1, rect.width() / 24)
        val stepY = max(1, rect.height() / 24)
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                samples++
                if (luminance(pixels[y * width + x]) < 200) ink++
                x += stepX
            }
            y += stepY
        }
        return samples == 0 || ink * 100 < samples
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
}
