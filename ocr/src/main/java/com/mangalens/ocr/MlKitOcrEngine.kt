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
        if (script == LanguageScript.AUTO) return recognizeAuto(bitmap)
        return recognizeWith(bitmap, script)
    }

    /**
     * Mode otomatis: recognizer Latin hampir selalu lebih akurat untuk huruf
     * Latin, sedangkan manga Jepang butuh recognizer Jepang. Latin dicoba dulu
     * karena paling sering dipakai; bila hasilnya terlalu sedikit (mis. halaman
     * Jepang), recognizer Jepang dicoba dan hasil dengan teks terbanyak dipakai.
     */
    private suspend fun recognizeAuto(bitmap: Bitmap): List<RecognizedBlock> {
        val latin = runCatching { recognizeWith(bitmap, LanguageScript.LATIN) }
            .getOrDefault(emptyList())
        var best = latin
        var bestChars = scriptChars(latin)
        // Halaman berhuruf Latin (komik/manhwa terjemahan Inggris) biasanya
        // sudah cukup terbaca; tiga pass tambahan hanya membuang waktu.
        if (bestChars >= AUTO_ENOUGH_CHARS) return latin

        for (script in OTHER_SCRIPTS) {
            val blocks = runCatching { recognizeWith(bitmap, script) }
                .getOrDefault(emptyList())
            val chars = scriptChars(blocks)
            if (chars > bestChars) {
                best = blocks
                bestChars = chars
            }
        }
        return best
    }

    private fun scriptChars(blocks: List<RecognizedBlock>): Int =
        ScriptDetector.characterCount(blocks.joinToString(" ") { it.text })

    private suspend fun recognizeWith(
        bitmap: Bitmap,
        script: LanguageScript
    ): List<RecognizedBlock> {
        val recognizer = createRecognizer(script)
        return try {
            val result = recognizer
                .process(InputImage.fromBitmap(bitmap, 0))
                .awaitResult()

            result.textBlocks.mapIndexedNotNull { index, block ->
                val text = block.lines.joinToString("\n") { it.text }.trim()
                val box = block.boundingBox
                if (text.isEmpty() || box == null) {
                    null
                } else {
                    RecognizedBlock(text = text, bounds = Rect(box), order = index)
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

        LanguageScript.LATIN, LanguageScript.AUTO ->
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private companion object {
        /** Di atas angka ini halaman dianggap berisi teks Latin; tidak perlu coba skrip lain. */
        const val AUTO_ENOUGH_CHARS = 40

        /** Dicoba berurutan saat halaman hampir tidak berisi huruf Latin. */
        val OTHER_SCRIPTS = listOf(
            LanguageScript.JAPANESE,
            LanguageScript.KOREAN,
            LanguageScript.CHINESE
        )
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
