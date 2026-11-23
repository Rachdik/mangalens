package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Rect
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.OcrEngine
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
 * Alur terjemahan satu halaman manga: OCR -> deteksi balon -> terjemahkan.
 * Hasilnya membawa posisi balon sehingga teks terjemahan bisa ditempel
 * langsung menimpa teks asli, bukan ditampilkan sebagai daftar terpisah.
 *
 * Provider dibuat sekali oleh pemanggil lalu dipakai berulang (model ML Kit
 * tidak perlu dimuat ulang setiap ketukan).
 */
class MangaPageTranslator(
    private val ocr: OcrEngine,
    private val provider: TranslationProvider
) {

    @Volatile
    private var prepared = false

    suspend fun prepare(): Boolean {
        if (prepared) return true
        prepared = provider.prepare()
        return prepared
    }

    suspend fun translate(bitmap: Bitmap, script: LanguageScript): List<TranslatedBubble> {
        val blocks = ocr.recognize(bitmap, script)
        if (blocks.isEmpty()) return emptyList()

        val bubbles = BubbleDetector.detect(bitmap, blocks)
        if (bubbles.isEmpty()) return emptyList()

        if (!prepare()) {
            throw IllegalStateException(
                "Model terjemahan belum siap. Sambungkan internet sekali untuk mengunduh model."
            )
        }

        val requests = bubbles.mapIndexed { index, bubble ->
            TranslationRequest(index.toString(), bubble.text)
        }

        return when (val result = provider.translate(requests)) {
            is TranslateResult.Success -> bubbles.mapIndexedNotNull { index, bubble ->
                val target = result.texts[index.toString()]?.trim().orEmpty()
                if (target.isEmpty()) {
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

            is TranslateResult.Failure -> throw IllegalStateException(result.message)
        }
    }

    fun close() {
        (provider as? MlKitTranslationProvider)?.close()
    }
}
