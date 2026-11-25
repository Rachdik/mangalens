package com.mangalens.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.inpainting.BasicInpaintingEngine
import com.mangalens.inpainting.BubbleDetector
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.TranslationProviders
import com.mangalens.translation.TranslationSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pengukuran pipeline Reader pada ukuran halaman nyata (Phase 9K §9).
 *
 * Semua tahap memakai komponen produksi: ML Kit OCR, BubbleDetector,
 * MangaPageTranslator (provider ML Kit on-device), dan BasicInpaintingEngine.
 * Waktu yang dicetak adalah waktu nyata di RMX3561.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPipelinePerformanceTest {

    private val sizes = listOf(800 to 1138, 1079 to 1536, 1600 to 2277)

    @Test
    fun measurePipelineStages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val original = assets.open("page.png").use { BitmapFactory.decodeStream(it) }
        val ocr = MlKitOcrEngine()
        val settings = TranslationSettings(context)
        val translator = MangaPageTranslator(ocr) { source, target ->
            TranslationProviders.create(
                settings.backend,
                source,
                target,
                settings.llmConfig(),
                autoFallback = settings.autoFallback
            )
        }
        val engine = BasicInpaintingEngine()
        val cacheDir = File(context.cacheDir, "perf").apply { mkdirs() }

        for ((width, height) in sizes) {
            val scaled = Bitmap.createScaledBitmap(original, width, height, true)
            val file = File(cacheDir, "page_${width}x$height.png")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            scaled.recycle()

            val heapBefore = Debug.getNativeHeapAllocatedSize()

            // 1. Muat gambar (decode file seperti pipeline Reader).
            var t = System.currentTimeMillis()
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val decodeMs = System.currentTimeMillis() - t

            // 2. OCR AUTO vs JAPANESE (Priority E).
            t = System.currentTimeMillis()
            val autoBlocks = ocr.recognize(bitmap, LanguageScript.AUTO)
            val autoMs = System.currentTimeMillis() - t
            t = System.currentTimeMillis()
            val blocks = ocr.recognize(bitmap, LanguageScript.JAPANESE)
            val jaMs = System.currentTimeMillis() - t

            // 3. Pengelompokan balon.
            t = System.currentTimeMillis()
            val bubbles = BubbleDetector.detect(bitmap, blocks.filter {
                it.text.count { ch -> ch.isLetterOrDigit() } >= 2
            })
            val detectMs = System.currentTimeMillis() - t

            // 4-6. Terjemahan + mask + inpainting lewat pipeline produksi.
            t = System.currentTimeMillis()
            val page = translator.translate(bitmap, LanguageScript.JAPANESE, "ja", "id")
            val translateTotalMs = System.currentTimeMillis() - t

            // Mask + inpainting diukur langsung (bukan selisih dua panggilan
            // translate, yang bisa negatif karena model sudah panas).
            t = System.currentTimeMillis()
            val mask = android.graphics.Bitmap.createBitmap(
                bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            mask.eraseColor(android.graphics.Color.BLACK)
            val canvas = android.graphics.Canvas(mask)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }
            page.bubbles.forEach { bubble ->
                bubble.textLines.ifEmpty { listOf(bubble.textBounds) }.forEach { line ->
                    val pad = maxOf(2, (line.height() * 0.06f).toInt()).coerceAtMost(8)
                    canvas.drawRect(
                        (line.left - pad).toFloat(), (line.top - pad).toFloat(),
                        (line.right + pad).toFloat(), (line.bottom + pad).toFloat(), paint
                    )
                }
            }
            val maskMs = System.currentTimeMillis() - t
            t = System.currentTimeMillis()
            val inpainted = engine.inpaint(bitmap, mask)
            val inpaintMs = System.currentTimeMillis() - t
            mask.recycle()

            // 7. Encode bitmap -> data URL (yang benar-benar dipasang di Reader).
            t = System.currentTimeMillis()
            val output = ByteArrayOutputStream()
            inpainted.compress(Bitmap.CompressFormat.JPEG, 85, output)
            val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            val encodeMs = System.currentTimeMillis() - t

            val heapAfter = Debug.getNativeHeapAllocatedSize()
            val translateMs = translateTotalMs - jaMs - detectMs
            val totalMs = decodeMs + jaMs + detectMs + translateMs + maskMs + inpaintMs + encodeMs
            println(
                "PERF ${width}x$height decode=${decodeMs}ms ocrAuto=${autoMs}ms " +
                    "ocrJa=${jaMs}ms(autoBlocks=${autoBlocks.size},jaBlocks=${blocks.size}) " +
                    "detect=${detectMs}ms bubbles=${bubbles.size} " +
                    "translate=${translateMs}ms mask=${maskMs}ms inpaint=${inpaintMs}ms " +
                    "encode=${encodeMs}ms(${dataUrl.length / 1024}KB) " +
                    "total=${totalMs}ms translatedBubbles=${page.bubbles.size} " +
                    "nativeHeapDelta=${(heapAfter - heapBefore) / 1024}KB"
            )

            if (!inpainted.isRecycled) inpainted.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
            file.delete()
        }

        check(true)
    }
}
