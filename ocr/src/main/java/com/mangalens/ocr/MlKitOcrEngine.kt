package com.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implementasi ML Kit untuk [OcrEngine]. Model dibundel di APK sehingga
 * tidak perlu unduhan saat pertama dipakai.
 */
class MlKitOcrEngine : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, script: LanguageScript): List<RecognizedBlock> {
        val recognizer = createRecognizer(script)
        return try {
            val result = recognizer
                .process(InputImage.fromBitmap(bitmap, 0))
                .awaitResult()

            result.textBlocks.mapIndexedNotNull { index, block ->
                val text = block.lines.joinToString("\n") { it.text }.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    RecognizedBlock(
                        text = text,
                        bounds = block.boundingBox ?: Rect(),
                        order = index
                    )
                }
            }
        } finally {
            recognizer.close()
        }
    }

    private fun createRecognizer(script: LanguageScript): TextRecognizer = when (script) {
        LanguageScript.JAPANESE ->
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

        LanguageScript.KOREAN ->
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

        LanguageScript.CHINESE ->
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        LanguageScript.LATIN ->
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
