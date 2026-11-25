package com.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Implementasi ML Kit untuk [OcrEngine]. Model dibundel di APK sehingga
 * tidak perlu unduhan saat pertama dipakai.
 *
 * Catatan performa:
 * - Orientasi teks dihitung SEKALI per halaman. Versi sebelumnya memanggil
 *   recognizer tambahan untuk setiap blok teks, sehingga satu halaman dengan
 *   30 blok menjalankan 31 kali OCR.
 * - Gambar besar diperkecil lebih dulu (batas [MAX_OCR_DIMENSION]) lalu
 *   koordinat blok dikembalikan ke ukuran asli, agar memori dan waktu
 *   pemrosesan tetap wajar untuk halaman webtoon yang sangat tinggi.
 */
class MlKitOcrEngine : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, script: LanguageScript): List<RecognizedBlock> {
        if (script == LanguageScript.AUTO) return recognizeAuto(bitmap)
        return recognizeWith(bitmap, script)
    }

    /**
     * Deteksi orientasi mandiri tidak dipakai pipeline (orientasi sudah
     * dihitung di dalam [recognize]). Dibiarkan sebagai API dan tidak
     * memblokir thread pemanggil.
     */
    override fun detectOrientation(bitmap: Bitmap): TextOrientation = TextOrientation.UNKNOWN

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
        val scaled = scaleForOcr(bitmap)
        val recognizer = createRecognizer(script)
        return try {
            val result = recognizer
                .process(InputImage.fromBitmap(scaled.bitmap, 0))
                .awaitResult()

            // Hitung orientasi sekali saja untuk seluruh halaman.
            val orientation = orientationOf(script, result)

            result.textBlocks.mapIndexedNotNull { index, block ->
                // Teks vertikal Jepang dibaca dari kolom PALING KANAN ke kiri.
                // ML Kit mengembalikan baris dari kiri ke kanan, sehingga tanpa
                // pembalikan urutan ini dialog satu balon terbaca terbalik
                // (mis. "この / なにかしら / かわいい人形は" alih-alih
                // "この / かわいい人形は / なにかしら").
                val orderedLines = if (orientation == TextOrientation.VERTICAL) {
                    block.lines.sortedByDescending { it.boundingBox?.left ?: 0 }
                } else {
                    block.lines
                }
                val text = OcrTextCleaner.clean(
                    orderedLines.joinToString("\n") { it.text }
                )
                val box = block.boundingBox
                if (text.isEmpty() || box == null) {
                    null
                } else {
                    RecognizedBlock(
                        text = text,
                        bounds = Rect(box).toOriginal(scaled.scale, bitmap.width, bitmap.height),
                        order = index,
                        orientation = orientation,
                        lines = orderedLines.mapNotNull { line ->
                            line.boundingBox?.let {
                                Rect(it).toOriginal(scaled.scale, bitmap.width, bitmap.height)
                            }
                        },
                        elements = block.lines.asSequence()
                            .flatMap { line -> line.elements.asSequence() }
                            .mapNotNull { element ->
                                element.boundingBox?.let {
                                    Rect(it).toOriginal(scaled.scale, bitmap.width, bitmap.height)
                                }
                            }
                            .take(MAX_ELEMENTS_PER_BLOCK)
                            .toList()
                    )
                }
            }
        } finally {
            runCatching { recognizer.close() }
            if (scaled.needsRecycle) runCatching { scaled.bitmap.recycle() }
        }
    }

    /** Rasio lebar/tinggi blok menentukan tategaki (vertikal) atau horizontal. */
    private fun orientationOf(script: LanguageScript, result: Text): TextOrientation {
        if (script != LanguageScript.JAPANESE && script != LanguageScript.CHINESE) {
            return TextOrientation.HORIZONTAL
        }
        var totalRatio = 0f
        var count = 0
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            if (box.width() < 5 || box.height() < 5) continue
            totalRatio += box.width().toFloat() / box.height()
            count++
        }
        if (count == 0) return TextOrientation.UNKNOWN
        return if (totalRatio / count < 0.5f) TextOrientation.VERTICAL else TextOrientation.HORIZONTAL
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

    private class ScaledInput(val bitmap: Bitmap, val scale: Float, val needsRecycle: Boolean)

    private fun scaleForOcr(bitmap: Bitmap): ScaledInput {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_OCR_DIMENSION) return ScaledInput(bitmap, 1f, false)

        val scale = MAX_OCR_DIMENSION.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }.getOrNull() ?: return ScaledInput(bitmap, 1f, false)

        return if (scaled === bitmap) {
            ScaledInput(bitmap, 1f, false)
        } else {
            ScaledInput(scaled, scaled.width.toFloat() / bitmap.width, true)
        }
    }

    private fun Rect.toOriginal(scale: Float, width: Int, height: Int): Rect {
        if (scale == 1f) return this
        return Rect(
            (left / scale).toInt().coerceIn(0, width),
            (top / scale).toInt().coerceIn(0, height),
            (right / scale).toInt().coerceIn(0, width),
            (bottom / scale).toInt().coerceIn(0, height)
        )
    }

    private companion object {
        /** Di atas angka ini halaman dianggap berisi teks Latin; tidak perlu coba skrip lain. */
        const val AUTO_ENOUGH_CHARS = 40

        /** Sisi terpanjang maksimum yang dikirim ke ML Kit. */
        const val MAX_OCR_DIMENSION = 2400

        /** Batas kotak elemen per blok agar memori tetap kecil. */
        const val MAX_ELEMENTS_PER_BLOCK = 80

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
