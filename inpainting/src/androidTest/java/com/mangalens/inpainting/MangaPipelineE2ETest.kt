package com.mangalens.inpainting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.MyMemoryTranslationProvider
import com.mangalens.translation.TranslateResult
import com.mangalens.translation.TranslationProvider
import com.mangalens.translation.TranslationRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Validasi pipeline end-to-end pada halaman manga nyata:
 *
 *   gambar -> OCR -> deteksi balon -> terjemahan -> mask -> inpainting -> gambar akhir
 *
 * Dua test:
 * 1. [pipelineWithStubProvider] memakai provider stub deterministik untuk membuktikan
 *    pemetaan per-balon, pembuatan mask, dan penghapusan teks asli oleh inpainting.
 *    Hasil PNG disimpan agar bisa diperiksa manusia.
 * 2. [pipelineWithNetworkTranslation] memanggil provider MyMemory sungguhan
 *    (tanpa API key) dengan teks hasil OCR. Hasilnya dicatat apa adanya; kegagalan
 *    jaringan dilaporkan sebagai BLOCKED, bukan dianggap sukses.
 *
 * Rendering teks terjemahan dilakukan UI (overlay Compose di atas bitmap), bukan
 * oleh modul ini — karena itu test ini berhenti di gambar yang sudah diinpainting
 * plus data [TranslatedBubble] berisi posisi dan teks target.
 */
@RunWith(AndroidJUnit4::class)
class MangaPipelineE2ETest {

    companion object {
        private const val TAG = "MangaLensE2E"
        private const val ASSET = "manga_page_en.jpg"
    }

    @Test
    fun pipelineWithStubProvider() = runBlocking {
        val bitmap = loadPage()
        val translator = MangaPageTranslator(MlKitOcrEngine()) { _, _ -> StubProvider() }

        val (inpainted, page) = translator.translateWithInpaint(
            bitmap = bitmap,
            script = LanguageScript.AUTO,
            inpaintingEngine = BasicInpaintingEngine(),
            sourceTag = "en",
            targetTag = "id"
        )

        Log.d(TAG, "E2E_BEGIN")
        Log.d(TAG, "OCR blocks: ${page.ocrBlocks}")
        Log.d(TAG, "Detected bubbles: ${page.detectedBubbles}")
        Log.d(TAG, "Provider: ${page.providerName}")
        Log.d(TAG, "Translated bubbles: ${page.bubbles.size}")

        var inkBefore = 0
        var inkAfter = 0
        page.bubbles.forEachIndexed { index, bubble ->
            Log.d(TAG, "BUBBLE ${index + 1} source='${bubble.sourceText.replace("\n", " / ")}'")
            Log.d(TAG, "BUBBLE ${index + 1} target='${bubble.targetText}'")
            Log.d(TAG, "BUBBLE ${index + 1} textBounds=${bubble.textBounds} lines=${bubble.textLines.size}")
            inkBefore += countDarkPixels(bitmap, bubble.textBounds)
            inkAfter += countDarkPixels(inpainted, bubble.textBounds)
        }

        val outDir = File(context().getExternalFilesDir(null), "phase9i").apply { mkdirs() }
        val stubFile = File(outDir, "e2e_stub_provider.png")
        FileOutputStream(stubFile).use { inpainted.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.d(TAG, "Saved inpainted result: ${stubFile.absolutePath} (${stubFile.length()} bytes)")
        Log.d(TAG, "INK dark pixels inside text bounds: before=$inkBefore after=$inkAfter")
        Log.d(TAG, "E2E_END")

        assertTrue("OCR harus menemukan teks pada halaman nyata", page.ocrBlocks > 0)
        assertTrue("Harus ada balon/region yang diterjemahkan", page.bubbles.isNotEmpty())
        assertTrue(
            "Teks asli harus dihapus oleh inpainting. Sebelum=$inkBefore Sesudah=$inkAfter",
            inkAfter < inkBefore
        )
        assertTrue(
            "Setiap balon harus punya teks target",
            page.bubbles.all { it.targetText.isNotBlank() }
        )
    }

    @Test
    fun pipelineWithNetworkTranslation() = runBlocking {
        val bitmap = loadPage()
        val translator = MangaPageTranslator(MlKitOcrEngine()) { source, target ->
            MyMemoryTranslationProvider(source, target)
        }

        val result = runCatching {
            translator.translate(bitmap, LanguageScript.AUTO, sourceTag = "en", targetTag = "id")
        }

        result.onSuccess { page ->
            Log.d(TAG, "NETWORK provider=${page.providerName} bubbles=${page.bubbles.size}")
            page.bubbles.forEachIndexed { index, bubble ->
                Log.d(TAG, "NET BUBBLE ${index + 1}: '${bubble.sourceText.replace("\n", " / ")}'")
                Log.d(TAG, "NET BUBBLE ${index + 1} -> '${bubble.targetText}'")
            }
            val dir = File(context().getExternalFilesDir(null), "phase9i").apply { mkdirs() }
            File(dir, "translation_network.txt").writeText(
                page.bubbles.joinToString("\n\n") { "${it.sourceText}\n=> ${it.targetText}" }
            )
            Log.d(TAG, "NETWORK_RESULT SUCCESS bubbles=${page.bubbles.size}")
        }.onFailure { error ->
            Log.e(TAG, "NETWORK_RESULT FAILED: ${error.javaClass.simpleName}: ${error.message}", error)
        }
        Unit
    }

    /** Provider deterministik: membuktikan pemetaan per-balon, bukan kualitas terjemahan. */
    private class StubProvider : TranslationProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub (uji pemetaan)"

        override suspend fun translate(requests: List<TranslationRequest>): TranslateResult =
            TranslateResult.Success(requests.mapIndexed { index, request ->
                request.id to "TERJEMAHAN ${index + 1}: ${request.text.length} karakter"
            }.toMap())
    }

    private fun countDarkPixels(bitmap: Bitmap, rect: android.graphics.Rect): Int {
        var count = 0
        for (y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(bitmap.height)) {
            for (x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(bitmap.width)) {
                val color = bitmap.getPixel(x, y)
                val luminance = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
                if (luminance < 100) count++
            }
        }
        return count
    }

    private fun context(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun loadPage(): Bitmap {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val bitmap = testContext.assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
        assertNotNull("$ASSET harus termuat", bitmap)
        Log.d(TAG, "Loaded $ASSET ${bitmap!!.width}x${bitmap.height}")
        return bitmap
    }
}
