package com.mangalens.imageprocessing.util

import android.graphics.Bitmap

/**
 * dHash 64-bit: gambar diperkecil ke 9x8 grayscale, lalu setiap piksel
 * dibandingkan dengan tetangga kanannya. Dipakai sebagai kunci cache agar
 * gambar yang sama tidak diproses ulang.
 */
object ImageHasher {

    private const val WIDTH = 9
    private const val HEIGHT = 8

    fun dHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, WIDTH, HEIGHT, true)
        val pixels = IntArray(WIDTH * HEIGHT)
        scaled.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        if (scaled !== bitmap) scaled.recycle()

        var hash = 0L
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH - 1) {
                val left = luminance(pixels[y * WIDTH + x])
                val right = luminance(pixels[y * WIDTH + x + 1])
                hash = hash shl 1
                if (left > right) hash = hash or 1L
            }
        }
        return hash
    }

    fun toHex(hash: Long): String = hash.toULong().toString(16).padStart(16, '0')

    private fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
