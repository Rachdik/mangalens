package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.mangalens.ocr.RecognizedBlock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Satu balon teks yang terdeteksi pada gambar manga. */
data class DetectedBubble(
    val textBounds: Rect,
    val bubbleBounds: Rect,
    val text: String,
    val fillColor: Int,
    val textColor: Int
)

/**
 * Mengelompokkan blok OCR menjadi balon percakapan, lalu memperkirakan batas
 * balon dan warna latarnya.
 *
 * Pendekatan:
 * 1. Blok yang berdekatan (jarak antar baris/kolom kecil) digabung menjadi satu
 *    kelompok, karena satu balon sering terpecah menjadi beberapa blok OCR
 *    (apalagi teks Jepang vertikal).
 * 2. Warna latar balon diambil dari warna yang paling sering muncul di cincin
 *    di sekitar teks.
 * 3. Batas balon dicari dengan flood fill dari dalam teks selama warna piksel
 *    masih mirip warna latar. Garis tepi balon (hitam) otomatis menghentikan
 *    pencarian, sehingga area yang dihasilkan adalah bagian dalam balon.
 *
 * Semua perhitungan memakai salinan gambar yang diperkecil agar cukup cepat
 * dijalankan saat pengguna mengetuk bubble.
 */
object BubbleDetector {

    /** Batas piksel analisis; cukup detail untuk balon, tetap ringan. */
    private const val MAX_ANALYSIS_DIMENSION = 720

    /** Toleransi warna latar (per kanal) saat flood fill. */
    private const val COLOR_TOLERANCE = 30

    /** Jarak maksimum pertumbuhan dari kotak teks (fraksi dari sisi terpanjang). */
    private const val MAX_GROW_FACTOR = 0.9f

    fun detect(bitmap: Bitmap, blocks: List<RecognizedBlock>): List<DetectedBubble> {
        // Blok hanya berisi tanda baca/angka tunggal (nomor halaman, SFX) bukan
        // dialog dan membuat deteksi balon meleset.
        val usable = blocks.filter {
            it.text.count { ch -> ch.isLetterOrDigit() } >= 2 &&
                it.bounds.width() > 2 &&
                it.bounds.height() > 2
        }
        if (usable.isEmpty()) return emptyList()

        val groups = groupBlocks(usable)
        if (groups.isEmpty()) return emptyList()

        val scaled = scaleForAnalysis(bitmap)
        val image = scaled.first
        val scale = scaled.second
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)

        val result = groups.mapNotNull { group ->
            val union = Rect(
                group.blocks.minOf { it.bounds.left },
                group.blocks.minOf { it.bounds.top },
                group.blocks.maxOf { it.bounds.right },
                group.blocks.maxOf { it.bounds.bottom }
            ).toScaled(scale)

            val text = group.blocks.joinToString(" ") { it.text.trim() }.trim()
            if (text.isEmpty()) return@mapNotNull null

            val ring = ringColor(pixels, image.width, image.height, union)
            val fill = ring ?: Color.WHITE
            val bubble = growBubble(pixels, image.width, image.height, union, fill)
                ?: padRect(union, image.width, image.height)

            val textColor = if (luminance(fill) > 150) Color.BLACK else Color.WHITE
            DetectedBubble(
                textBounds = union.toOriginal(scale),
                bubbleBounds = bubble.toOriginal(scale),
                text = text,
                fillColor = fill,
                textColor = textColor
            )
        }

        if (scaled.third) image.recycle()
        return result
    }

    /** Warna dominan di cincin sekitar kotak teks (latarbalon). */
    fun ringColor(pixels: IntArray, width: Int, height: Int, rect: Rect): Int? {
        val ring = Rect(
            rect.left - RING_PADDING,
            rect.top - RING_PADDING,
            rect.right + RING_PADDING,
            rect.bottom + RING_PADDING
        ).clampTo(width, height)

        val histogram = HashMap<Int, Int>()
        for (y in ring.top until ring.bottom) {
            for (x in ring.left until ring.right) {
                if (rect.contains(x, y)) continue
                val color = pixels[y * width + x]
                val quantized = quantize(color)
                histogram[quantized] = (histogram[quantized] ?: 0) + 1
            }
        }
        return histogram.maxByOrNull { it.value }?.key
    }

    private fun growBubble(
        pixels: IntArray,
        width: Int,
        height: Int,
        text: Rect,
        fill: Int
    ): Rect? {
        val maxPad = (max(text.width(), text.height()) * MAX_GROW_FACTOR).toInt().coerceAtLeast(12)
        val limit = Rect(
            text.left - maxPad,
            text.top - maxPad,
            text.right + maxPad,
            text.bottom + maxPad
        ).clampTo(width, height)

        val seed = findSeed(pixels, width, text, fill) ?: return null
        if (!limit.contains(seed.first, seed.second)) return null

        val seen = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0
        queue[tail++] = seed.second * width + seed.first
        seen[seed.second * width + seed.first] = true

        var minX = seed.first
        var minY = seed.second
        var maxX = seed.first
        var maxY = seed.second
        var count = 0

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            count++
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y

            // Empat arah; batasi agar tidak melebar ke seluruh halaman.
            if (x > limit.left) visit(pixels, width, index - 1, x - 1, y, fill, limit, seen, queue, tail).let { tail = it }
            if (x < limit.right - 1) visit(pixels, width, index + 1, x + 1, y, fill, limit, seen, queue, tail).let { tail = it }
            if (y > limit.top) visit(pixels, width, index - width, x, y - 1, fill, limit, seen, queue, tail).let { tail = it }
            if (y < limit.bottom - 1) visit(pixels, width, index + width, x, y + 1, fill, limit, seen, queue, tail).let { tail = it }
        }

        if (count < max(64, text.width() * text.height() / 4)) return null

        val found = Rect(minX, minY, maxX + 1, maxY + 1)
        // Batas balon harus mencakup teksnya.
        return Rect(
            min(found.left, text.left),
            min(found.top, text.top),
            max(found.right, text.right),
            max(found.bottom, text.bottom)
        )
    }

    private fun visit(
        pixels: IntArray,
        width: Int,
        index: Int,
        x: Int,
        y: Int,
        fill: Int,
        limit: Rect,
        seen: BooleanArray,
        queue: IntArray,
        tail: Int
    ): Int {
        if (x < limit.left || x >= limit.right || y < limit.top || y >= limit.bottom) return tail
        if (seen[index]) return tail
        seen[index] = true
        if (!similar(pixels[index], fill)) return tail
        queue[tail] = index
        return tail + 1
    }

    private fun findSeed(pixels: IntArray, width: Int, text: Rect, fill: Int): Pair<Int, Int>? {
        val step = 2
        for (y in text.top until text.bottom step step) {
            for (x in text.left until text.right step step) {
                if (similar(pixels[y * width + x], fill)) return x to y
            }
        }
        // Cadangan: titik tepat di luar kotak teks.
        for (y in text.top until text.bottom step step) {
            for (x in text.left until text.right step step) {
                val candidate = pixels[y * width + x]
                if (luminance(candidate) > 200) return x to y
            }
        }
        return null
    }

    private fun groupBlocks(blocks: List<RecognizedBlock>): List<BlockGroup> {
        val parent = IntArray(blocks.size) { it }

        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootB] = rootA
        }

        for (i in blocks.indices) {
            for (j in i + 1 until blocks.size) {
                if (mergeable(blocks[i].bounds, blocks[j].bounds)) union(i, j)
            }
        }

        val grouped = LinkedHashMap<Int, MutableList<RecognizedBlock>>()
        blocks.forEachIndexed { index, block ->
            grouped.getOrPut(find(index)) { mutableListOf() }.add(block)
        }
        return grouped.values
            .map { BlockGroup(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** Dua blok dianggap satu balon bila jaraknya kecil dibanding tinggi huruf. */
    private fun mergeable(a: Rect, b: Rect): Boolean {
        val horizontalGap = max(0, max(a.left, b.left) - min(a.right, b.right))
        val verticalGap = max(0, max(a.top, b.top) - min(a.bottom, b.bottom))
        val reference = max(min(a.height(), b.height()), 12)
        val horizontalLimit = (reference * 1.4f).toInt()
        val verticalLimit = (reference * 1.2f).toInt()
        return horizontalGap <= horizontalLimit && verticalGap <= verticalLimit
    }

    /** Kotak cadangan bila flood fill gagal (balon dengan screentone/gradien). */
    private fun padRect(rect: Rect, width: Int, height: Int): Rect {
        val pad = (rect.height() * 0.28f).toInt().coerceIn(6, 56)
        return Rect(
            (rect.left - pad).coerceAtLeast(0),
            (rect.top - pad).coerceAtLeast(0),
            (rect.right + pad).coerceAtMost(width),
            (rect.bottom + pad).coerceAtMost(height)
        )
    }

    /** @return gambar analisis, faktor skala, dan apakah gambar perlu di-recycle. */
    private fun scaleForAnalysis(bitmap: Bitmap): Triple<Bitmap, Float, Boolean> {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_ANALYSIS_DIMENSION) return Triple(bitmap, 1f, false)

        val scale = MAX_ANALYSIS_DIMENSION.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        return Triple(scaled, scale, true)
    }

    private fun Rect.toScaled(scale: Float): Rect {
        if (scale == 1f) return Rect(this)
        return Rect(
            (left * scale).toInt(),
            (top * scale).toInt(),
            (right * scale).toInt(),
            (bottom * scale).toInt()
        )
    }

    private fun Rect.toOriginal(scale: Float): Rect {
        if (scale == 1f) return Rect(this)
        return Rect(
            (left / scale).toInt(),
            (top / scale).toInt(),
            (right / scale).toInt(),
            (bottom / scale).toInt()
        )
    }

    private fun Rect.clampTo(width: Int, height: Int): Rect = Rect(
        left.coerceIn(0, width - 1),
        top.coerceIn(0, height - 1),
        right.coerceIn(1, width),
        bottom.coerceIn(1, height)
    )

    private fun similar(color: Int, reference: Int): Boolean =
        abs(Color.red(color) - Color.red(reference)) <= COLOR_TOLERANCE &&
            abs(Color.green(color) - Color.green(reference)) <= COLOR_TOLERANCE &&
            abs(Color.blue(color) - Color.blue(reference)) <= COLOR_TOLERANCE

    private fun quantize(color: Int): Int =
        Color.rgb(
            Color.red(color) and 0xF0,
            Color.green(color) and 0xF0,
            Color.blue(color) and 0xF0
        )

    private fun luminance(color: Int): Int =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()

    private class BlockGroup(val blocks: List<RecognizedBlock>) {
        val left: Int = blocks.minOf { it.bounds.left }
        val top: Int = blocks.minOf { it.bounds.top }
    }

    private const val RING_PADDING = 4
}
