package com.mangalens.floating

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.TranslatedBubble
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.MyMemoryTranslationProvider
import com.mangalens.translation.TranslationSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

/**
 * Verifikasi RENDERING teks terjemahan memakai kode produksi
 * [BubbleOverlay.showTranslations] pada perangkat (Phase 9J, Task B).
 *
 * Batasan yang jujur: overlay ditambahkan ke [RecordingWindowManager] palsu,
 * bukan jendela sistem sungguhan (izin "tampilkan di atas aplikasi lain" tidak
 * bisa diberikan lewat instrumentation tanpa interaksi pengguna). Yang diuji
 * tetap kode produksi yang sama: pembuatan TextView, auto-size, pembungkusan
 * baris, dan perhitungan koordinat WindowManager.LayoutParams. Hasilnya
 * dikompositkan ke atas gambar manga lalu disimpan sebagai PNG untuk diperiksa
 * secara visual.
 */
@RequiresApi(Build.VERSION_CODES.R)
@RunWith(AndroidJUnit4::class)
class BubbleOverlayRenderTest {

    private val screenWidth = 1080
    private val screenHeight = 2412

    @Test
    fun rendersRealJapaneseTranslationsOnMangaPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val decoded = loadAsset(context, "manga_page_ja_modern.png")
        assertTrue("fixture manga Jepang harus termuat", decoded != null)
        val bitmap = decoded!!

        val translator = MangaPageTranslator(MlKitOcrEngine()) { source, target ->
            MyMemoryTranslationProvider(source, target)
        }
        val page = try {
            translator.translate(bitmap, LanguageScript.JAPANESE, "ja", "id")
        } finally {
            translator.close()
        }
        println("RENDER_BEGIN")
        println(
            "IMAGE ${bitmap.width}x${bitmap.height} OCR=${page.ocrBlocks} " +
                "BUBBLES=${page.bubbles.size} provider=${page.providerName}"
        )
        page.bubbles.forEach { bubble ->
            println(
                "TEXT text='${bubble.sourceText.replace("\n", " / ")}' " +
                    "-> '${bubble.targetText.replace("\n", " / ")}' textBounds=${bubble.textBounds}"
            )
        }

        val scale = screenWidth.toFloat() / bitmap.width
        val screen = RecordingWindowManager(screenWidth, screenHeight)
        val overlay = BubbleOverlay(
            FakeWindowContext(context, screen.manager),
            onTranslate = {},
            onStop = {},
            onRefresh = {},
            onPause = {},
            onResume = {},
            onSettings = {},
            settings = TranslationSettings(context)
        )

        val shown = overlay.showTranslations(page.bubbles, scale)
        println("SCALE $scale SHOWN $shown of ${page.bubbles.size}")

        var overflow = 0
        var outside = 0
        screen.added.forEachIndexed { index, (view, params) ->
            val textView = view as TextView
            val contentHeight = textView.layout?.height ?: 0
            val available = params.height - textView.paddingTop - textView.paddingBottom
            // Produksi menumbuhkan kotak setelah view terpasang (post{} di
            // showTranslations): maksimum 1,8x tinggi awal, sisa layar, dan 32%
            // tinggi layar. Overflow sejati = teks melebihi batas tumbuh itu.
            val grownAbility = minOf(
                params.height * 1.8f,
                (screenHeight - params.y).toFloat(),
                screenHeight * 0.32f
            )
            val isOverflow = contentHeight > grownAbility + 1
            if (isOverflow) overflow++
            val inside = params.x >= 0 && params.y >= 0 &&
                params.x + params.width <= screenWidth && params.y + params.height <= screenHeight
            if (!inside) outside++
            println(
                "OVERLAY ${index + 1} rect=[${params.x},${params.y}]" +
                    "+[${params.width}x${params.height}] lines=${textView.layout?.lineCount ?: 0} " +
                    "textHeight=$contentHeight boxHeight=$available grownMax=${grownAbility.toInt()} " +
                    "overflow=$isOverflow inside=$inside text='${textView.text}'"
            )
            Log.d(TAG, "overlay ${index + 1} ${params.x},${params.y} ${params.width}x${params.height} '${textView.text}'")
        }

        // Overlap antar tempelan diukur, bukan disembunyikan: dua balon yang
        // digabung BubbleDetector akan menghasilkan dua kotak yang bertumpuk.
        var overlappingPairs = 0
        val rects = screen.added.map { (_, p) -> Rect(p.x, p.y, p.x + p.width, p.y + p.height) }
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val intersection = Rect()
                if (intersection.setIntersect(rects[i], rects[j])) {
                    val area = intersection.width() * intersection.height()
                    val smallest = minOf(rects[i].width() * rects[i].height(), rects[j].width() * rects[j].height())
                    if (area > smallest * 0.10) overlappingPairs++
                }
            }
        }
        println("OVERLAP pairs=$overlappingPairs of ${rects.size} overlays")

        val composite = composite(bitmap, scale, screen)
        val out = File(context.getExternalFilesDir(null), "overlay_ja_render.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { composite.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SAVED ${out.absolutePath} ${composite.width}x${composite.height}")
        println("RENDER_END")

        assertTrue("Should render at least one bubble", shown >= 1)
        assertEquals("Every rendered overlay must be inside the screen", 0, outside)
        assertEquals("Rendered text must not overflow its overlay box", 0, overflow)
        overlay.clearTranslations()
    }

    @Test
    fun placementDoesNotDriftWithImageScale() {
        val context = InstrumentationRegistry.getInstrumentation().context
        // Jauh dari tepi kiri agar pembatas layar tidak ikut menggeser kotak.
        val textBounds = Rect(400, 200, 600, 420)
        val bubble = TranslatedBubble(
            bounds = Rect(380, 180, 630, 450),
            textBounds = textBounds,
            sourceText = "これはテストです",
            targetText = "INI ADALAH SEBUAH UJI COBA YANG CUKUP PANJANG UNTUK MEMBUNGKUS BARIS",
            fillColor = Color.WHITE,
            textColor = Color.BLACK,
            textLines = listOf(textBounds)
        )

        val at1 = placeBubble(context, bubble, scale = 1f)
        val at2 = placeBubble(context, bubble, scale = 2f)

        println("DRIFT scale=1 $at1")
        println("DRIFT scale=2 $at2")
        // Skala 2x berarti koordinat layar menjadi separuh; toleransi 2 px untuk
        // pembulatan. Lebar memakai batas baca minimum layar (tidak berskala),
        // jadi yang diuji adalah titik tengah dan tepi atas.
        assertEquals(at1.centerX() / 2f, at2.centerX().toFloat(), 2f)
        assertEquals(at1.top / 2f, at2.top.toFloat(), 2f)
        assertTrue("lebar minimal harus terbaca (>= 22% layar)", at2.width() >= 230)
    }

    private fun placeBubble(context: Context, bubble: TranslatedBubble, scale: Float): Rect {
        val screen = RecordingWindowManager(screenWidth, screenHeight)
        val overlay = BubbleOverlay(
            FakeWindowContext(context, screen.manager),
            {}, {}, {}, {}, {}, {},
            TranslationSettings(context)
        )
        overlay.showTranslations(listOf(bubble), scale)
        val params = screen.added.firstOrNull()?.second
            ?: error("overlay tidak dibuat untuk scale=$scale")
        val rect = Rect(params.x, params.y, params.x + params.width, params.y + params.height)
        overlay.clearTranslations()
        return rect
    }

    /** Gambar manga + overlay produksi, digambar ke satu bitmap. */
    private fun composite(page: Bitmap, scale: Float, screen: RecordingWindowManager): Bitmap {
        val result = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawBitmap(page, 0f, 0f, null)
        canvas.restore()
        for ((view, params) in screen.added) {
            canvas.save()
            canvas.translate(params.x.toFloat(), params.y.toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        return result
    }

    private fun loadAsset(context: Context, name: String): Bitmap? = runCatching {
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }
    }.onFailure { Log.e(TAG, "asset $name gagal dimuat", it) }.getOrNull()

    /** WindowManager tanpa jendela nyata: mencatat view + parameter layout. */
    private class RecordingWindowManager(
        private val width: Int,
        private val height: Int
    ) {

        val added = mutableListOf<Pair<android.view.View, WindowManager.LayoutParams>>()

        private fun record(view: android.view.View, params: WindowManager.LayoutParams) {
            added += view to params
            val w = if (params.width > 0) params.width else width
            val h = if (params.height > 0) params.height else height
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, w, h)
        }

        val manager: WindowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addView" -> {
                    record(
                        args!![0] as android.view.View,
                        args[1] as WindowManager.LayoutParams
                    )
                    null
                }

                "updateViewLayout" -> {
                    val view = args!![0] as android.view.View
                    val lp = args[1] as WindowManager.LayoutParams
                    added.removeAll { it.first === view }
                    record(view, lp)
                    null
                }

                "removeView", "removeViewImmediate" -> {
                    val view = args!![0] as android.view.View
                    added.removeAll { it.first === view }
                    null
                }

                "getCurrentWindowMetrics", "getMaximumWindowMetrics" ->
                    WindowMetrics(Rect(0, 0, width, height), WindowInsets.Builder().build())

                else -> null
            }
        } as WindowManager
    }

    /** Context yang mengembalikan WindowManager palsu di atas. */
    private class FakeWindowContext(
        base: Context,
        private val windowManager: WindowManager
    ) : ContextWrapper(base) {
        // getSystemService(Class) di ContextWrapper bersifat final dan meneruskan
        // ke getSystemService(String), jadi override nama sudah cukup.
        override fun getSystemService(name: String): Any? =
            if (name == Context.WINDOW_SERVICE) windowManager else super.getSystemService(name)
    }

    private companion object {
        const val TAG = "MangaLensRender"
    }
}
