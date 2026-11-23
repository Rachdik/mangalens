package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Inpainting engine berbasis sampling ring + interpolasi.
 *
 * Pendekatan:
 * 1. Ambil sampel warna dari cincin di sekitar area teks
 * 2. Isi area teks dengan interpolasi weighted dari sampel tersebut
 * 3. Softening di boundary agar tidak ada garis tajam
 *
 * Keterbatasan:
 * - Tidak reconstruct screentone atau pola kompleks
 * - Latar bersih (solid color/gradien halus) hasilnya baik
 * - Latar dengan garis-garis atau pola akan terlihat blur
 * - Untuk hasil terbaik, gambar asli harus punya latar yang cukup homogen
 *
 * Implementasi ini SENGaja SIDIK: tidak pakai OpenCV native karena
 * - OpenCV Android ~10-20 MB tambahan di APK
 * - Native lib per ABI memperbesar APK
 * - Untuk tujuan ini (tutup teks manga di balon berwarna), pendekatan sederhana sudah cukup
 */
class BasicInpaintingEngine(
    private val ringRadius: Int = 8,
    private val blurRadius: Int = 3,
    private val softenEdges: Boolean = true
) : InpaintingEngine {

    override fun inpaint(source: Bitmap, mask: Bitmap): Bitmap {
        if (source.width != mask.width || source.height != mask.height) {
            throw IllegalArgumentException(
                "Source dan mask harus sama ukurannya"
            )
        }
        val w = source.width
        val h = source.height
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        result.getPixels(pixels, 0, w, 0, 0, w, h)
        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)

        val bbox = boundingBoxOf(maskPixels, w, h) ?: return result
        val ringSamples = sampleRing(pixels, w, h, bbox, ringRadius)

        if (ringSamples.isEmpty()) {
            fillRect(pixels, w, h, bbox, Color.WHITE)
        } else {
            fillFromSamples(pixels, w, h, bbox, maskPixels, ringSamples)
        }

        if (softenEdges) {
            softenBoundary(pixels, w, h, bbox, maskPixels, blurRadius)
        }

        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun sampleRing(pixels: IntArray, w: Int, h: Int, bbox: Rect, radius: Int): List<Sample> {
        val samples = mutableListOf<Sample>()
        val left = (bbox.left - radius).coerceAtLeast(0)
        val top = (bbox.top - radius).coerceAtLeast(0)
        val right = (bbox.right + radius).coerceAtMost(w)
        val bottom = (bbox.bottom + radius).coerceAtMost(h)
        val step = kotlin.math.max(1, radius / 2)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (x >= bbox.left && x < bbox.right && y >= bbox.top && y < bbox.bottom) {
                    x += step
                    continue
                }
                val idx = y * w + x
                val color = pixels[idx]
                if (Color.alpha(color) > 128) {
                    samples.add(Sample(x, y, color))
                }
                x += step
            }
            y += step
        }
        return samples
    }

    private fun fillFromSamples(pixels: IntArray, w: Int, h: Int, bbox: Rect, maskPixels: IntArray, samples: List<Sample>) {
        val maxDist = (bbox.width() + bbox.height()).toDouble()
        var y = bbox.top
        while (y < bbox.bottom) {
            var x = bbox.left
            while (x < bbox.right) {
                val idx = y * w + x
                if (maskPixels[idx] == 0) { x++; continue }
                var totalWeight = 0.0
                var totalR = 0.0
                var totalG = 0.0
                var totalB = 0.0
                val maxS = kotlin.math.min(8, samples.size)
                for (i in 0 until maxS) {
                    val s = samples[i]
                    val dx = x - s.x
                    val dy = y - s.y
                    val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                    if (dist > maxDist) continue
                    val weight = 1.0 / (dist + 1.0)
                    totalWeight += weight
                    totalR += Color.red(s.color) * weight
                    totalG += Color.green(s.color) * weight
                    totalB += Color.blue(s.color) * weight
                }
                if (totalWeight > 0) {
                    pixels[idx] = Color.argb(
                        255,
                        (totalR / totalWeight).roundToInt().coerceIn(0, 255),
                        (totalG / totalWeight).roundToInt().coerceIn(0, 255),
                        (totalB / totalWeight).roundToInt().coerceIn(0, 255)
                    )
                }
                x++
            }
            y++
        }
    }

    private fun softenBoundary(pixels: IntArray, w: Int, h: Int, bbox: Rect, maskPixels: IntArray, radius: Int) {
        val halfR = radius / 2
        val blurred = pixels.copyOf()
        var y = (bbox.top - halfR).coerceAtLeast(0)
        while (y < (bbox.bottom + halfR).coerceAtMost(h)) {
            var x = (bbox.left - halfR).coerceAtLeast(0)
            while (x < (bbox.right + halfR).coerceAtMost(w)) {
                val idx = y * w + x
                if (maskPixels[idx] != 0) { x++; continue }
                var count = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var yy = kotlin.math.max(0, y - 1)
                while (yy <= kotlin.math.min(h - 1, y + 1)) {
                    var xx = kotlin.math.max(0, x - 1)
                    while (xx <= kotlin.math.min(w - 1, x + 1)) {
                        val iidx = yy * w + xx
                        if (maskPixels[iidx] == 0) {
                            sumR += Color.red(pixels[iidx])
                            sumG += Color.green(pixels[iidx])
                            sumB += Color.blue(pixels[iidx])
                            count++
                        }
                        xx++
                    }
                    yy++
                }
                if (count > 0) {
                    blurred[idx] = Color.argb(
                        255,
                        (sumR / count).coerceIn(0, 255),
                        (sumG / count).coerceIn(0, 255),
                        (sumB / count).coerceIn(0, 255)
                    )
                }
                x++
            }
            y++
        }
        y = bbox.top
        while (y < bbox.bottom) {
            var x = bbox.left
            while (x < bbox.right) {
                val idx = y * w + x
                if (maskPixels[idx] != 0) {
                    pixels[idx] = blendColors(pixels[idx], blurred[idx], 0.75)
                }
                x++
            }
            y++
        }
    }

    private fun blendColors(a: Int, b: Int, factor: Double): Int {
        val fa = factor
        val fb = 1.0 - factor
        return Color.argb(
            255,
            (Color.red(a) * fa + Color.red(b) * fb).roundToInt().coerceIn(0, 255),
            (Color.green(a) * fa + Color.green(b) * fb).roundToInt().coerceIn(0, 255),
            (Color.blue(a) * fa + Color.blue(b) * fb).roundToInt().coerceIn(0, 255)
        )
    }

    private fun fillRect(pixels: IntArray, w: Int, h: Int, rect: Rect, color: Int) {
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                if (x < w && y < h) pixels[y * w + x] = color
                x++
            }
            y++
        }
    }

    private fun boundingBoxOf(maskPixels: IntArray, w: Int, h: Int): Rect? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var found = false
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (maskPixels[y * w + x] != 0) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    found = true
                }
                x++
            }
            y++
        }
        return if (found) Rect(minX, minY, maxX + 1, maxY + 1) else null
    }

    private data class Sample(val x: Int, val y: Int, val color: Int)
}
