package com.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifikasi OCR pada gambar nyata, dijalankan di perangkat.
 *
 * Gambar dimuat dari `androidTest` assets, bukan /sdcard: APK test berjalan dengan
 * scoped storage (Android 10+) sehingga `BitmapFactory.decodeFile("/sdcard/...")`
 * mengembalikan null tanpa izin READ_MEDIA_IMAGES. Itu penyebab kegagalan
 * verifikasi sebelumnya, bukan masalah pada gambarnya.
 *
 * Fixture:
 * - manga_page_en.jpg : halaman manga asli hasil tangkapan reader (1600x2278).
 * - japanese_book.jpg : sampul buku Jepang domain publik (teks Jepang cetak).
 */
@RunWith(AndroidJUnit4::class)
class RealImageOcrVerificationTest {

    companion object {
        private const val TAG = "MangaLensOcrVerify"
        private const val MANGA_PAGE = "manga_page_en.jpg"
        private const val JAPANESE_BOOK = "japanese_book.jpg"
    }

    private lateinit var ocrEngine: MlKitOcrEngine

    @Before
    fun setUp() {
        ocrEngine = MlKitOcrEngine()
    }

    @Test
    fun ocrOnRealMangaPage() = runBlocking {
        val bitmap = loadAsset(MANGA_PAGE)
        assertNotNull("$MANGA_PAGE harus termuat", bitmap)
        val blocks = runOcrAndLog(MANGA_PAGE, bitmap!!, LanguageScript.AUTO)
        assertTrue("OCR tidak menemukan teks pada halaman manga asli", blocks.isNotEmpty())
    }

    @Test
    fun ocrOnJapaneseText() = runBlocking {
        val bitmap = loadAsset(JAPANESE_BOOK)
        assertNotNull("$JAPANESE_BOOK harus termuat", bitmap)
        val blocks = runOcrAndLog(JAPANESE_BOOK, bitmap!!, LanguageScript.JAPANESE)

        assertTrue("Recognizer Jepang tidak menemukan teks", blocks.isNotEmpty())
        val japanese = blocks.filter { block -> block.text.any { containsJapanese(it) } }
        Log.d(TAG, "Blok berisi karakter Jepang: ${japanese.size} dari ${blocks.size}")
        assertTrue(
            "Tidak ada satu blok pun yang berisi karakter Jepang (kanji/kana)",
            japanese.isNotEmpty()
        )
    }

    /** Kanji, hiragana, katakana, atau tanda baca Jepang. */
    private fun containsJapanese(ch: Char): Boolean =
        ch in '\u3040'..'\u30FF' || ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' ||
            ch in '\uFF66'..'\uFF9D'

    /** Muat gambar dari assets APK test. */
    private fun loadAsset(name: String): Bitmap? {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        return runCatching {
            testContext.assets.open(name).use { BitmapFactory.decodeStream(it) }
        }.onFailure { Log.e(TAG, "Gagal memuat asset $name", it) }.getOrNull()
    }

    private suspend fun runOcrAndLog(
        name: String,
        bitmap: Bitmap,
        script: LanguageScript
    ): List<RecognizedBlock> {
        Log.d(TAG, "MANGALENS_OCR_BEGIN")
        Log.d(TAG, "Image: $name")
        Log.d(TAG, "Image dimensions: ${bitmap.width}x${bitmap.height}")
        Log.d(TAG, "OCR engine: MlKitOcrEngine (ML Kit, script=$script)")

        val start = System.currentTimeMillis()
        val blocks = ocrEngine.recognize(bitmap, script)
        val elapsed = System.currentTimeMillis() - start

        Log.d(TAG, "Processing duration: ${elapsed}ms")
        Log.d(TAG, "Detected blocks: ${blocks.size}")

        var totalChars = 0
        blocks.forEachIndexed { index, block ->
            totalChars += block.text.length
            Log.d(TAG, "BLOCK ${index + 1}")
            Log.d(TAG, "Recognized text: ${block.text.replace("\n", " / ")}")
            Log.d(
                TAG,
                "Bounding box: [${block.bounds.left},${block.bounds.top}]" +
                    "-[${block.bounds.right},${block.bounds.bottom}] " +
                    "(${block.bounds.width()}x${block.bounds.height()}) orientation=${block.orientation}"
            )
        }
        Log.d(TAG, "Total characters: $totalChars")
        Log.d(TAG, "MANGALENS_OCR_END")

        println("MANGALENS_OCR_BEGIN")
        println("Image: $name (${bitmap.width}x${bitmap.height}) script=$script")
        println("Duration: ${elapsed}ms")
        println("Blocks: ${blocks.size} TotalChars: $totalChars")
        blocks.forEachIndexed { i, b -> println("BLOCK ${i + 1}: ${b.text.replace("\n", " / ")} | ${b.bounds}") }
        println("MANGALENS_OCR_END")
        return blocks
    }
}
