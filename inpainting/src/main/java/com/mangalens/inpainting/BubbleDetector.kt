package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.RecognizedBlock
import com.mangalens.ocr.TextOrientation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Seberapa yakin batas balon hasil deteksi; menentukan strategi penempatan. */
enum class BubbleConfidence { HIGH, MEDIUM, LOW }

/** Satu balon teks yang terdeteksi pada gambar manga. */
data class DetectedBubble(
    val textBounds: Rect,
    val bubbleBounds: Rect,
    val text: String,
    val fillColor: Int,
    val textColor: Int,
    /** ID stabil untuk kelompok teks ini (untuk cache & penggantian overlay). */
    val id: String = "",
    val readingOrder: Int = 0,
    val confidence: BubbleConfidence = BubbleConfidence.MEDIUM,
    /** Kotak baris teks asli; dipakai untuk mask yang tidak menghapus balon. */
    val lines: List<Rect> = emptyList()
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
        // Deteksi orientasi dominan dari blok (jika日本語)
        val dominantOrientation = detectDominantOrientation(blocks)
        return detectWithOrientation(bitmap, blocks, dominantOrientation)
    }

    private fun detectDominantOrientation(blocks: List<RecognizedBlock>): TextOrientation {
        val verticalCount = blocks.count { it.orientation == TextOrientation.VERTICAL }
        val horizontalCount = blocks.count { it.orientation == TextOrientation.HORIZONTAL }
        return when {
            verticalCount > horizontalCount -> TextOrientation.VERTICAL
            horizontalCount > verticalCount -> TextOrientation.HORIZONTAL
            else -> TextOrientation.UNKNOWN
        }
    }

    private fun detectWithOrientation(bitmap: Bitmap, blocks: List<RecognizedBlock>, orientation: TextOrientation): List<DetectedBubble> {
        val usable = blocks.filter {
            it.text.count { ch -> ch.isLetterOrDigit() } >= 2 &&
                it.bounds.width() > 2 &&
                it.bounds.height() > 2
        }
        if (usable.isEmpty()) {
            val relaxed = blocks.filter {
                it.text.isNotBlank() &&
                    it.bounds.width() > 1 &&
                    it.bounds.height() > 1
            }
            if (relaxed.isEmpty()) return emptyList()
            return detectBubbles(bitmap, relaxed, orientation)
        }
        return detectBubbles(bitmap, usable, orientation)
    }

    private fun detectBubbles(bitmap: Bitmap, usable: List<RecognizedBlock>, orientation: TextOrientation): List<DetectedBubble> {
        val scaled = scaleForAnalysis(bitmap)
        val image = scaled.first
        val scale = scaled.second
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)

        val groups = groupBlocks(usable, pixels, image.width, image.height, scale, orientation)
        if (groups.isEmpty()) {
            if (scaled.third) image.recycle()
            return emptyList()
        }

        val result = groups.mapNotNull { group ->
            val union = Rect(
                group.blocks.minOf { it.bounds.left },
                group.blocks.minOf { it.bounds.top },
                group.blocks.maxOf { it.bounds.right },
                group.blocks.maxOf { it.bounds.bottom }
            ).toScaled(scale)

            // Satu balon Jepang sering terpecah menjadi beberapa blok OCR per
            // kolom. Urutan baca manga vertikal adalah kanan -> kiri, sedangkan
            // ML Kit memberi urutan kiri -> kanan; tanpa pengurutan ini teks
            // satu balon tergabung terbalik sebelum diterjemahkan.
            val orderedBlocks = if (orientation == TextOrientation.VERTICAL) {
                group.blocks.sortedByDescending { it.bounds.left }
            } else {
                group.blocks
            }
            val text = orderedBlocks.joinToString(" ") { it.text.trim() }.trim()
            if (text.isEmpty()) return@mapNotNull null

            val ring = ringColor(pixels, image.width, image.height, union)
            val fill = ring ?: Color.WHITE
            val grown = growBubble(pixels, image.width, image.height, union, fill)
            val bubble = grown ?: padRect(union, image.width, image.height)
            val textArea = union.width().toLong() * union.height()
            val bubbleArea = bubble.width().toLong() * bubble.height()

            // HIGH: flood fill menemukan balon yang rapat di sekitar teks.
            // MEDIUM: balon ditemukan tetapi area jauh lebih besar dari teks.
            // LOW: tidak ada balon; jangan memaksakan kotak tebakan.
            val confidence = when {
                grown == null -> BubbleConfidence.LOW
                textArea > 0 && bubbleArea <= textArea * 3 -> BubbleConfidence.HIGH
                else -> BubbleConfidence.MEDIUM
            }

            // `block.lines` dan `block.bounds` sudah dalam koordinat bitmap asli
            // (OCR mengembalikannya pada ukuran asli). Sebelumnya baris ini memanggil
            // toOriginal(scale) sehingga koordinat dibagi skala DUA kali dan mask
            // inpainting jatuh di tempat yang salah (teks asli tidak pernah terhapus).
            val lines = orderedBlocks
                .flatMap { block -> block.lines.ifEmpty { listOf(block.bounds) } }
                .map { Rect(it) }
            val idText = text + "@" + union.left + "," + union.top
            val id = "b" + Integer.toHexString(idText.hashCode())

            val textColor = if (luminance(fill) > 150) Color.BLACK else Color.WHITE
            DetectedBubble(
                textBounds = union.toOriginal(scale),
                bubbleBounds = if (confidence == BubbleConfidence.LOW) union.toOriginal(scale) else bubble.toOriginal(scale),
                text = text,
                fillColor = fill,
                textColor = textColor,
                id = id,
                confidence = confidence,
                lines = lines
            )
        }.mapIndexed { index, bubble -> bubble.copy(readingOrder = index + 1) }

        if (scaled.third) image.recycle()
        return result
    }

    /**
     * Warna latar dan warna teks untuk blok OCR yang tidak berada di dalam
     * balon (kotak narasi, teks di luar kotak, tulisan pada gambar).
     * Warna diambil dari cincin di sekitar blok agar tempelan menyatu dengan
     * latar aslinya; teks dipilih hitam atau putih sesuai kecerahan latar.
     */
    fun surroundings(bitmap: Bitmap, textBounds: Rect): Pair<Int, Int> {
        val pad = (max(textBounds.width(), textBounds.height()) * 0.22f)
            .toInt()
            .coerceIn(4, 40)
        val ring = Rect(
            textBounds.left - pad,
            textBounds.top - pad,
            textBounds.right + pad,
            textBounds.bottom + pad
        ).clampTo(bitmap.width, bitmap.height)

        val histogram = HashMap<Int, Int>()
        val stepX = max(1, ring.width() / 24)
        val stepY = max(1, ring.height() / 24)
        var y = ring.top
        while (y < ring.bottom) {
            var x = ring.left
            while (x < ring.right) {
                if (!textBounds.contains(x, y)) {
                    val color = quantize(bitmap.getPixel(x, y))
                    histogram[color] = (histogram[color] ?: 0) + 1
                }
                x += stepX
            }
            y += stepY
        }

        val fill = histogram.maxByOrNull { it.value }?.key ?: Color.WHITE
        val textColor = if (luminance(fill) > 150) Color.BLACK else Color.WHITE
        return fill to textColor
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

    private fun groupBlocks(
        blocks: List<RecognizedBlock>,
        pixels: IntArray,
        width: Int,
        height: Int,
        scale: Float,
        orientation: TextOrientation
    ): List<BlockGroup> {
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
                if (mergeable(blocks[i].bounds, blocks[j].bounds, pixels, width, height, scale, orientation)) {
                    union(i, j)
                }
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

    /**
     * Dua blok dianggap satu balon bila jaraknya kecil dibanding tinggi huruf
     * DAN tidak ada garis gambar/tepi balon di antara keduanya. Tanpa cek garis
     * ini, dua balon bersebelahan tergabung menjadi satu kotak besar yang
     * tempelannya menutupi beberapa balon sekaligus.
     */
    private fun mergeable(
        a: Rect,
        b: Rect,
        pixels: IntArray,
        width: Int,
        height: Int,
        scale: Float,
        orientation: TextOrientation
    ): Boolean {
        // Ukuran acuan jarak HARUS ukuran karakter, bukan panjang blok.
        // Pada teks vertikal satu blok adalah satu kolom (tinggi ~140 px, lebar
        // ~20 px). Memakai tinggi sebagai acuan membuat balon di panel berbeda
        // (jarak vertikal 109 px) dianggap satu balon hanya karena kolomnya
        // tinggi — itulah penyebab dua balon tergabung sebelum Phase 9K.
        // Untuk tategaki: lebar kolom = ukuran karakter; jarak antar-kolom dalam
        // satu balon ~1,6 karakter, sedangkan dua balon bertumpuk berjarak lebih
        // dari 0,5 karakter.
        val verticalText = orientation == TextOrientation.VERTICAL
        val characterSize = if (verticalText) {
            max(min(a.width(), b.width()), 8)
        } else {
            max(min(a.height(), b.height()), 12)
        }
        val horizontalGap = max(0, max(a.left, b.left) - min(a.right, b.right))
        val verticalGap = max(0, max(a.top, b.top) - min(a.bottom, b.bottom))
        val horizontalLimit = (characterSize * if (verticalText) 1.6f else 1.1f).toInt()
        val verticalLimit = (characterSize * if (verticalText) 0.5f else 0.9f).toInt()
        if (horizontalGap > horizontalLimit || verticalGap > verticalLimit) return false

        // Jangan gabungkan dua area yang bersama-sama membentuk blok raksasa:
        // itu biasanya dua panel/balon berbeda, bukan satu dialog.
        val unionW = max(a.right, b.right) - min(a.left, b.left)
        val unionH = max(a.bottom, b.bottom) - min(a.top, b.top)
        val pageArea = width.toLong() * height
        if (unionW.toLong() * unionH > pageArea * MAX_GROUP_PAGE_FRACTION) return false

        // Balon bersebelahan: periksa garis vertikal di celah di antara teks.
        if (horizontalGap > 0 && verticalGap == 0) {
            val stripLeft = min(a.right, b.right)
            val stripRight = max(a.left, b.left)
            val top = max(a.top, b.top)
            val bottom = min(a.bottom, b.bottom)
            if (top < bottom &&
                hasInk(pixels, width, height, scale, stripLeft, top, stripRight, bottom)
            ) {
                return false
            }
        }

        // Balon bertumpuk: periksa garis horizontal di celah di antara teks.
        if (verticalGap > 0 && horizontalGap == 0) {
            val stripTop = min(a.bottom, b.bottom)
            val stripBottom = max(a.top, b.top)
            val left = max(a.left, b.left)
            val right = min(a.right, b.right)
            if (left < right &&
                hasInk(pixels, width, height, scale, left, stripTop, right, stripBottom)
            ) {
                return false
            }
        }

        return true
    }

    /**
     * True bila sebagian besar baris di area celah mengandung piksel gelap —
     * tanda ada tepi balon/garis gambar yang memisahkan dua kelompok teks.
     */
    private fun hasInk(
        pixels: IntArray,
        width: Int,
        height: Int,
        scale: Float,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        val l = (left * scale).toInt().coerceIn(0, width - 1)
        val r = (right * scale).toInt().coerceIn(l + 1, width)
        val t = (top * scale).toInt().coerceIn(0, height - 1)
        val b = (bottom * scale).toInt().coerceIn(t + 1, height)

        val stepY = max(1, (b - t) / 16)
        val stepX = max(1, (r - l) / 4)
        var rows = 0
        var rowsWithInk = 0
        var y = t
        while (y < b) {
            var samples = 0
            var dark = 0
            var x = l
            while (x < r) {
                samples++
                if (luminance(pixels[y * width + x]) < INK_LUMA) dark++
                x += stepX
            }
            if (samples > 0) {
                rows++
                if (dark * 2 >= samples) rowsWithInk++
            }
            y += stepY
        }
        return rows > 0 && rowsWithInk * 10 >= rows * 6
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

    /** Di bawah kecerahan ini piksel dianggap tinta/garis balon. */
    private const val INK_LUMA = 110

    /** Batas luas gabungan satu kelompok terhadap luas halaman analisis. */
    private const val MAX_GROUP_PAGE_FRACTION = 0.22
}
