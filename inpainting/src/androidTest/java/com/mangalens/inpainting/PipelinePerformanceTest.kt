package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * Pengukuran performa pipeline pada perangkat RMX3561 (Phase 9J, Task F).
 *
 * Setiap tahap diukur terpisah agar angka yang dilaporkan bisa ditelusuri:
 * decode -> OCR -> deteksi balon -> inpainting. Tiga resolusi dipakai karena
 * performa satu gambar tidak mewakili semua halaman.
 */
@RunWith(AndroidJUnit4::class)
class PipelinePerformanceTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val ocr = MlKitOcrEngine()
    private val engine = BasicInpaintingEngine()

    @Test
    fun measuresPipelineStagesAtThreeResolutions() = runBlocking {
        val original = context.assets.open("manga_page_en.jpg").use { BitmapFactory.decodeStream(it) }
        val ja = context.assets.open("manga_page_ja_modern.png").use { BitmapFactory.decodeStream(it) }
        assertTrue("fixture harus termuat", original.width > 0 && ja.width > 0)

        val widths = listOf(800, 1079, 1600)
        for (targetWidth in widths) {
            val height = (original.height.toFloat() * targetWidth / original.width).toInt()
            val page = Bitmap.createScaledBitmap(original, targetWidth, height, true)
            measureOne(page)
            // createScaledBitmap mengembalikan bitmap yang SAMA bila ukurannya pas;
            // me-recycle-nya akan merusak iterasi berikutnya.
            if (page !== original) page.recycle()
        }

        measureOne(ja)
        ja.recycle()
        original.recycle()
    }

    private suspend fun measureOne(page: Bitmap) {
        val runtime = Runtime.getRuntime()
        val usedBefore = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        val ocrStarted = System.currentTimeMillis()
        val blocks = ocr.recognize(page, LanguageScript.AUTO)
        val ocrMs = System.currentTimeMillis() - ocrStarted

        val detectStarted = System.currentTimeMillis()
        val bubbles = BubbleDetector.detect(page, blocks)
        val detectMs = System.currentTimeMillis() - detectStarted

        val mask = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        maskCanvas.drawColor(Color.BLACK)
        val white = Paint().apply { color = Color.WHITE }
        val rects = bubbles.flatMap { it.lines.ifEmpty { listOf(it.textBounds) } }
            .map { Rect(it) }
        rects.forEach { maskCanvas.drawRect(it, white) }

        val inpaintStarted = System.currentTimeMillis()
        val result = engine.inpaint(page, mask)
        val inpaintMs = System.currentTimeMillis() - inpaintStarted

        val usedAfter = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        println(
            "PERF ${page.width}x${page.height} blocks=${blocks.size} bubbles=${bubbles.size} " +
                "ocrMs=$ocrMs detectMs=$detectMs inpaintMs=$inpaintMs " +
                "bitmapMb=${page.byteCount / (1024 * 1024)} heapMb=${usedBefore}->${usedAfter} " +
                "maxHeapMb=${runtime.maxMemory() / (1024 * 1024)}"
        )

        assertTrue("OCR terlalu lambat: $ocrMs ms", ocrMs < 60_000)
        assertTrue("inpainting terlalu lambat: $inpaintMs ms", inpaintMs < 60_000)
        assertTrue("hasil inpainting harus berukuran sama", result.width == page.width &&
            result.height == page.height)
        mask.recycle()
        result.recycle()
    }
}
