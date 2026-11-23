package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.OcrEngine
import com.mangalens.ocr.RecognizedBlock
import com.mangalens.ocr.ScriptDetector
import com.mangalens.translation.MlKitTranslationProvider
import com.mangalens.translation.TranslateResult
import com.mangalens.translation.TranslationProvider
import com.mangalens.translation.TranslationRequest
import com.mangalens.inpainting.InpaintingEngine
import kotlin.math.max
import kotlin.math.min

/** Hasil terjemahan satu balon, lengkap dengan posisi & warna balon aslinya. */
data class TranslatedBubble(
    /** Area balon penuh; dipakai sebagai batas luar tempelan. */
    val bounds: Rect,
    /** Area teks asli; tempelan dibuat seukuran ini agar tidak menutupi gambar. */
    val textBounds: Rect,
    val sourceText: String,
    val targetText: String,
    val fillColor: Int,
    val textColor: Int
)

/**
 * Hasil satu halaman/layar. Angka diagnostik ikut dikembalikan supaya UI bisa
 * memberi tahu pengguna apa yang sebenarnya terjadi (0 blok OCR? 0 balon?).
 */
data class PageTranslation(
    val bubbles: List<TranslatedBubble>,
    val ocrBlocks: Int,
    val detectedBubbles: Int,
    val sourceTag: String
)

/**
 * Alur terjemahan satu layar: OCR -> deteksi balon -> terjemahkan.
 *
 * Semua teks yang terbaca diterjemahkan, bukan hanya yang berada di dalam
 * balon: blok yang tidak terdeteksi sebagai balon (kotak narasi, tulisan di
 * luar kotak, teks pada gambar) tetap diterjemahkan dan ditempel pada posisi
 * bloknya sendiri. Dengan begitu satu ketukan menerjemahkan apa pun yang ada
 * di layar.
 *
 * Bahasa sumber ditentukan dari teks yang benar-benar terbaca, bukan dari
 * pilihan pengguna, karena pilihan yang salah (mis. "Jepang" pada komik
 * Inggris) menghasilkan terjemahan kosong atau tidak berubah.
 *
 * Provider dibuat lewat [providerFactory] dan di-cache per bahasa sehingga
 * model ML Kit tidak dimuat ulang setiap ketukan.
 */
class MangaPageTranslator(
    private val ocr: OcrEngine,
    /** (kode bahasa sumber, kode bahasa tujuan) -> provider. */
    private val providerFactory: (String, String) -> TranslationProvider
) {

    private var provider: TranslationProvider? = null
    private var providerKey: String? = null

    /**
     * @param sourceTag kode bahasa sumber, atau "auto" untuk dideteksi dari teks.
     * @param targetTag kode bahasa hasil terjemahan.
     */
    suspend fun translate(
        bitmap: Bitmap,
        script: LanguageScript,
        sourceTag: String = "auto",
        targetTag: String = "id"
    ): PageTranslation {
        val blocks = ocr.recognize(bitmap, script)
        if (blocks.isEmpty()) {
            return PageTranslation(emptyList(), 0, 0, ScriptDetector.languageTag(script))
        }

        // Blok tanpa huruf/angka yang cukup (garis, titik, nomor halaman)
        // bukan dialog dan hanya menambah derau.
        val usable = blocks.filter {
            it.text.count { ch -> ch.isLetterOrDigit() } >= 2 &&
                it.bounds.width() > 2 &&
                it.bounds.height() > 2
        }
        if (usable.isEmpty()) {
            return PageTranslation(emptyList(), 0, 0, ScriptDetector.languageTag(script))
        }

        val joined = usable.joinToString(" ") { it.text }
        val detected = ScriptDetector.languageTagOf(joined)
        val tag = if (sourceTag.isBlank() || sourceTag == "auto") detected else sourceTag

        val bubbles = BubbleDetector.detect(bitmap, usable)
        val regions = buildRegions(bitmap, usable, bubbles)
        if (regions.isEmpty() || tag == targetTag) {
            // Bahasa sumber sama dengan tujuan: tidak ada yang perlu diterjemahkan.
            return PageTranslation(emptyList(), usable.size, bubbles.size, tag)
        }

        val translationProvider = providerFor(tag, targetTag)
        if (!translationProvider.prepare()) {
            throw IllegalStateException(
                "Provider ${translationProvider.displayName} belum siap. " +
                    "Untuk LLM, isi LLM_API_KEY di file .env lalu build ulang; " +
                    "untuk ML Kit, sambungkan internet sekali lalu ketuk bubble lagi."
            )
        }

        val requests = regions.mapIndexed { index, region ->
            TranslationRequest(index.toString(), region.text)
        }

        return when (val result = translationProvider.translate(requests)) {
            is TranslateResult.Success -> {
                val translated = regions.mapIndexedNotNull { index, region ->
                    val target = result.texts[index.toString()]?.trim().orEmpty()
                    if (target.isEmpty() || target.equals(region.text, ignoreCase = true)) {
                        null
                    } else {
                        TranslatedBubble(
                            bounds = region.bounds,
                            textBounds = region.textBounds,
                            sourceText = region.text,
                            targetText = target,
                            fillColor = region.fillColor,
                            textColor = region.textColor
                        )
                    }
                }
                PageTranslation(translated, usable.size, bubbles.size, tag)
            }

            is TranslateResult.Failure -> throw IllegalStateException(result.message)
        }
    }

    /**
     * Gabungkan balon hasil deteksi dengan blok OCR yang tidak tercakup balon.
     * Blok bebas memakai warna latar di sekitarnya agar tempelan menyatu dengan
     * gambar aslinya.
     */
    private fun buildRegions(
        bitmap: Bitmap,
        blocks: List<RecognizedBlock>,
        bubbles: List<DetectedBubble>
    ): List<Region> {
        val regions = bubbles
            .map {
                Region(
                    bounds = it.bubbleBounds,
                    textBounds = it.textBounds,
                    text = it.text,
                    fillColor = it.fillColor,
                    textColor = it.textColor
                )
            }
            .toMutableList()
        val bubbleTextBounds = bubbles.map { it.textBounds }

        for (block in blocks) {
            val text = block.text.trim()
            if (text.isEmpty()) continue
            val insideBubble = bubbleTextBounds.any { overlapFraction(block.bounds, it) >= 0.5f }
            if (insideBubble) continue

            val bounds = padBlock(block.bounds, bitmap.width, bitmap.height)
            val (fill, textColor) = BubbleDetector.surroundings(bitmap, block.bounds)
            regions += Region(bounds, block.bounds, text, fill, textColor)
        }

        return regions
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
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

    suspend fun translateWithInpaint(
        bitmap: Bitmap,
        script: LanguageScript,
        inpaintingEngine: InpaintingEngine,
        sourceTag: String = "auto",
        targetTag: String = "id"
    ): Pair<Bitmap, PageTranslation> {
        val result = translate(bitmap, script, sourceTag, targetTag)
        if (result.bubbles.isEmpty()) {
            return Pair(bitmap.copy(Bitmap.Config.ARGB_8888, true), result)
        }
        val mask = createMask(bitmap.width, bitmap.height, result.bubbles.map { it.textBounds })
        val inpainted = inpaintingEngine.inpaint(bitmap, mask)
        return Pair(inpainted, result)
    }

    private fun createMask(width: Int, height: Int, textBounds: List<Rect>): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) pixels[i] = Color.BLACK
        for (rect in textBounds) paintRect(pixels, width, height, rect, Color.WHITE)
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    private fun paintRect(pixels: IntArray, width: Int, height: Int, rect: Rect, color: Int) {
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                if (x >= 0 && x < width && y >= 0 && y < height) pixels[y * width + x] = color
                x++
            }
            y++
        }
    }


    fun close() {
        (provider as? MlKitTranslationProvider)?.close()
        provider = null
        providerKey = null
    }

    private data class Region(
        val bounds: Rect,
        val textBounds: Rect,
        val text: String,
        val fillColor: Int,
        val textColor: Int
    )

    private companion object {
        const val MAX_REGIONS = 40
    }
}
