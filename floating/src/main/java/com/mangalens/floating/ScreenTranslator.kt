package com.mangalens.floating

import android.graphics.Bitmap
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.OcrEngine
import com.mangalens.ocr.RecognizedBlock
import com.mangalens.translation.MlKitTranslationProvider
import com.mangalens.translation.TranslateResult
import com.mangalens.translation.TranslationRequest

data class TranslatedLine(
    val source: String,
    val target: String
)

/**
 * Alur satu kali terjemahan layar: OCR -> urutkan urutan baca -> terjemahkan.
 */
class ScreenTranslator(private val ocr: OcrEngine) {

    suspend fun translate(
        bitmap: Bitmap,
        script: LanguageScript,
        languageTag: String
    ): List<TranslatedLine> {
        val blocks = sortInReadingOrder(ocr.recognize(bitmap, script), bitmap.height)
        if (blocks.isEmpty()) return emptyList()

        val provider = MlKitTranslationProvider(languageTag)
        return try {
            if (!provider.prepare()) {
                throw IllegalStateException(
                    "Model terjemahan belum siap. Sambungkan internet sekali untuk mengunduh model."
                )
            }
            val requests = blocks.mapIndexed { index, block ->
                TranslationRequest(index.toString(), block.text)
            }
            when (val result = provider.translate(requests)) {
                is TranslateResult.Success -> blocks.mapIndexed { index, block ->
                    TranslatedLine(block.text, result.texts[index.toString()].orEmpty())
                }
                is TranslateResult.Failure -> throw IllegalStateException(result.message)
            }
        } finally {
            provider.close()
        }
    }

    /**
     * Manga/manhua dibaca kanan→kiri per baris. Blok dikelompokkan per
     * ketinggian lalu diurutkan dari kanan ke kiri, atas ke bawah.
     */
    private fun sortInReadingOrder(
        blocks: List<RecognizedBlock>,
        bitmapHeight: Int
    ): List<RecognizedBlock> {
        if (blocks.isEmpty()) return blocks
        val rowHeight = (bitmapHeight / 32).coerceAtLeast(24)
        return blocks.sortedWith(
            compareBy({ it.bounds.top / rowHeight }, { -it.bounds.left })
        )
    }
}
