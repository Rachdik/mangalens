package com.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Rect

enum class LanguageScript { LATIN, JAPANESE, KOREAN, CHINESE }

data class RecognizedBlock(
    val text: String,
    val bounds: Rect,
    val order: Int
)

/**
 * Implementasi ML Kit masuk pada Tahap 4. Interface ini menjaga agar
 * pemanggil tidak bergantung langsung pada ML Kit.
 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap, script: LanguageScript): List<RecognizedBlock>
}
