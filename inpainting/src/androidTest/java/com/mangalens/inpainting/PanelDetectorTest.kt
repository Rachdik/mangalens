package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Validasi [PanelDetector] pada halaman nyata dan kasus sintetis (Phase 9J, Task D). */
@RunWith(AndroidJUnit4::class)
class PanelDetectorTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun detectsPanelsOnRealJapaneseMangaPage() {
        val bitmap = loadAsset("manga_page_ja_modern.png")
        assertTrue("fixture harus termuat", bitmap != null)
        val analysis = PanelDetector.analyzeBands(bitmap!!)
        println(
            "PANELS image=${bitmap.width}x${bitmap.height} analysis=${analysis.analysisWidth}x${analysis.analysisHeight} " +
                "rows=${analysis.rowBands} cols=${analysis.colBands}"
        )
        val panels = PanelDetector.detect(bitmap)

        println("PANELS image=${bitmap.width}x${bitmap.height} count=${panels.size}")
        panels.forEachIndexed { index, rect -> println("PANEL ${index + 1} $rect ${rect.width()}x${rect.height()}") }

        val debug = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(debug)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.RED
        }
        panels.forEach { canvas.drawRect(it, paint) }
        val out = File(context.getExternalFilesDir(null), "panel_detection_ja.png")
        out.outputStream().use { debug.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SAVED ${out.absolutePath}")

        // Halaman ini terdiri dari 3 baris panel; deteksi minimal harus memisahkan baris.
        assertTrue("panel terdeteksi kurang dari 2: ${panels.size}", panels.size >= 2)
        panels.forEach { rect ->
            assertTrue("panel $rect keluar dari gambar", rect.left >= 0 && rect.top >= 0 &&
                rect.right <= bitmap.width && rect.bottom <= bitmap.height)
        }
    }

    @Test
    fun borderlessPageFallsBackToSinglePanel() {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.rgb(30, 30, 30) }
        // Ilustrasi penuh tanpa garis panel: harus tetap satu panel, bukan nol.
        canvas.drawCircle(300f, 400f, 200f, paint)
        val panels = PanelDetector.detect(bitmap)
        println("PANELS borderless count=${panels.size} first=${panels.firstOrNull()}")
        assertEquals(1, panels.size)
        assertEquals(Rect(0, 0, 600, 800), panels.first())
    }

    @Test
    fun speechBubbleInsidePanelIsNotReportedAsPanel() {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        // Dua panel bersambung dengan garis batas. Isi panel diberi pola agar
        // tidak ada baris yang 100% putih (baris putih penuh memang celah panel).
        val fill = Paint().apply { color = Color.rgb(170, 170, 170) }
        canvas.drawRect(10f, 10f, 590f, 390f, fill)
        canvas.drawRect(10f, 410f, 590f, 790f, fill)
        canvas.drawRect(4f, 4f, 596f, 396f, border)
        canvas.drawRect(4f, 404f, 596f, 796f, border)
        // Balon putih dengan garis tipis di dalam panel atas.
        canvas.drawOval(200f, 120f, 420f, 300f, border)
        // Isi balon tetap putih: itu bagian dari panel, bukan celah.
        canvas.drawOval(205f, 125f, 415f, 295f, Paint().apply { color = Color.WHITE })
        val analysis = PanelDetector.analyzeBands(bitmap)
        println(
            "PANELS synthetic analysis=${analysis.analysisWidth}x${analysis.analysisHeight} " +
                "rows=${analysis.rowBands} cols=${analysis.colBands}"
        )
        val panels = PanelDetector.detect(bitmap)
        println("PANELS synthetic count=${panels.size} ${panels.joinToString()}")
        assertEquals(2, panels.size)
        panels.forEach { rect ->
            assertTrue(
                "balon (${rect}) tidak boleh dianggap panel penuh",
                rect.width() > 600 * 0.5 && rect.height() > 800 * 0.3
            )
        }
    }

    private fun loadAsset(name: String): Bitmap? = runCatching {
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
