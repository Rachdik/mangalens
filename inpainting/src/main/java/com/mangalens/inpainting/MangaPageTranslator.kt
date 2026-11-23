package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Rect
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.OcrEngine
import com.mangalens.ocr.ScriptDetector
import com.mangalens.translation.MlKitTranslationProvider
import com.mangalens.translation.TranslateResult
import com.mangalens.translation.TranslationProvider
import com.mangalens.translation.TranslationRequest

/** Hasil terjemahan satu balon, lengkap dengan posisi & warna balon aslinya. */
data class TranslatedBubble(
    val bounds: Rect,
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
 * Alur terjemahan satu halaman manga: OCR -> deteksi balon -> terjemahkan.
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
    private val providerFactory: (String) -> TranslationProvider
) {

    private var provider: TranslationProvider? = null
    private var providerTag: String? = null

    suspend fun translate(bitmap: Bitmap, script: LanguageScript): PageTranslation {
        val blocks = ocr.recognize(bitmap, script)
        if (blocks.isEmpty()) {
            return PageTranslation(emptyList(), 0, 0, ScriptDetector.languageTag(script))
        }

        val joined = blocks.joinToString(" ") { it.text }
        val tag = ScriptDetector.languageTagOf(joined)

        val bubbles = BubbleDetector.detect(bitmap, blocks)
        if (bubbles.isEmpty()) {
            return PageTranslation(emptyList(), blocks.size, 0, tag)
        }

        val translationProvider = providerFor(tag)
        if (!translationProvider.prepare()) {
            throw IllegalStateException(
                "Model terjemahan $tag → Indonesia belum tersedia. " +
                    "Sambungkan internet sekali, lalu ketuk bubble lagi."
            )
        }

        val requests = bubbles.mapIndexed { index, bubble ->
            TranslationRequest(index.toString(), bubble.text)
        }

        return when (val result = translationProvider.translate(requests)) {
            is TranslateResult.Success -> {
                val translated = bubbles.mapIndexedNotNull { index, bubble ->
                    val target = result.texts[index.toString()]?.trim().orEmpty()
                    if (target.isEmpty() || target.equals(bubble.text, ignoreCase = true)) {
                        null
                    } else {
                        TranslatedBubble(
                            bounds = bubble.bubbleBounds,
                            sourceText = bubble.text,
                            targetText = target,
                            fillColor = bubble.fillColor,
                            textColor = bubble.textColor
                        )
                    }
                }
                PageTranslation(translated, blocks.size, bubbles.size, tag)
            }

            is TranslateResult.Failure -> throw IllegalStateException(result.message)
        }
    }

    private fun providerFor(tag: String): TranslationProvider {
        val existing = provider
        if (existing != null && providerTag == tag) return existing

        existing?.let { (it as? MlKitTranslationProvider)?.close() }
        val created = providerFactory(tag)
        provider = created
        providerTag = tag
        return created
    }

    fun close() {
        (provider as? MlKitTranslationProvider)?.close()
        provider = null
        providerTag = null
    }
}
