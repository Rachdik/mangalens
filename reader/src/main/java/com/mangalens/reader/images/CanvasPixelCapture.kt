package com.mangalens.reader.images

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fallback ekstraksi canvas ketika `toDataURL()` diblokir browser karena canvas
 * **tainted** (gambar halaman dimuat dari CDN cross-origin, seperti kmanga).
 *
 * Pendekatan: halaman digulir per potong setinggi viewport, lalu piksel area
 * canvas diambil dengan [PixelCopy]. Semua potongan disusun kembali menjadi satu
 * bitmap seukuran canvas (diskalakan) sehingga koordinat OCR tetap memakai
 * sistem koordinat canvas yang sama dengan overlay.
 *
 * Yang dijaga di sini:
 * - area tangkapan hanya kotak canvas (bukan toolbar/UI aplikasi),
 * - overlay terjemahan milik MangaLens disembunyikan saat menangkap agar tidak
 *   ikut terbaca sebagai teks sumber,
 * - hasil yang kosong/buram ditolak, bukan dikirim ke OCR,
 * - scroll pengguna dipulihkan setelah selesai.
 */
object CanvasPixelCapture {

    private const val TAG = "MangaLensCanvas"

    /** Batas piksel bitmap gabungan agar tidak menahan memori berlebihan. */
    private const val MAX_CAPTURE_PIXELS = 12_000_000.0

    /** Lebar kerja maksimum: cukup untuk OCR tanpa membengkakkan memori. */
    private const val MAX_CAPTURE_WIDTH = 1600.0

    /** Jeda agar compositor sempat menggambar setelah scroll/overlay disembunyikan. */
    private const val SETTLE_MS = 380L

    /** Tumpang tindih kecil antar potongan agar teks di batas tidak terpotong. */
    private const val TILE_OVERLAP_CSS = 24.0

    private const val MAX_TILES = 12

    suspend fun capture(
        activity: Activity,
        webView: WebView,
        key: String,
        eval: suspend (String) -> String?,
        /** Menolak gambar yang isinya sama dengan tangkapan sebelumnya. */
        acceptContent: (Int) -> Boolean = { true }
    ): CanvasCapture? = withContext(Dispatchers.Main) {
        captureOnMain(activity, webView, key, eval, acceptContent)
    }

    private suspend fun captureOnMain(
        activity: Activity,
        webView: WebView,
        key: String,
        eval: suspend (String) -> String?,
        acceptContent: (Int) -> Boolean
    ): CanvasCapture? {
        val prep = json(eval("window.__mangalensCanvasPrep&&window.__mangalensCanvasPrep(${quote(key)})"))
            ?: return null
        if (!prep.optBoolean("ok")) {
            Log.w(TAG, "persiapan gagal: ${prep.optString("error")}")
            return null
        }

        val cssW = prep.optDouble("cssW", 0.0)
        val cssH = prep.optDouble("cssH", 0.0)
        val vh = prep.optDouble("vh", 0.0)
        val dpr = prep.optDouble("dpr", 1.0).coerceIn(0.5, 4.0)
        val originalScroll = prep.optDouble("scrollY", 0.0)
        if (cssW < 50 || cssH < 50 || vh < 50) return null

        val scale = captureScale(cssW * dpr, cssH * dpr)
        val outW = max(1, (cssW * dpr * scale).roundToInt())
        val outH = max(1, (cssH * dpr * scale).roundToInt())
        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        output.eraseColor(Color.WHITE)

        val step = max(1.0, vh - TILE_OVERLAP_CSS)
        val tiles = min(MAX_TILES, ceil(cssH / step).toInt())
        var capturedTiles = 0

        val location = IntArray(2)
        webView.getLocationOnScreen(location)
        // PixelCopy memakai koordinat JENDELA, getLocationOnScreen memakai
        // koordinat layar; selisihnya harus dikompensasi.
        val decorLocation = IntArray(2)
        activity.window.decorView.getLocationOnScreen(decorLocation)
        val windowW = activity.window.decorView.width
        val windowH = activity.window.decorView.height

        try {
            eval("window.__mangalensSetOverlayHidden&&window.__mangalensSetOverlayHidden(true)")
            delay(SETTLE_MS)

            // Satu putaran menangkap semua potongan. Bila tidak ada potongan yang
            // berhasil (mis. compositor belum selesai setelah fling), putaran
            // diulang sekali dengan jeda lebih panjang, bukan langsung menyerah.
            suspend fun capturePass(pass: Int): Int {
                var got = 0
                for (tile in 0 until tiles) {
                    val offset = tile * step
                    if (offset >= cssH) break
                    eval("window.__mangalensCanvasScrollTo&&window.__mangalensCanvasScrollTo(${quote(key)},${offset})")
                    // Tunggu compositor menggambar ulang setelah scroll, lalu ukur lagi:
                    // tanpa ini tangkapan bisa berisi halaman sebelumnya (duplikat).
                    delay(SETTLE_MS + if (pass > 0) SETTLE_MS else 0L)
                    var info = json(eval("window.__mangalensCanvasRectNow&&window.__mangalensCanvasRectNow(${quote(key)})"))
                    if (info == null || !info.optBoolean("ok") || info.optDouble("sliceHeight", 0.0) < 8.0) {
                        delay(SETTLE_MS * 2)
                        info = json(
                            eval("window.__mangalensCanvasRectNow&&window.__mangalensCanvasRectNow(${quote(key)})")
                        )
                    }
                    if (info == null || !info.optBoolean("ok")) continue

                    val sliceTop = info.optDouble("sliceTop", 0.0)
                    val sliceH = info.optDouble("sliceHeight", 0.0)
                    val left = info.optDouble("left", 0.0)
                    val width = info.optDouble("width", 0.0)
                    if (sliceH < 8 || width < 8) continue

                    var srcLeft = location[0] + (left * dpr).roundToInt() - decorLocation[0]
                    var srcTop = location[1] + (sliceTop * dpr).roundToInt() - decorLocation[1]
                    var srcW = (width * dpr).roundToInt()
                    var srcH = (sliceH * dpr).roundToInt()
                    if (srcW <= 0 || srcH <= 0) continue

                    // Klip ke batas jendela; sisa yang terpotong digeser di destRect.
                    var clipX = 0
                    var clipY = 0
                    if (srcLeft < 0) {
                        clipX = -srcLeft
                        srcW -= clipX
                        srcLeft = 0
                    }
                    if (srcTop < 0) {
                        clipY = -srcTop
                        srcH -= clipY
                        srcTop = 0
                    }
                    if (srcLeft + srcW > windowW) srcW = windowW - srcLeft
                    if (srcTop + srcH > windowH) srcH = windowH - srcTop
                    if (srcW < 8 || srcH < 8) {
                        Log.w(TAG, "potongan $tile di luar jendela (w=$srcW h=$srcH)")
                        continue
                    }

                    val tileBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
                    val ok = pixelCopy(activity, Rect(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH), tileBitmap)
                    if (!ok) {
                        tileBitmap.recycle()
                        Log.w(TAG, "PixelCopy gagal pada potongan $tile")
                        continue
                    }

                    val destRect = Rect(
                        (left * dpr * scale).roundToInt() + (clipX * scale).roundToInt(),
                        (offset * dpr * scale).roundToInt() + (clipY * scale).roundToInt(),
                        (left * dpr * scale).roundToInt() + ((clipX + srcW) * scale).roundToInt(),
                        (offset * dpr * scale).roundToInt() + ((clipY + srcH) * scale).roundToInt()
                    )
                    Canvas(output).drawBitmap(tileBitmap, null, destRect, null)
                    tileBitmap.recycle()
                    got++
                }
                return got
            }

            capturedTiles = capturePass(0)
            if (capturedTiles == 0) {
                Log.w(TAG, "percobaan pertama kosong, mengulang setelah jeda")
                delay(SETTLE_MS * 2)
                capturedTiles = capturePass(1)
            }
        } finally {
            // Selalu pulihkan posisi baca pengguna dan tampilkan overlay lagi.
            eval("window.__mangalensScrollRestore&&window.__mangalensScrollRestore(${originalScroll.toInt()})")
            eval("window.__mangalensSetOverlayHidden&&window.__mangalensSetOverlayHidden(false)")
        }

        if (capturedTiles == 0) {
            output.recycle()
            Log.w(TAG, "tidak ada potongan canvas yang berhasil ditangkap")
            return null
        }
        if (!looksLikeComic(output)) {
            output.recycle()
            Log.w(TAG, "hasil tangkapan kosong/buram, OCR dibatalkan")
            return null
        }
        val contentHash = sampleHash(output)
        if (!acceptContent(contentHash)) {
            output.recycle()
            Log.w(TAG, "isi canvas sama dengan tangkapan lain, dilewati (duplikat)")
            return null
        }

        val width = output.width
        val height = output.height
        val bytes = withContext(Dispatchers.Default) { compress(output) }
        output.recycle()
        if (bytes == null || bytes.isEmpty()) return null
        Log.d(TAG, "canvas pixel ${width}x$height ${bytes.size / 1024} KB, $capturedTiles potongan")
        return CanvasCapture(bytes, width, height)
    }

    private fun captureScale(pixelW: Double, pixelH: Double): Double {
        if (pixelW <= 0 || pixelH <= 0) return 1.0
        var scale = 1.0
        val pixels = pixelW * pixelH
        if (pixels > MAX_CAPTURE_PIXELS) scale = sqrt(MAX_CAPTURE_PIXELS / pixels)
        if (pixelW * scale > MAX_CAPTURE_WIDTH) scale *= MAX_CAPTURE_WIDTH / (pixelW * scale)
        return scale.coerceIn(0.05, 1.0)
    }

    /**
     * Cek cepat kualitas: gambar yang seluruhnya putih/hitam (PixelCopy gagal
     * atau canvas belum digambar) tidak layak dikirim ke OCR.
     */
    private fun looksLikeComic(bitmap: Bitmap): Boolean {
        val sampleW = min(64, bitmap.width)
        val sampleH = min(64, bitmap.height)
        if (sampleW < 4 || sampleH < 4) return false
        val pixels = IntArray(sampleW * sampleH)
        val stepX = max(1, bitmap.width / sampleW)
        val stepY = max(1, bitmap.height / sampleH)
        var index = 0
        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                pixels[index++] = bitmap.getPixel(min(bitmap.width - 1, x * stepX), min(bitmap.height - 1, y * stepY))
            }
        }
        var dark = 0
        var light = 0
        var sum = 0L
        var sumSq = 0.0
        for (pixel in pixels) {
            val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            sum += luma
            sumSq += (luma * luma).toDouble()
            if (luma < 80) dark++
            if (luma > 200) light++
        }
        val total = pixels.size.toDouble()
        val mean = sum / total
        val variance = sumSq / total - mean * mean
        val hasInk = dark / total > 0.005
        val hasPaper = light / total > 0.05
        return hasInk && hasPaper && variance > 150
    }

    /** Hash isi gambar (sampling) untuk mendeteksi tangkapan duplikat. */
    private fun sampleHash(bitmap: Bitmap): Int {
        var hash = 0x811c9dc5.toInt()
        val stepX = max(1, bitmap.width / 24)
        val stepY = max(1, bitmap.height / 24)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                hash = (hash xor bitmap.getPixel(x, y)) * 16777619
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    private fun compress(bitmap: Bitmap): ByteArray? {
        return runCatching {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            stream.toByteArray()
        }.getOrNull()
    }

    private suspend fun pixelCopy(activity: Activity, src: Rect, dest: Bitmap): Boolean {
        val window = activity.window ?: return false
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                PixelCopy.request(
                    window,
                    src,
                    dest,
                    { result ->
                        if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                    },
                    Handler(Looper.getMainLooper())
                )
            }.onFailure {
                Log.w(TAG, "PixelCopy tidak tersedia: ${it.javaClass.simpleName}")
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private fun json(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val unquoted = JSONTokener(raw).nextValue() as? String ?: raw
            JSONObject(unquoted)
        }.getOrNull()
    }

    private fun quote(value: String): String = JSONObject.quote(value)
}
