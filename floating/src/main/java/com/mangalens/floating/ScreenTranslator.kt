package com.mangalens.floating

import android.graphics.Bitmap
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.TranslatedBubble
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.OcrEngine
import com.mangalens.translation.TranslationProvider

/**
 * Pembungkus tipis [MangaPageTranslator] untuk layar: OCR -> deteksi balon ->
 * terjemahan, dengan provider yang dipakai ulang antar ketukan.
 */
class ScreenTranslator(ocr: OcrEngine, provider: TranslationProvider) {

    private val page = MangaPageTranslator(ocr, provider)

    suspend fun translate(bitmap: Bitmap, script: LanguageScript): List<TranslatedBubble> =
        page.translate(bitmap, script)

    fun close() = page.close()
}
