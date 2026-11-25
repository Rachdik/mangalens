package com.mangalens.reader

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
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
 * Gestur NYATA pada Reader (Phase 9L): pinch-to-zoom, swipe, ganti mode saat
 * diperbesar, dan rotasi perangkat. Semua gerakan dikirim lewat UiAutomator,
 * bukan dengan mengubah CSS.
 */
@RunWith(AndroidJUnit4::class)
class ReaderGestureTest {

    private lateinit var server: GesturePageServer
    private lateinit var scenario: ActivityScenario<ReaderActivity>
    private lateinit var device: UiDevice
    private val activityRef = AtomicReference<ReaderActivity?>()

    @Before
    fun setUp() {
        Dependencies.historyStore = object : HistoryStore {
            override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = flowOf(emptyList())
            override suspend fun record(url: String, title: String) = Unit
            override suspend fun clear() = Unit
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        val pageBytes = instrumentation.context.assets.open("page.png").use { it.readBytes() }
        server = GesturePageServer(pageBytes).also { it.start() }
        scenario = ActivityScenario.launch(
            ReaderActivity.intent(instrumentation.targetContext, server.pageUrl)
        )
        scenario.onActivity { activityRef.set(it) }
    }

    @After
    fun tearDown() {
        runCatching { device.setOrientationNatural() }
        runCatching { scenario.close() }
        runCatching { server.close() }
    }

    @Test
    fun pinchSwipeAndRotateKeepOverlayAligned() {
        val translated = awaitState(120_000) { it.optInt("boxCount") >= 6 }
        assertEquals("terjemahan tidak siap", 6, translated.optInt("boxCount"))
        awaitImageLoaded()
        val baseScale = visualViewportScale()
        val baseFraction = boxFraction(translated, 0)
        val baseOffset = boxOffsetY(translated, 0)
        println("GESTURE start scale=$baseScale boxes=${translated.optInt("boxCount")} state=$translated")
        capture("gesture_normal")

        val web = device.findObject(By.clazz(WebView::class.java))
        assertTrue("WebView tidak ditemukan untuk gestur", web != null)

        // --- Pinch zoom in (dua jari sungguhan) ---
        web!!.pinchOpen(0.6f, 700)
        device.waitForIdle(1500)
        val zoomedInScale = awaitScale(1.05, 8_000)
        println("GESTURE pinchOpen scale=$zoomedInScale")
        assertTrue(
            "pinch-to-zoom tidak mengubah skala halaman (scale=$zoomedInScale)",
            zoomedInScale > 1.05
        )
        val zoomedIn = awaitState(8_000) { it.optInt("boxCount") >= 6 }
        assertEquals("kotak hilang setelah zoom in", 6, zoomedIn.optInt("boxCount"))
        assertEquals("teks meluber setelah zoom in", 0, zoomedIn.optInt("overflowBoxes"))
        assertTrue(
            "overlay bergeser terhadap gambar setelah zoom in: " +
                "$baseFraction vs ${boxFraction(zoomedIn, 0)}",
            kotlin.math.abs(baseFraction - boxFraction(zoomedIn, 0)) < 0.02
        )
        println("GESTURE zoomed boxes=${zoomedIn.optInt("boxCount")} " +
            "overflow=${zoomedIn.optInt("overflowBoxes")} minFont=${zoomedIn.optInt("minFont")}")
        capture("gesture_zoomed_in")

        // --- Swipe (scroll sungguhan) setelah zoom ---
        val imgTopBefore = zoomedIn.getJSONObject("img").getInt("t")
        val scrollBefore = pageScroll()
        val center = web.visibleCenter
        // Swipe dua jari? Tidak: satu jari nyata dari UiAutomator.
        device.swipe(center.x, center.y + 300, center.x, center.y - 300, 30)
        device.waitForIdle(1200)
        var scrollAfter = pageScroll()
        if (kotlin.math.abs(scrollAfter - scrollBefore) < 10) {
            // WebView yang sedang di-zoom menangani pan sendiri; pakai gestur
            // scroll UiAutomator sebagai upaya kedua yang tetap gestur nyata.
            web.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f)
            device.waitForIdle(1200)
            scrollAfter = pageScroll()
        }
        val scrolled = awaitState(8_000) { it.optInt("boxCount") >= 6 }
        val imgTopAfter = scrolled.getJSONObject("img").getInt("t")
        println("GESTURE swipe scroll $scrollBefore -> $scrollAfter " +
            "imgTop $imgTopBefore -> $imgTopAfter offset=${boxOffsetY(scrolled, 0)} (was $baseOffset)")
        assertTrue(
            "gestur scroll tidak menggeser halaman ($scrollBefore -> $scrollAfter)",
            kotlin.math.abs(scrollAfter - scrollBefore) >= 10
        )
        assertEquals("kotak hilang setelah scroll", 6, scrolled.optInt("boxCount"))
        assertTrue(
            "kotak tidak menempel pada gambar setelah scroll+zoom",
            kotlin.math.abs(boxOffsetY(scrolled, 0) - baseOffset) <= 3
        )
        capture("gesture_scrolled_after_zoom")

        // --- Ganti mode saat diperbesar (sentuhan nyata pada tombol) ---
        val modeButton = device.findObject(By.text("Terjemahan"))
        assertTrue("tombol mode tidak ditemukan", modeButton != null)
        modeButton!!.click()
        device.waitForIdle(1200)
        val original = awaitState(8_000) {
            it.optString("mode") == "original" && it.optInt("overlays") == 0
        }
        assertEquals("mode asli setelah zoom", "original", original.optString("mode"))
        capture("gesture_original_zoomed")

        val backButton = device.findObject(By.text("Asli"))
        assertTrue("tombol mode asli tidak ditemukan", backButton != null)
        backButton!!.click()
        device.waitForIdle(1200)
        val back = awaitState(8_000) {
            it.optString("mode") == "translated" && it.optInt("boxCount") >= 6
        }
        assertEquals("terjemahan tidak kembali setelah zoom", 6, back.optInt("boxCount"))
        assertEquals("teks meluber setelah kembali ke terjemahan", 0, back.optInt("overflowBoxes"))
        capture("gesture_translated_zoomed")

        // --- Pinch zoom out ---
        web.pinchClose(0.6f, 700)
        device.waitForIdle(1500)
        val zoomedOutScale = awaitScale(1.02, 8_000)
        println("GESTURE pinchClose scale=$zoomedOutScale")
        assertTrue(
            "pinch-out tidak mengembalikan skala (scale=$zoomedOutScale)",
            zoomedOutScale <= 1.02
        )
        capture("gesture_zoomed_out")

        // --- Rotasi perangkat ---
        device.setOrientationLeft()
        device.waitForIdle(2000)
        // Rotasi membuat Activity dibuat ulang: WebView baru memuat halaman lagi
        // dan menerjemahkannya dari cache/file. Pegang Activity yang baru dulu.
        scenario.onActivity { activityRef.set(it) }
        val landscape = awaitState(120_000) {
            it.optInt("boxCount") >= 6 && it.optJSONObject("img") != null
        }
        val img = landscape.optJSONObject("img")
        println("GESTURE landscape boxes=${landscape.optInt("boxCount")} img=$img " +
            "overflow=${landscape.optInt("overflowBoxes")}")
        assertEquals("kotak hilang setelah rotasi", 6, landscape.optInt("boxCount"))
        assertEquals("teks meluber setelah rotasi", 0, landscape.optInt("overflowBoxes"))
        capture("gesture_landscape")
        device.setOrientationNatural()
        device.waitForIdle(1500)
    }

    /** Posisi gulir efektif (layout scroll + pan visual viewport saat zoom). */
    private fun pageScroll(): Double {
        val raw = evalJs(
            "(function(){" +
                "var v=window.visualViewport?window.visualViewport.pageTop:0;" +
                "return (window.scrollY||0)+v;})()"
        ).orEmpty()
        return runCatching { raw.toDouble() }.getOrDefault(0.0)
    }

    private fun visualViewportScale(): Double {
        val raw = evalJs(
            "(function(){return (window.visualViewport&&window.visualViewport.scale)||1;})()"
        ).orEmpty()
        return runCatching { raw.toDouble() }.getOrDefault(1.0)
    }

    private fun awaitScale(target: Double, timeoutMs: Long): Double {
        val deadline = System.currentTimeMillis() + timeoutMs
        var scale = visualViewportScale()
        while (System.currentTimeMillis() < deadline) {
            scale = visualViewportScale()
            if (if (target > 1) scale >= target else scale <= target) return scale
            Thread.sleep(200)
        }
        return scale
    }

    private fun boxFraction(state: JSONObject, index: Int): Double {
        val img = state.getJSONObject("img")
        val box = state.getJSONArray("boxes").getJSONObject(index)
        return (box.getInt("l") - img.getInt("l")).toDouble() / img.getInt("w")
    }

    private fun boxOffsetY(state: JSONObject, index: Int): Int {
        val img = state.getJSONObject("img")
        val box = state.getJSONArray("boxes").getJSONObject(index)
        return box.getInt("t") - img.getInt("t")
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

    private fun awaitImageLoaded() {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (evalJs(
                    "(function(){var i=document.getElementById('page');" +
                        "return (i&&i.complete&&i.naturalWidth>0)?'1':'0';})()"
                )?.contains("1") == true
            ) {
                Thread.sleep(600)
                return
            }
            Thread.sleep(200)
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
            ?: return
        val dir = File(instrumentation.context.getExternalFilesDir(null), "reader")
        dir.mkdirs()
        val file = File(dir, "phase9l_$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("GESTURE screenshot ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
    }

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

    private class GesturePageServer(private val pageBytes: ByteArray) {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val port = socket.localPort
        val pageUrl: String get() = "http://127.0.0.1:$port/index.html"

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
                <div style="height:2600px;background:#111"></div>
                </body></html>
            """.trimIndent()
        }
    }
}
