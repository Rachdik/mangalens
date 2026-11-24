package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.OcrEngine
import com.mangalens.ocr.RecognizedBlock
import com.mangalens.ocr.TextOrientation
import com.mangalens.translation.TranslateResult
import com.mangalens.translation.TranslationProvider
import com.mangalens.translation.TranslationRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uji jalur inpainting yang dipakai [MangaPageTranslator.translateWithInpaint]:
 * teks asli di area mask harus hilang, piksel di luar area teks tidak berubah.
 *
 * OCR dan provider palsu dipakai agar hasil tidak bergantung jaringan.
 */
@RunWith(AndroidJUnit4::class)
class MangaPageTranslatorInpaintTest {

    private val textRect = Rect(60, 50, 200, 80)

    private fun page(): Bitmap {
        val bitmap = Bitmap.createBitmap(260, 140, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawRect(10f, 10f, 250f, 130f, border)
        // "Teks" asli: garis hitam pekat di dalam kotak teks.
        val ink = Paint().apply { color = Color.BLACK }
        canvas.drawRect(
            textRect.left.toFloat(),
            textRect.top.toFloat(),
            textRect.right.toFloat(),
            textRect.bottom.toFloat(),
            ink
        )
        return bitmap
    }

    private val fakeOcr = object : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap, script: LanguageScript): List<RecognizedBlock> =
            listOf(
                RecognizedBlock(
                    text = "こんにちは",
                    bounds = Rect(textRect),
                    order = 0,
                    orientation = TextOrientation.HORIZONTAL,
                    lines = listOf(Rect(textRect)),
                    elements = listOf(Rect(textRect))
                )
            )
    }

    private val fakeProvider = object : TranslationProvider {
        override val id = "fake"
        override val displayName = "Fake"
        override suspend fun prepare(): Boolean = true
        override suspend fun translate(requests: List<TranslationRequest>): TranslateResult =
            TranslateResult.Success(requests.associate { it.id to "Halo" })
    }

    @Test
    fun inpaint_removesOriginalTextAndKeepsBorder() = runBlocking {
        val source = page()
        val translator = MangaPageTranslator(fakeOcr) { _, _ -> fakeProvider }
        try {
            val (result, pageTranslation) = translator.translateWithInpaint(
                bitmap = source,
                script = LanguageScript.JAPANESE,
                inpaintingEngine = BasicInpaintingEngine(),
                sourceTag = "ja",
                targetTag = "id"
            )

            assertEquals("seharusnya ada satu dialog", 1, pageTranslation.bubbles.size)
            assertTrue("hasil inpainting harus bitmap baru", result !== source)

            // Area teks harus sudah tidak hitam.
            var darkInside = 0
            for (y in textRect.top + 6 until textRect.bottom - 6) {
                for (x in textRect.left + 6 until textRect.right - 6) {
                    val c = result.getPixel(x, y)
                    val lum = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                    if (lum < 60) darkInside++
                }
            }
            assertEquals("teks asli masih tersisa di area mask", 0, darkInside)

            // Garis panel di luar teks tidak boleh ikut berubah. Nilai piksel
            // dibandingkan dengan sumber karena tepi stroke punya anti-aliasing.
            for (point in listOf(10 to 70, 11 to 70, 12 to 70, 2 to 2, 250 to 70)) {
                val before = source.getPixel(point.first, point.second)
                val after = result.getPixel(point.first, point.second)
                assertEquals(
                    "piksel di luar mask berubah di (${point.first},${point.second})",
                    before,
                    after
                )
            }
            val borderPixel = result.getPixel(11, 70)
            assertTrue(
                "garis panel seharusnya tetap gelap: $borderPixel",
                Color.red(borderPixel) < 200
            )

            if (!source.isRecycled) source.recycle()
            if (!result.isRecycled) result.recycle()
        } finally {
            translator.close()
        }
    }
}
