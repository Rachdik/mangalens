package com.mangalens.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.data.HistoryStore
import com.mangalens.core.di.Dependencies
import com.mangalens.core.model.HistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Validasi Reader sungguhan di perangkat (Phase 9K, Priority A/C).
 *
 * Alur yang benar-benar dijalankan:
 * WebView memuat halaman dari server lokal -> script pemindai menemukan <img> ->
 * unduh gambar -> OCR -> deteksi balon -> terjemahan ML Kit -> mask -> inpainting
 * -> `img.src` diganti bitmap hasil inpainting -> overlay teks di DOM.
 *
 * Tidak ada mock pipeline: OCR, deteksi balon, terjemahan on-device, dan
 * inpainting memakai kode produksi. Halaman dilayani dari 127.0.0.1 agar tidak
 * bergantung pada situs manga eksternal.
 */
@RunWith(AndroidJUnit4::class)
class ReaderEndToEndTest {

    private lateinit var server: LocalPageServer
    private lateinit var scenario: ActivityScenario<ReaderActivity>
    private val activityRef = AtomicReference<ReaderActivity?>()
    private val statusTexts = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var samplingStatus = false

    @Before
    fun setUp() {
        Dependencies.historyStore = object : HistoryStore {
            override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = flowOf(emptyList())
            override suspend fun record(url: String, title: String) = Unit
            override suspend fun clear() = Unit
        }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val pageBytes = assets.open("page.png").use { it.readBytes() }
        server = LocalPageServer(pageBytes).also { it.start() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(ReaderActivity.intent(context, server.pageUrl))
        scenario.onActivity { activityRef.set(it) }
    }

    @After
    fun tearDown() {
        runCatching { scenario.close() }
        runCatching { server.close() }
    }

    @Test
    fun readerShowsInpaintedPageWithTranslations() {
        startStatusSampling()
        val started = System.currentTimeMillis()
        val translated = awaitState(TRANSLATED_TIMEOUT_MS) { state ->
            state.optInt("overlays") >= 1 && state.optInt("inpainted") >= 1
        }
        val elapsed = System.currentTimeMillis() - started
        println("READER translated in ${elapsed}ms state=$translated")
        val doneStatus = awaitStatus("Terjemahan siap")
        Thread.sleep(300)
        stopStatusSampling()
        println("READER doneStatus=$doneStatus")
        val stages = statusTexts.toList()
        println("READER statusStages=$stages")
        assertTrue("status UI tidak menampilkan tahap apa pun", stages.isNotEmpty())
        assertTrue(
            "status UI tidak pernah menunjukkan selesai: $stages",
            stages.any { it.startsWith("Terjemahan siap") }
        )
        assertTrue(
            "status UI tidak menunjukkan tahap proses: $stages",
            stages.distinct().size >= 3
        )
        assertEquals("mode awal harus terjemahan", "translated", translated.optString("mode"))
        // Phase 9L: 6 region (まあ! kini terpisah dari kolom panjang).
        assertEquals("jumlah kotak terjemahan di DOM", 6, translated.optInt("boxCount"))
        assertEquals("ada teks yang meluber keluar balon", 0, translated.optInt("overflowBoxes"))
        assertTrue("bitmap inpainting tidak terpasang: $translated", translated.optInt("inpainted") >= 1)
        assertTrue("overlay terjemahan tidak muncul: $translated", translated.optInt("overlays") >= 1)

        val src = jsString("document.getElementById('page').src")
        assertTrue("img.src harus bitmap inpainting, bukan '$src'", src.startsWith("data:image"))

        // Kotak terjemahan harus berada di dalam gambar.
        val img = translated.getJSONObject("img")
        val boxes = translated.getJSONArray("boxes")
        assertTrue("tidak ada kotak terjemahan", boxes.length() >= 1)
        for (i in 0 until boxes.length()) {
            val box = boxes.getJSONObject(i)
            assertTrue(
                "kotak $i keluar gambar: $box vs $img",
                box.getInt("l") >= img.getInt("l") - 2 &&
                    box.getInt("t") >= img.getInt("t") - 2 &&
                    box.getInt("l") + box.getInt("w") <= img.getInt("l") + img.getInt("w") + 2 &&
                    box.getInt("t") + box.getInt("h") <= img.getInt("t") + img.getInt("h") + 2
            )
        }

        // Bitmap yang benar-benar ditampilkan harus BERBEDA dari gambar asli
        // (teks asli sudah dihapus oleh inpainting).
        val diff = countDifferentPixels(src)
        println("READER inpainted pixelDiff=$diff")
        assertTrue("bitmap inpainting identik dengan asli (teks tidak dihapus)", diff > 500)

        awaitImageLoaded()
        capture("reader_translated")

        // --- Zoom: kotak harus mengikuti skala gambar ---
        val before = translated
        val beforeFrac = boxFractionX(before)
        js("document.getElementById('page').style.width='62%';")
        Thread.sleep(900)
        val zoomed = awaitState(10_000) { it.getJSONObject("img").getInt("w") < before.getJSONObject("img").getInt("w") - 50 }
        val zoomedFrac = boxFractionX(zoomed)
        println("READER zoom before=$beforeFrac after=$zoomedFrac img=${zoomed.optJSONObject("img")}")
        assertTrue(
            "posisi horizontal kotak bergeser setelah zoom: $beforeFrac vs $zoomedFrac",
            kotlin.math.abs(beforeFrac - zoomedFrac) < 0.02
        )
        // Tipografi adaptif: saat diperkecil, teks tetap di dalam balon.
        println("READER zoomed typography boxes=${zoomed.optInt("boxCount")} " +
            "overflow=${zoomed.optInt("overflowBoxes")} minFont=${zoomed.optInt("minFont")}")
        assertEquals("kotak terjemahan hilang saat zoom-out", 6, zoomed.optInt("boxCount"))
        assertEquals("teks meluber keluar balon saat zoom-out", 0, zoomed.optInt("overflowBoxes"))
        capture("reader_zoomed")

        // --- Scroll: container position:fixed harus tetap menempel pada gambar ---
        val beforeOffset = boxOffsetY(zoomed)
        js("window.scrollTo(0, 420);")
        Thread.sleep(700)
        val scrolled = awaitState(10_000) { true }
        val afterOffset = boxOffsetY(scrolled)
        println("READER scroll offset before=$beforeOffset after=$afterOffset")
        assertTrue(
            "kotak tidak menempel pada gambar saat digulir: $beforeOffset vs $afterOffset",
            kotlin.math.abs(beforeOffset - afterOffset) <= 3
        )
        capture("reader_scrolled")

        // Kembali ke atas agar tangkapan mode menampilkan halaman utuh.
        js("window.scrollTo(0, 0);")
        Thread.sleep(600)

        // --- Mode asli: gambar asli dipulihkan, overlay disembunyikan ---
        clickMode()
        val original = awaitState(10_000) { it.optString("mode") == "original" && it.optInt("overlays") == 0 }
        val originalSrc = jsString("document.getElementById('page').src")
        println("READER original mode=$original src=${originalSrc.take(60)}")
        assertEquals("mode asli", "original", original.optString("mode"))
        assertTrue("gambar asli tidak dipulihkan: $originalSrc", originalSrc.startsWith("http://127.0.0.1"))
        assertTrue("overlay harus disembunyikan di mode asli", original.optInt("overlays") == 0)
        capture("reader_original")

        // --- Kembali ke terjemahan: memakai hasil cache, tanpa OCR ulang ---
        val switchStarted = System.currentTimeMillis()
        clickMode()
        val back = awaitState(15_000) { it.optString("mode") == "translated" && it.optInt("overlays") >= 1 }
        val switchMs = System.currentTimeMillis() - switchStarted
        awaitImageLoaded()
        println("READER switch back in ${switchMs}ms state=$back")
        assertEquals("translated", back.optString("mode"))
        assertTrue("overlay tidak kembali setelah mode terjemahan", back.optInt("overlays") >= 1)
        assertEquals("kotak terjemahan hilang setelah kembali dari mode asli", 6, back.optInt("boxCount"))
        capture("reader_translated_again")

        // --- Tiga siklus bolak-balik: tidak boleh ada overlay ganda/tersembunyi ---
        repeat(3) { cycle ->
            clickMode()
            val asli = awaitState(10_000) {
                it.optString("mode") == "original" && it.optInt("overlays") == 0
            }
            val asliSrc = jsString("document.getElementById('page').src")
            assertEquals("siklus ${cycle + 1}: mode asli", "original", asli.optString("mode"))
            assertTrue(
                "siklus ${cycle + 1}: gambar asli tidak dipulihkan",
                asliSrc.startsWith("http://127.0.0.1")
            )

            clickMode()
            val terjemahan = awaitState(10_000) {
                it.optString("mode") == "translated" && it.optInt("boxCount") >= 6
            }
            awaitImageLoaded()
            val terjemahanSrc = jsString("document.getElementById('page').src")
            assertEquals(
                "siklus ${cycle + 1}: mode terjemahan",
                "translated",
                terjemahan.optString("mode")
            )
            assertTrue(
                "siklus ${cycle + 1}: bitmap inpainting tidak kembali",
                terjemahanSrc.startsWith("data:image")
            )
            // Tidak boleh ada kotak ganda (10) atau hilang (0).
            assertEquals(
                "siklus ${cycle + 1}: jumlah kotak terjemahan salah",
                6,
                terjemahan.optInt("boxCount")
            )
            println("READER cycle ${cycle + 1} ok boxes=${terjemahan.optInt("boxCount")}")
        }
        capture("reader_three_cycles")
    }

    /**
     * Rekam teks statusView selama pemrosesan untuk membuktikan UI benar-benar
     * menampilkan tahap (bukan sekadar log internal).
     */
    private fun startStatusSampling() {
        samplingStatus = true
        Thread {
            while (samplingStatus) {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val value = AtomicReference<String>()
                val latch = CountDownLatch(1)
                handler.post {
                    val text = activityRef.get()?.findViewById<android.widget.TextView>(R.id.statusView)
                    if (text != null && text.visibility == View.VISIBLE) {
                        value.set(text.text?.toString())
                    }
                    latch.countDown()
                }
                latch.await(1, TimeUnit.SECONDS)
                value.get()?.takeIf { it.isNotBlank() }?.let { text ->
                    if (!statusTexts.contains(text)) statusTexts.add(text)
                }
                Thread.sleep(80)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun stopStatusSampling() {
        samplingStatus = false
    }

    /** Tunggu statusView menampilkan teks dengan awalan tertentu. */
    private fun awaitStatus(prefix: String, timeoutMs: Long = 15_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = AtomicReference<String?>()
            val latch = CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val view = activityRef.get()?.findViewById<android.widget.TextView>(R.id.statusView)
                value.set(if (view != null && view.visibility == View.VISIBLE) view.text?.toString() else null)
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
            val text = value.get()
            if (text != null && text.startsWith(prefix)) return text
            Thread.sleep(100)
        }
        return null
    }

    /** Tunggu WebView selesai memuat ulang bitmap (data URL) sebelum tangkapan layar. */
    private fun awaitImageLoaded() {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val loaded = jsString(
                "(function(){var i=document.getElementById('page');" +
                    "return (i&&i.complete&&i.naturalWidth>0)?'1':'0';})()"
            )
            if (loaded == "1") {
                Thread.sleep(600)
                return
            }
            Thread.sleep(200)
        }
    }

    private fun clickMode() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            activityRef.get()?.findViewById<View>(R.id.modeButton)?.performClick()
        }
    }

    private fun awaitState(timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            val raw = evalJs("window.__mangalensReaderState?window.__mangalensReaderState():'{}'")
            if (!raw.isNullOrBlank() && raw != "null") {
                runCatching { JSONObject(unquote(raw)) }.getOrNull()?.let { state ->
                    last = state
                    if (predicate(state)) return state
                }
            }
            Thread.sleep(400)
        }
        return last
    }

    private fun boxFractionX(state: JSONObject): Double {
        val img = state.getJSONObject("img")
        val box = state.getJSONArray("boxes").getJSONObject(0)
        return (box.getInt("l") - img.getInt("l")).toDouble() / img.getInt("w")
    }

    private fun boxOffsetY(state: JSONObject): Int {
        val img = state.getJSONObject("img")
        val box = state.getJSONArray("boxes").getJSONObject(0)
        return box.getInt("t") - img.getInt("t")
    }

    /** Hitung piksel berbeda antara gambar asli dan bitmap inpainting. */
    private fun countDifferentPixels(src: String): Int {
        val comma = src.indexOf(',')
        if (comma <= 0) return 0
        val bytes = runCatching { Base64.decode(src.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
            ?: return 0
        val displayed = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return 0
        val original = InstrumentationRegistry.getInstrumentation().context.assets
            .open("page.png").use { BitmapFactory.decodeStream(it) }
        if (displayed.width != original.width || displayed.height != original.height) {
            println("READER size mismatch displayed=${displayed.width}x${displayed.height} original=${original.width}x${original.height}")
            return Int.MAX_VALUE
        }
        var diff = 0
        for (y in 0 until original.height step 2) {
            for (x in 0 until original.width step 2) {
                val a = original.getPixel(x, y)
                val b = displayed.getPixel(x, y)
                val delta = kotlin.math.abs((a and 0xFF) - (b and 0xFF)) +
                    kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                    kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
                if (delta > 45) diff++
            }
        }
        return diff
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull() ?: return
        val dir = File(instrumentation.context.getExternalFilesDir(null), "reader")
        dir.mkdirs()
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("READER screenshot ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    private fun js(script: String) {
        evalJs(script)
    }

    private fun jsString(script: String): String = unquote(evalJs(script).orEmpty())

    private fun evalJs(script: String): String? {
        val activity = activityRef.get() ?: return null
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity.findViewById<WebView>(R.id.webView).evaluateJavascript(script) { value ->
                holder[0] = value
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        return holder[0]
    }

    private fun unquote(raw: String): String =
        runCatching { JSONTokener(raw).nextValue() as? String ?: raw }.getOrDefault(raw)

    /** Server HTTP minimal: satu halaman uji + satu gambar manga. */
    private class LocalPageServer(private val pageBytes: ByteArray) {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val host = "127.0.0.1"
        private val port = socket.localPort
        val pageUrl: String get() = "http://$host:$port/index.html"

        fun start() {
            Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread { handle(client) }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        fun close() {
            runCatching { socket.close() }
        }

        private fun handle(client: Socket) {
            client.use {
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine().orEmpty()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1).orEmpty()
                val body: ByteArray
                val type: String
                when {
                    path.startsWith("/page.png") -> {
                        body = pageBytes
                        type = "image/png"
                    }

                    path.startsWith("/index.html") || path == "/" -> {
                        body = HTML.toByteArray()
                        type = "text/html; charset=utf-8"
                    }

                    else -> {
                        body = "not found".toByteArray()
                        type = "text/plain"
                    }
                }
                val header = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                client.getOutputStream().apply {
                    write(header.toByteArray())
                    write(body)
                    flush()
                }
            }
        }

        private companion object {
            val HTML = """
                <!doctype html>
                <html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>body{margin:0;background:#111}</style>
                </head><body>
                <img id="page" src="/page.png" style="width:100%;display:block">
                <div style="height:2200px;background:#111"></div>
                </body></html>
            """.trimIndent()
        }
    }

    private companion object {
        /** OCR + terjemahan model + inpainting + render pada perangkat. */
        const val TRANSLATED_TIMEOUT_MS = 120_000L
    }
}
