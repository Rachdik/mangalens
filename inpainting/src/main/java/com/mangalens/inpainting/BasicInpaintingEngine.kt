package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Inpainting sederhana berbasis interpolasi warna dari piksel asli di sekitar
 * area yang di-mask.
 *
 * Cara kerja:
 * 1. Untuk setiap baris/kolom di dalam kotak mask, cari piksel asli terdekat di
 *    sisi kiri, kanan, atas, dan bawah (di luar area mask).
 * 2. Warna setiap piksel di dalam mask dihitung dari rata-rata berbobot empat
 *    warna sisi tersebut (bobot ~ 1/jarak).
 * 3. Tepi mask dilembutkan dengan rata-rata piksel asli di sekitarnya.
 *
 * Ini benar-benar MENGISI area dengan warna dari gambar aslinya, bukan
 * menempelkan satu warna solid — tetapi bukan rekonstruksi semantik.
 *
 * KETERBATASAN (jujur, belum diuji pada perangkat):
 * - Latar polos atau gradien halus: hasil biasanya menyatu.
 * - Screentone / pola garis / tekstur: hasilnya akan tampak blur/halus karena
 *   pola tidak direkonstruksi. Tidak setara Content-Aware Fill atau inpainting AI.
 * - Gambar dengan garis gambar (outline karakter) yang melewati area teks akan
 *   kehilangan garis tersebut di area itu.
 * - Tidak ada OpenCV/native library: ukuran APK tidak bertambah dan tidak ada
 *   dependensi ABI tambahan, dengan konsekuensi kualitas di atas.
 */
class BasicInpaintingEngine(
    /** Radius minimum pencarian piksel asli di luar kotak mask. */
    private val searchRadius: Int = 6,
    /** Ukuran kernel pelembutan tepi. */
    private val blurRadius: Int = 3,
    private val softenEdges: Boolean = true
) : InpaintingEngine {

    override fun inpaint(source: Bitmap, mask: Bitmap): Bitmap {
        require(source.width == mask.width && source.height == mask.height) {
            "Source dan mask harus sama ukurannya"
        }

        val width = source.width
        val height = source.height
        val result = source.copy(Bitmap.Config.ARGB_8888, true)

        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)
        val maskPixels = IntArray(width * height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        val bbox = boundingBoxOf(maskPixels, width, height) ?: return result
        fillFromEdges(pixels, maskPixels, width, height, bbox)

        if (softenEdges) {
            softenBoundary(pixels, maskPixels, width, height, bbox, blurRadius)
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Isi area mask dengan interpolasi dari piksel asli terdekat di empat sisi.
     * Semua warna berasal dari gambar aslinya, sehingga latar solid dan gradien
     * tetap menyatu.
     */
    private fun fillFromEdges(
        pixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        height: Int,
        bbox: Rect
    ) {
        val fallback = averageColor(pixels, maskPixels)
        val pad = searchRadius + 1

        val leftColor = IntArray(height)
        val rightColor = IntArray(height)
        for (y in bbox.top until bbox.bottom) {
            leftColor[y] = nearestHorizontal(pixels, maskPixels, width, y, bbox.left - pad, -1)
            rightColor[y] = nearestHorizontal(pixels, maskPixels, width, y, bbox.right + pad - 1, 1)
        }

        val topColor = IntArray(width)
        val bottomColor = IntArray(width)
        for (x in bbox.left until bbox.right) {
            topColor[x] = nearestVertical(pixels, maskPixels, width, height, x, bbox.top - pad, -1)
            bottomColor[x] = nearestVertical(pixels, maskPixels, width, height, x, bbox.bottom + pad - 1, 1)
        }

        for (y in bbox.top until bbox.bottom) {
            for (x in bbox.left until bbox.right) {
                val index = y * width + x
                if (!isMasked(maskPixels[index])) continue

                val weightLeft = 1.0 / (x - bbox.left + 1)
                val weightRight = 1.0 / (bbox.right - x)
                val weightTop = 1.0 / (y - bbox.top + 1)
                val weightBottom = 1.0 / (bbox.bottom - y)

                var totalWeight = 0.0
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0

                fun add(color: Int, weight: Double) {
                    if (color == 0) return
                    totalWeight += weight
                    sumR += Color.red(color) * weight
                    sumG += Color.green(color) * weight
                    sumB += Color.blue(color) * weight
                }

                add(leftColor[y], weightLeft)
                add(rightColor[y], weightRight)
                add(topColor[x], weightTop)
                add(bottomColor[x], weightBottom)

                pixels[index] = if (totalWeight > 0.0) {
                    Color.argb(
                        255,
                        (sumR / totalWeight).roundToInt().coerceIn(0, 255),
                        (sumG / totalWeight).roundToInt().coerceIn(0, 255),
                        (sumB / totalWeight).roundToInt().coerceIn(0, 255)
                    )
                } else {
                    fallback
                }
            }
        }
    }

    /** Piksel asli terdekat pada baris y, mulai dari x ke arah step. */
    private fun nearestHorizontal(
        pixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        y: Int,
        from: Int,
        step: Int
    ): Int {
        var x = from
        val limit = if (step < 0) 0 else width - 1
        while (if (step < 0) x >= limit else x <= limit) {
            if (x in 0 until width) {
                val index = y * width + x
                if (!isMasked(maskPixels[index])) {
                    val color = pixels[index]
                    if (Color.alpha(color) > 16) return color
                }
            }
            x += step
        }
        return 0
    }

    /** Piksel asli terdekat pada kolom x, mulai dari y ke arah step. */
    private fun nearestVertical(
        pixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        from: Int,
        step: Int
    ): Int {
        var y = from
        val limit = if (step < 0) 0 else height - 1
        while (if (step < 0) y >= limit else y <= limit) {
            if (y in 0 until height) {
                val index = y * width + x
                if (!isMasked(maskPixels[index])) {
                    val color = pixels[index]
                    if (Color.alpha(color) > 16) return color
                }
            }
            y += step
        }
        return 0
    }

    /**
     * Rata-rata warna piksel asli (di luar mask) sebagai cadangan bila tidak ada
     * piksel tetangga yang bisa dipakai.
     */
    private fun averageColor(pixels: IntArray, maskPixels: IntArray): Int {
        var count = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var index = 0
        while (index < pixels.size) {
            if (!isMasked(maskPixels[index])) {
                val color = pixels[index]
                if (Color.alpha(color) > 16) {
                    sumR += Color.red(color)
                    sumG += Color.green(color)
                    sumB += Color.blue(color)
                    count++
                }
            }
            index += 7 // sampling jarang agar cepat
        }
        if (count == 0) return Color.WHITE
        return Color.argb(
            255,
            (sumR / count).toInt().coerceIn(0, 255),
            (sumG / count).toInt().coerceIn(0, 255),
            (sumB / count).toInt().coerceIn(0, 255)
        )
    }

    /**
     * Lembutkan batas mask: warna di dalam mask dicampur dengan rata-rata piksel
     * asli di sekelilingnya agar tidak ada garis tegas antara area yang diisi dan
     * gambar aslinya.
     */
    private fun softenBoundary(
        pixels: IntArray,
        maskPixels: IntArray,
        width: Int,
        height: Int,
        bbox: Rect,
        radius: Int
    ) {
        val half = max(1, radius / 2)
        val snapshot = pixels.copyOf()
        val blendArea = Rect(
            (bbox.left - half).coerceAtLeast(0),
            (bbox.top - half).coerceAtLeast(0),
            (bbox.right + half).coerceAtMost(width),
            (bbox.bottom + half).coerceAtMost(height)
        )

        for (y in blendArea.top until blendArea.bottom) {
            for (x in blendArea.left until blendArea.right) {
                val index = y * width + x
                if (!isMasked(maskPixels[index])) continue

                var count = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (yy in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (xx in max(0, x - 1)..min(width - 1, x + 1)) {
                        val neighbour = yy * width + xx
                        if (!isMasked(maskPixels[neighbour])) {
                            val color = snapshot[neighbour]
                            sumR += Color.red(color)
                            sumG += Color.green(color)
                            sumB += Color.blue(color)
                            count++
                        }
                    }
                }
                if (count > 0) {
                    val surrounding = Color.argb(
                        255,
                        sumR / count,
                        sumG / count,
                        sumB / count
                    )
                    pixels[index] = blendColors(pixels[index], surrounding, SOFTEN_FACTOR)
                }
            }
        }
    }

    private fun blendColors(a: Int, b: Int, factor: Double): Int {
        val inverse = 1.0 - factor
        return Color.argb(
            255,
            (Color.red(a) * factor + Color.red(b) * inverse).roundToInt().coerceIn(0, 255),
            (Color.green(a) * factor + Color.green(b) * inverse).roundToInt().coerceIn(0, 255),
            (Color.blue(a) * factor + Color.blue(b) * inverse).roundToInt().coerceIn(0, 255)
        )
    }


    /**
     * Piksel mask yang harus diisi adalah piksel TERANG (putih), sesuai
     * konvensi [MangaPageTranslator.createMask]: putih = area teks, hitam =
     * area yang dipertahankan.
     *
     * Sebelumnya kode mengecek `maskPixels[i] != 0`. Itu salah karena hitam
     * opaque bernilai 0xFF000000 (bukan 0), sehingga seluruh halaman dianggap
     * area mask dan seluruh gambar ikut diisi ulang.
     */
    private fun isMasked(color: Int): Boolean {
        val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return luminance > MASK_LUMINANCE_THRESHOLD
    }

    private fun boundingBoxOf(maskPixels: IntArray, width: Int, height: Int): Rect? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var found = false
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                if (isMasked(maskPixels[rowStart + x])) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    found = true
                }
            }
        }
        return if (found) Rect(minX, minY, maxX + 1, maxY + 1) else null
    }

    private companion object {
        /** Bobot warna hasil isi terhadap warna sekitarnya saat melembutkan tepi. */
        const val SOFTEN_FACTOR = 0.75

        /** Ambang kecerahan mask: di atas ini dianggap area teks. */
        const val MASK_LUMINANCE_THRESHOLD = 127
    }
}
