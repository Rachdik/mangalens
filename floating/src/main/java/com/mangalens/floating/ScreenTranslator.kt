package com.mangalens.floating

import android.graphics.Bitmap
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.PageTranslation
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.OcrEngine
import com.mangalens.translation.TranslationProvider

/**
 * Pembungkus tipis [MangaPageTranslator] untuk layar: OCR -> deteksi balon ->
 * terjemahan, dengan provider yang dipakai ulang antar ketukan.
 */
class ScreenTranslator(
    ocr: OcrEngine,
    providerFactory: (String, String) -> TranslationProvider
) {

    private val page = MangaPageTranslator(ocr, providerFactory)

    suspend fun translate(
        bitmap: Bitmap,
        script: LanguageScript,
        sourceTag: String = "auto",
        targetTag: String = "id"
    ): PageTranslation = page.translate(bitmap, script, sourceTag, targetTag)

    fun close() = page.close()
}
