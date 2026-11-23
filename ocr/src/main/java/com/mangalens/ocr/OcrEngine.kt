package com.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Skrip yang bisa dikenali OCR. [AUTO] berarti pemanggil tidak memilih bahasa:
 * engine mencoba beberapa recognizer lalu memakai hasil terbaik, sehingga satu
 * bubble yang sama bisa dipakai di manga Jepang maupun komik Inggris.
 */
enum class LanguageScript { AUTO, LATIN, JAPANESE, KOREAN, CHINESE }

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
