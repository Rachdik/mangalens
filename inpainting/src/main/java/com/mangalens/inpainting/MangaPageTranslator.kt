package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.OcrEngine
import com.mangalens.ocr.RecognizedBlock
import com.mangalens.ocr.ScriptDetector
import com.mangalens.translation.MlKitTranslationProvider
import com.mangalens.translation.TranslateResult
import com.mangalens.translation.TokenUsage
import com.mangalens.translation.TranslationProvider
import com.mangalens.translation.TranslationRequest
import kotlin.math.max
import kotlin.math.min

/** Tahap pipeline yang bisa ditampilkan sebagai status ke pengguna. */
enum class TranslationStage { RECOGNIZING, GROUPING, TRANSLATING, INPAINTING }

/** Hasil terjemahan satu balon, lengkap dengan posisi & warna balon aslinya. */
data class TranslatedBubble(
    /** Area balon penuh; hanya batas aman, bukan area yang dicat penuh. */
    val bounds: Rect,
    /** Area teks asli; tempelan dibuat seukuran ini agar tidak menutupi gambar. */
    val textBounds: Rect,
    val sourceText: String,
    val targetText: String,
    val fillColor: Int,
    val textColor: Int,
    /** ID stabil kelompok teks; mencegah hasil lama dipakai untuk konten baru. */
    val id: String = "",
    val readingOrder: Int = 0,
    val confidence: BubbleConfidence = BubbleConfidence.MEDIUM,
    /** Kotak baris teks asli untuk mask inpainting. */
    val textLines: List<Rect> = emptyList()
)

/**
 * Hasil satu halaman/layar. Angka diagnostik ikut dikembalikan supaya UI bisa
 * memberi tahu pengguna apa yang sebenarnya terjadi (0 blok OCR? 0 balon?).
 */
data class PageTranslation(
    val bubbles: List<TranslatedBubble>,
    val ocrBlocks: Int,
    val detectedBubbles: Int,
    val sourceTag: String,
    val targetTag: String = "",
    /** Provider yang benar-benar dipakai (bukan sekadar pilihan tersimpan). */
    val providerName: String = "",
    val usage: TokenUsage? = null
)

/**
 * Alur terjemahan satu layar: OCR -> deteksi balon -> terjemahkan.
 *
 * Semua teks yang terbaca diterjemahkan, bukan hanya yang berada di dalam
 * balon: blok yang tidak terdeteksi sebagai balon (kotak narasi, tulisan di
 * luar kotak, teks pada gambar) tetap diterjemahkan dan ditempel pada posisi
 * bloknya sendiri.
 *
 * Bahasa sumber ditentukan dari teks yang benar-benar terbaca, kecuali pengguna
 * memilih bahasa sumber secara eksplisit.
 *
 * Provider dibuat lewat [providerFactory] dan di-cache per bahasa sehingga
 * model ML Kit tidak dimuat ulang setiap ketukan.
 */
class MangaPageTranslator(
    private val ocr: OcrEngine,
    /** (kode bahasa sumber, kode bahasa tujuan) -> provider. */
    private val providerFactory: (String, String) -> TranslationProvider
) {

    /**
     * Tahap pemrosesan, dilaporkan ke UI agar pengguna melihat kemajuan
     * (bukan layar diam tanpa penjelasan). Dipanggil dari thread pipeline.
     */
    var onStage: ((TranslationStage) -> Unit)? = null

    private var provider: TranslationProvider? = null
    private var providerKey: String? = null

    suspend fun translate(
        bitmap: Bitmap,
        script: LanguageScript,
        sourceTag: String = "auto",
        targetTag: String = "id"
    ): PageTranslation {
        onStage?.invoke(TranslationStage.RECOGNIZING)
        val blocks = ocr.recognize(bitmap, script)
        if (blocks.isEmpty()) {
            return PageTranslation(emptyList(), 0, 0, ScriptDetector.languageTag(script), targetTag)
        }

        // Blok tanpa huruf/angka yang cukup (garis, titik, nomor halaman)
        // bukan dialog dan hanya menambah derau.
        val usable = blocks.filter {
            it.text.count { ch -> ch.isLetterOrDigit() } >= 2 &&
                it.bounds.width() > 2 &&
                it.bounds.height() > 2
        }
        if (usable.isEmpty()) {
            return PageTranslation(emptyList(), 0, 0, ScriptDetector.languageTag(script), targetTag)
        }

        val joined = usable.joinToString(" ") { it.text }
        val detected = ScriptDetector.languageTagOf(joined)
        val tag = if (sourceTag.isBlank() || sourceTag == "auto") detected else sourceTag

        onStage?.invoke(TranslationStage.GROUPING)
        val bubbles = BubbleDetector.detect(bitmap, usable)
        val regions = buildRegions(bitmap, usable, bubbles)
        Log.d(
            TAG,
            "translate blocks=${blocks.size} usable=${usable.size} bubbles=${bubbles.size} " +
                "regions=${regions.size} tag=$tag target=$targetTag"
        )
        if (regions.isEmpty() || tag == targetTag) {
            // Bahasa sumber sama dengan tujuan: tidak ada yang perlu diterjemahkan.
            return PageTranslation(emptyList(), usable.size, bubbles.size, tag, targetTag)
        }

        val translationProvider = providerFor(tag, targetTag)
        val providerName = translationProvider.displayName
        Log.d(
            TAG,
            "provider=$providerName source=$tag target=$targetTag regions=${regions.size}"
        )
        if (!translationProvider.prepare()) {
            throw IllegalStateException(
                "Provider ${translationProvider.displayName} belum siap. " +
                    "Untuk LLM, buka pengaturan provider lalu simpan kunci API; " +
                    "untuk ML Kit, sambungkan internet sekali lalu ketuk bubble lagi."
            )
        }

        val requests = regions.mapIndexed { index, region ->
            // Konteks hanya dialog sebelumnya; hasil tiap bubble tetap terpisah.
            val context = if (index == 0) {
                null
            } else {
                regions.subList(max(0, index - 2), index)
                    .joinToString(" / ") { it.text }
                    .take(600)
                    .ifBlank { null }
            }
            TranslationRequest(region.id, region.text, context)
        }

        onStage?.invoke(TranslationStage.TRANSLATING)
        return when (val result = translationProvider.translate(requests)) {
            is TranslateResult.Success -> {
                val translated = regions.mapNotNull { region ->
                    val target = result.texts[region.id]?.trim().orEmpty()
                    if (target.isEmpty() || target.equals(region.text, ignoreCase = true)) {
                        null
                    } else {
                        TranslatedBubble(
                            bounds = region.bounds,
                            textBounds = region.textBounds,
                            sourceText = region.text,
                            targetText = target,
                            fillColor = region.fillColor,
                            textColor = region.textColor,
                            id = region.id,
                            readingOrder = region.readingOrder,
                            confidence = region.confidence,
                            textLines = region.lines
                        )
                    }
                }
                Log.d(TAG, "translated bubbles=${translated.size} provider=$providerName")
                PageTranslation(
                    translated,
                    usable.size,
                    bubbles.size,
                    tag,
                    targetTag,
                    providerName,
                    result.usage
                )
            }

            is TranslateResult.Failure -> throw IllegalStateException(result.message)
        }
    }

    /**
     * Terjemahkan sekaligus hapus teks asli dengan inpainting.
     *
     * Bitmap masukan tetap milik pemanggil; hasilnya bitmap BARU (salinan yang
     * sudah diinpainting) sehingga pemanggil bertanggung jawab me-recycle
     * keduanya bila perlu.
     */
    suspend fun translateWithInpaint(
        bitmap: Bitmap,
        script: LanguageScript,
        inpaintingEngine: InpaintingEngine,
        sourceTag: String = "auto",
        targetTag: String = "id"
    ): Pair<Bitmap, PageTranslation> {
        val result = translate(bitmap, script, sourceTag, targetTag)
        if (result.bubbles.isEmpty()) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, true) to result
        }

        val mask = createMask(
            width = bitmap.width,
            height = bitmap.height,
            textBounds = result.bubbles.flatMap { bubble ->
                bubble.textLines.ifEmpty { listOf(bubble.textBounds) }
            }
        )
        onStage?.invoke(TranslationStage.INPAINTING)
        val inpainted = try {
            inpaintingEngine.inpaint(bitmap, mask)
        } finally {
            if (!mask.isRecycled) mask.recycle()
        }
        return inpainted to result
    }

    /**
     * Mask putih di area teks, hitam di area lain. Tiap kotak teks dilebarkan
     * sedikit ([MASK_DILATION]) agar sisa anti-aliasing huruf asli ikut tertutup;
     * tanpa ini tepi huruf masih terlihat sebagai bayangan tipis.
     */
    private fun createMask(width: Int, height: Int, textBounds: List<Rect>): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        java.util.Arrays.fill(pixels, Color.BLACK)

        for (rect in textBounds) {
            // Mask mengikuti kotak baris teks (bukan seluruh balon). Pelebaran
            // kecil menutup anti-aliasing huruf tanpa menghapus garis balon,
            // screentone, atau ilustrasi di sekitarnya.
            val pad = max(2, (rect.height() * MASK_DILATION).toInt()).coerceAtMost(8)
            paintRect(
                pixels,
                width,
                height,
                Rect(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad),
                Color.WHITE
            )
        }

        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    private fun paintRect(pixels: IntArray, width: Int, height: Int, rect: Rect, color: Int) {
        val left = rect.left.coerceIn(0, width)
        val top = rect.top.coerceIn(0, height)
        val right = rect.right.coerceIn(0, width)
        val bottom = rect.bottom.coerceIn(0, height)
        for (y in top until bottom) {
            val rowStart = y * width
            for (x in left until right) {
                pixels[rowStart + x] = color
            }
        }
    }

    /**
     * Gabungkan balon hasil deteksi dengan blok OCR yang tidak tercakup balon.
     */
    private fun buildRegions(
        bitmap: Bitmap,
        blocks: List<RecognizedBlock>,
        bubbles: List<DetectedBubble>
    ): List<Region> {
        val regions = bubbles.map { bubble ->
            Region(
                id = bubble.id,
                bounds = bubble.bubbleBounds,
                textBounds = bubble.textBounds,
                text = bubble.text,
                fillColor = bubble.fillColor,
                textColor = bubble.textColor,
                confidence = bubble.confidence,
                lines = bubble.lines.ifEmpty { listOf(bubble.textBounds) },
                maskRects = bubble.lines.ifEmpty { listOf(bubble.textBounds) },
                readingOrder = bubble.readingOrder
            )
        }.toMutableList()
        val bubbleTextBounds = bubbles.map { it.textBounds }

        for (block in blocks) {
            val text = block.text.trim()
            if (text.isEmpty()) continue
            val insideBubble = bubbleTextBounds.any { overlapFraction(block.bounds, it) >= 0.45f }
            if (insideBubble) continue

            val bounds = padBlock(block.bounds, bitmap.width, bitmap.height)
            val (fill, textColor) = BubbleDetector.surroundings(bitmap, block.bounds)
            val lines = block.lines.ifEmpty { listOf(block.bounds) }
            val idText = text + "@" + block.bounds.left + "," + block.bounds.top
            regions += Region(
                id = "t" + Integer.toHexString(idText.hashCode()),
                bounds = bounds,
                textBounds = block.bounds,
                text = text,
                fillColor = fill,
                textColor = textColor,
                confidence = BubbleConfidence.LOW,
                lines = lines,
                maskRects = block.elements.ifEmpty { lines }
            )
        }

        val verticalBlocks = blocks.count { it.orientation == com.mangalens.ocr.TextOrientation.VERTICAL }
        val horizontalBlocks = blocks.count { it.orientation == com.mangalens.ocr.TextOrientation.HORIZONTAL }
        val vertical = verticalBlocks > horizontalBlocks
        val sorted = if (vertical) {
            // Manga Jepang: kanan ke kiri, lalu atas ke bawah.
            regions.sortedWith(compareBy({ -it.bounds.right }, { it.bounds.top }))
        } else {
            regions.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        }
        return sorted
            .mapIndexed { index, region -> region.copy(readingOrder = index + 1) }
            .take(MAX_REGIONS)
    }

    private fun overlapFraction(block: Rect, cover: Rect): Float {
        val width = min(block.right, cover.right) - max(block.left, cover.left)
        val height = min(block.bottom, cover.bottom) - max(block.top, cover.top)
        if (width <= 0 || height <= 0) return 0f
        val area = block.width() * block.height()
        if (area <= 0) return 0f
        return (width * height).toFloat() / area
    }

    private fun padBlock(rect: Rect, width: Int, height: Int): Rect {
        val pad = (rect.height() * 0.18f).toInt().coerceIn(4, 18)
        return Rect(
            (rect.left - pad).coerceAtLeast(0),
            (rect.top - pad).coerceAtLeast(0),
            (rect.right + pad).coerceAtMost(width),
            (rect.bottom + pad).coerceAtMost(height)
        )
    }

    private fun providerFor(sourceTag: String, targetTag: String): TranslationProvider {
        val key = "$sourceTag>$targetTag"
        val existing = provider
        if (existing != null && providerKey == key) return existing

        existing?.let { (it as? MlKitTranslationProvider)?.close() }
        val created = providerFactory(sourceTag, targetTag)
        provider = created
        providerKey = key
        return created
    }

    fun close() {
        (provider as? MlKitTranslationProvider)?.close()
        provider = null
        providerKey = null
    }

    private data class Region(
        val id: String,
        val bounds: Rect,
        val textBounds: Rect,
        val text: String,
        val fillColor: Int,
        val textColor: Int,
        val confidence: BubbleConfidence,
        val lines: List<Rect>,
        val maskRects: List<Rect>,
        val readingOrder: Int = 0
    )

    private companion object {
        const val TAG = "MangaLensMT"

        const val MAX_REGIONS = 40

        /** Pelebaran mask relatif terhadap tinggi kotak teks. */
        const val MASK_DILATION = 0.06f
    }
}
