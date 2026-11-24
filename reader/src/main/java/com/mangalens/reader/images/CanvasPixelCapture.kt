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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object CanvasPixelCapture {

    private const val TAG = "MangaLensCanvas"

    private const val MAX_CAPTURE_PIXELS = 12_000_000.0
    private const val MAX_CAPTURE_WIDTH = 1600.0
    private const val TILE_OVERLAP_CSS = 24.0
    private const val MAX_TILES = 12

    private const val TILE_RECT_TIMEOUT_MS = 700L
    private const val TILE_RECT_STABILIZE_POLL_MS = 110L
    private const val TILE_STABLE_COUNT = 2
    private const val TILE_RETRY_MAX = 2
    private const val TILE_RETRY_BASE_JITTER_MS = 100L

    /** Metadata tambahan untuk diagnostik sumber gambar. */
    data class SourceInfo(
        val sourceType: String, // "native", "pixelcopy", "hybrid"
        val nativeUrl: String? = null,
        val nativeWidth: Int? = null,
        val nativeHeight: Int? = null,
        val canvasBackingW: Int? = null,
        val canvasBackingH: Int? = null,
        val pixelCopyUsed: Boolean = false,
        val canvasTainted: Boolean = false
    )

    data class CaptureDiagnostics(
        val key: String,
        val outW: Int,
        val outH: Int,
        val tilesAttempted: Int,
        val tilesOk: Int,
        val tilesNeeded: Int,
        val tilesEmpty: Int,
        val tilesTimeout: Int,
        val tilesPixelCopyFail: Int,
        val tilesQualityFail: Int,
        val qualityRejected: Boolean,
        val duplicateRejected: Boolean,
        val partialCapture: Boolean,
        // Metadata geometri untuk diagnostik multi-tile
        val canvasBackingWidth: Int = 0,
        val canvasBackingHeight: Int = 0,
        val canvasCssWidth: Int = 0,
        val canvasCssHeight: Int = 0,
        val devicePixelRatio: Double = 1.0,
        val viewportWidth: Int = 0,
        val viewportHeight: Int = 0,
        val canvasRectTop: Double = 0.0,
        val canvasRectBottom: Double = 0.0,
        val canvasRectHeight: Double = 0.0,
        val scrollY: Double = 0.0,
        val tileStep: Double = 0.0,
        val tileOverlap: Double = TILE_OVERLAP_CSS,
        val sourceInfo: String? = null, // JSON serialized source info
        /** Alasan per tile yang gagal; kosong bila semua tile berhasil. */
        val failureReasons: List<String> = emptyList()
    )

    suspend fun capture(
        activity: Activity,
        webView: WebView,
        key: String,
        eval: suspend (String) -> String?,
        acceptContent: (Int) -> Boolean = { true }
    ): CanvasCapture? = withContext(Dispatchers.Main) {
        val result = captureOnMain(activity, webView, key, eval, acceptContent)

        // Minta info sumber asli dari JavaScript untuk diagnostik (tidak mempengaruhi hasil)
        runCatching {
            val sourceInfo = fetchSourceInfo(activity, webView, key, eval)
            if (sourceInfo != null) {
                Log.d(TAG, "sourceInfo: type=${sourceInfo.sourceType} " +
                    "native=${sourceInfo.nativeUrl != null} " +
                    "tainted=${sourceInfo.canvasTainted} " +
                    "pcUsed=${sourceInfo.pixelCopyUsed}")
            }
        }

        result
    }

    /**
     * Ambil informasi sumber gambar dari JavaScript.
     * Mencoba untuk mengetahui apakah canvas berasal dari gambar asli yang bisa
     * diakses lewat URL, atau hanya hasil render piksel.
     */
    private suspend fun fetchSourceInfo(
        activity: Activity,
        webView: WebView,
        key: String,
        eval: suspend (String) -> String?
    ): SourceInfo? {
        val script = "(function(){try{return window.__mangalensCanvasSourceInfo" +
            "?window.__mangalensCanvasSourceInfo(${quote(key)})" +
            ":JSON.stringify({sourceType:'unknown'});}" +
            "catch(e){return JSON.stringify({sourceType:'error',error:String(e)});}})()"

        val raw = eval(script)
        if (raw.isNullOrBlank() || raw == "null") return null
        val unquoted = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
        val json = runCatching { JSONObject(unquoted) }.getOrNull() ?: return null

        val sourceType = json.optString("sourceType", "unknown")
        val nativeUrl = json.optString("nativeUrl", null)
        val nativeWidth = json.optInt("nativeWidth", -1).takeIf { it > 0 }
        val nativeHeight = json.optInt("nativeHeight", -1).takeIf { it > 0 }
        val canvasBackingW = json.optInt("canvasBackingW", -1).takeIf { it > 0 }
        val canvasBackingH = json.optInt("canvasBackingH", -1).takeIf { it > 0 }
        val pixelCopyUsed = json.optBoolean("pixelCopyUsed", false)
        val canvasTainted = json.optBoolean("canvasTainted", false)

        return SourceInfo(
            sourceType = sourceType,
            nativeUrl = nativeUrl,
            nativeWidth = nativeWidth,
            nativeHeight = nativeHeight,
            canvasBackingW = canvasBackingW,
            canvasBackingH = canvasBackingH,
            pixelCopyUsed = pixelCopyUsed,
            canvasTainted = canvasTainted
        )
    }

    private suspend fun captureOnMain(
        activity: Activity,
        webView: WebView,
        key: String,
        eval: suspend (String) -> String?,
        acceptContent: (Int) -> Boolean
    ): CanvasCapture? {
        val prep = fetchPrepWithRetry(activity, webView, key, eval)
        if (prep == null || !prep.optBoolean("ok")) {
            Log.w(TAG, "persiapan gagal: ${prep?.optString("error") ?: "null/batal"}")
            return null
        }

        val cssW = prep.optDouble("cssW", 0.0)
        val cssH = prep.optDouble("cssH", 0.0)
        val bw = prep.optDouble("bw", cssW)   // backing store width (piksel asli canvas)
        val bh = prep.optDouble("bh", cssH)   // backing store height (piksel asli canvas)
        val vh = prep.optDouble("vh", 0.0)
        val dpr = prep.optDouble("dpr", 1.0).coerceIn(0.5, 4.0)
        val originalScroll = prep.optDouble("scrollY", 0.0)

        if (cssW < 50 || cssH < 50 || vh < 50 || bw < 50 || bh < 50) {
            Log.w(TAG, "ukuran terlalu kecil cssW=$cssW cssH=$cssH bw=$bw bh=$bh vh=$vh")
            return null
        }

        // backing store berisi seluruh konten canvas asli (bw × bh piksel).
        // CSS size (cssW × cssH) adalah ukuran render di viewport.
        // Jika cssH <= vh, seluruh canvas kasual terlihat dalam satu viewport
        // dan bisa ditangkap dalam satu tile (meski resolusi backing store
        // lebih tinggi dari resolusi capture karena CSS scaling).
        // Jika cssH > vh, perlu multi-tile untuk menutupi seluruh canvas.
        val scale = captureScale(cssW * dpr, cssH * dpr)
        val outW = max(1, (cssW * dpr * scale).roundToInt())
        val outH = max(1, (cssH * dpr * scale).roundToInt())

        val step = max(1.0, vh - TILE_OVERLAP_CSS)
        val tilesNeeded = ceil(cssH / step).toInt()
        val tiles = min(MAX_TILES, tilesNeeded)

        // Offset tiap tile. Tile terakhir disejajarkan dengan dasar canvas agar
        // tidak tersisa strip tipis di ujung (strip seperti itu sering ditolak
        // pemeriksaan kualitas dan meninggalkan pita putih).
        val tileOffsets = DoubleArray(tiles) { it * step }
        if (tiles > 0 && tilesNeeded <= MAX_TILES) {
            val bottomAligned = max(0.0, cssH - vh)
            if (bottomAligned > 0.0) tileOffsets[tiles - 1] = bottomAligned
        }
        // Nilai awal: dibatasi MAX_TILES. Setelah loop nilai ini dihitung ulang
        // dari tile yang benar-benar berhasil, sehingga tile gagal tidak pernah
        // dilaporkan sebagai hasil utuh.
        var partialCapture = tilesNeeded > MAX_TILES
        val failureReasons = mutableListOf<String>()

        var tilesAttempted = 0
        var tilesOk = 0
        var tilesEmpty = 0
        var tilesTimeout = 0
        var tilesPixelCopyFail = 0
        var tilesQualityFail = 0

        val location = IntArray(2)
        webView.getLocationOnScreen(location)
        val decorLocation = IntArray(2)
        activity.window.decorView.getLocationOnScreen(decorLocation)
        val windowW = activity.window.decorView.width
        val windowH = activity.window.decorView.height

        Log.d(
            TAG,
            "capture: cssW=$cssW cssH=$cssH bw=$bw bh=$bh vh=$vh dpr=$dpr " +
                "locWebScr=(${location[0]},${location[1]}) decorScr=(${decorLocation[0]},${decorLocation[1]}) " +
                "winW=$windowW winH=$windowH tiles=$tiles/need=$tilesNeeded scale=$scale " +
                "out=${outW}x$outH origScroll=$originalScroll partial=$partialCapture " +
                "css=${cssW}x$cssH bw=$bw x$bh step=$step"
        )

        lateinit var output: Bitmap
        var hasOutput = false

        try {
            output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            output.eraseColor(Color.WHITE)
            hasOutput = true

            eval("window.__mangalensSetOverlayHidden&&" +
                "window.__mangalensSetOverlayHidden(true)")
            delay(60)

            for (tile in 0 until tiles) {
                val offset = tileOffsets[tile]
                if (offset >= bh) break
                tilesAttempted++

                // Dua tahap terpisah: stabilisasi (maks 3x) lalu capture (maks 2x).
                // Stabilisasi mengulang scroll + baca posisi terbaru; hanya naikkan
                // tilesOk setelah bitmap valid & digambar ke output.
                var rectStableFound = false
                var stableInfoAcc: RectInfo? = null
                var stabilizeAttempts = 0
                var stabilizeLastReason: String = "unknown"
                var stabilizeDetached = false

                while (
                    stabilizeAttempts < 3 &&
                    !rectStableFound &&
                    (coroutineContext[Job]?.isActive ?: false)
                ) {
                    stabilizeAttempts++
                    eval(
                        "window.__mangalensCanvasScrollTo&&" +
                            "window.__mangalensCanvasScrollTo(${quote(key)},${offset})"
                    )
                    val info = waitForRectStable(eval, key, TILE_RECT_TIMEOUT_MS)
                    if (info == null) {
                        stabilizeLastReason = "timeout/stabilisasi"
                        if (!activity.isFinishing && !activity.isDestroyed &&
                            webView.isAttachedToWindow
                        ) {
                            // Halaman mungkin sedang mengganti elemen canvas /
                            // menyesuaikan layout; ulangi dari awal dengan scroll baru.
                            delay(TILE_RECT_STABILIZE_POLL_MS * 2)
                            continue
                        } else {
                            stabilizeDetached = true
                            stabilizeLastReason = "detached"
                            break
                        }
                    }
                    if (info.sliceHeight < 8 || info.width < 8) {
                        stabilizeLastReason = "kosong"
                        delay(TILE_RECT_STABILIZE_POLL_MS)
                        continue
                    }
                    // Stabil: rect sama dalam TILE_STABLE_COUNT polling berturut-turut.
                    rectStableFound = true
                    stableInfoAcc = info
                }

                if (!rectStableFound) {
                    failureReasons += "tile $tile: stabilisasi $stabilizeLastReason"
                    if (stabilizeDetached) {
                        tilesTimeout++
                        Log.w(
                            TAG,
                            "tile $tile: gagal stabilisasi ($stabilizeAttempts/3) " +
                                "alasan=$stabilizeLastReason detached=$stabilizeDetached"
                        )
                    } else {
                        tilesTimeout++
                        Log.w(
                            TAG,
                            "tile $tile: gagal stabilisasi ($stabilizeAttempts/3) " +
                                "alasan=$stabilizeLastReason"
                        )
                    }
                    continue
                }

                // Koordinat sumber dari rect yang sudah dinyatakan stabil.
                // Titik ini hanya tercapai bila rectStableFound == true, jadi
                // stableInfoAcc pasti tidak null.
                val stableInfo = stableInfoAcc!!
                var srcLeft = location[0] + (stableInfo.left * dpr).roundToInt() - decorLocation[0]
                var srcTop = location[1] + (stableInfo.sliceTop * dpr).roundToInt() - decorLocation[1]
                var srcW = (stableInfo.width * dpr).roundToInt()
                var srcH = (stableInfo.sliceHeight * dpr).roundToInt()

                // Koreksi clipping batas Window. Jika srcRect terpotong, destRect
                // ikut digeser secara proporsional agar tile tidak bergeser/lebar/
                // meninggalkan celah.
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
                    tilesEmpty++
                    failureReasons += "tile $tile: di luar jendela"
                    Log.w(TAG, "tile $tile: diluar jendela w=$srcW h=$srcH")
                    continue
                }

                // Offset konten yang benar-benar terlihat (bisa lebih kecil dari
                // tile*step bila scroller sudah mentok di akhir halaman).
                // Memakai offset ini mencegah tile terakhir bergeser/terpotong.
                val contentOffset = stableInfo.contentOffset.coerceAtLeast(0.0)

                // destRect divalidasi terhadap batas outW/outH setelah koreksi.
                val destLeft = (stableInfo.left * dpr * scale).roundToInt() +
                    (clipX * scale).roundToInt()
                val destTop = (contentOffset * dpr * scale).roundToInt() +
                    (clipY * scale).roundToInt()
                var destRight = (stableInfo.left * dpr * scale).roundToInt() +
                    ((clipX + srcW) * scale).roundToInt()
                var destBottom = (contentOffset * dpr * scale).roundToInt() +
                    ((clipY + srcH) * scale).roundToInt()
                if (destLeft < 0) {
                    destRight -= -destLeft
                }
                if (destTop < 0) {
                    destBottom -= -destTop
                }
                if (destRight > outW) destRight = outW
                if (destBottom > outH) destBottom = outH
                if (destRight <= destLeft || destBottom <= destTop) {
                    tilesEmpty++
                    failureReasons += "tile $tile: destRect tidak valid"
                    Log.w(TAG, "tile $tile: destRect LNPA batas outW=$outW outH=$outH")
                    continue
                }
                val destRect = Rect(destLeft, destTop, destRight, destBottom)

                Log.d(
                    TAG,
                    "tile $tile: src srcLeft=$srcLeft srcTop=$srcTop srcW=$srcW srcH=$srcH " +
                        "dest=${destRect.left},${destRect.top},${destRect.right},${destRect.bottom} " +
                        "stabil=$stabilizeAttempts"
                )

                var captureOk = false
                var captureAttempts = 0
                var tileBitmap: Bitmap? = null
                try {
                    while (
                        captureAttempts < TILE_RETRY_MAX &&
                        !captureOk &&
                        (coroutineContext[Job]?.isActive ?: false)
                    ) {
                        captureAttempts++
                        tileBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
                        val pxOk = pixelCopy(
                            activity,
                            Rect(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH),
                            tileBitmap
                        )
                        if (!pxOk) {
                            tilesPixelCopyFail++
                            if (captureAttempts == TILE_RETRY_MAX) {
                                failureReasons += "tile $tile: PixelCopy gagal"
                            }
                            Log.w(
                                TAG,
                                "tile $tile: PixelCopy gagal ($captureAttempts/$TILE_RETRY_MAX)"
                            )
                            tileBitmap.recycle()
                            tileBitmap = null
                            delay(TILE_RETRY_BASE_JITTER_MS + (captureAttempts * 40))
                            continue
                        }
                        if (!looksLikeComicTile(tileBitmap)) {
                            tilesQualityFail++
                            if (captureAttempts == TILE_RETRY_MAX) {
                                failureReasons += "tile $tile: kualitas tile rendah"
                            }
                            Log.w(
                                TAG,
                                "tile $tile: kosong/buram ($captureAttempts/$TILE_RETRY_MAX)"
                            )
                            tileBitmap.recycle()
                            tileBitmap = null
                            delay(TILE_RETRY_BASE_JITTER_MS + (captureAttempts * 40))
                            continue
                        }
                        // Hanya gambar ke output setelah bitmap lolos quality scan.
                        Canvas(output).drawBitmap(tileBitmap, null, destRect, null)
                        captureOk = true
                    }
                } finally {
                    tileBitmap?.recycle()
                }
                if (captureOk) {
                    tilesOk++
                } else {
                    Log.w(TAG, "tile $tile: gagal capture $captureAttempts percobaan")
                }
            }
        } finally {
            eval(
                "window.__mangalensScrollRestore&&" +
                    "window.__mangalensScrollRestore(${originalScroll.toInt()})"
            )
            eval("window.__mangalensSetOverlayHidden&&" +
                "window.__mangalensSetOverlayHidden(false)")
        }

        if (!hasOutput) return null

        // Hasil parsial bila ada tile yang tidak tertangkap, bukan hanya bila
        // jumlah tile dibatasi MAX_TILES.
        partialCapture = tilesNeeded > MAX_TILES ||
            tilesAttempted < tilesNeeded ||
            tilesOk < tilesNeeded
        if (partialCapture) {
            Log.w(
                TAG,
                "partial: ok=$tilesOk/need=$tilesNeeded attempted=$tilesAttempted " +
                    "reasons=${failureReasons.take(4)}"
            )
        }

        if (tilesOk == 0) {
            output.recycle()
            Log.w(TAG, "tidak ada potongan berhasil attempted=$tilesAttempted ok=0 empty=$tilesEmpty timeout=$tilesTimeout pxFail=$tilesPixelCopyFail qfail=$tilesQualityFail partial=$partialCapture")
            return null
        }

        val quality = CanvasQuality.validate(output)
        if (!looksLikeComic(output) || !quality.ok) {
            output.recycle()
            Log.w(
                TAG,
                "hasil ditolak: ${quality.reason ?: "kosong/buram"} " +
                    "(ok=$tilesOk stdDev=${quality.stdDev} ink=${quality.inkFraction})"
            )
            return null
        }

        val contentHash = sampleHash(output)
        val duplicateRejected = !acceptContent(contentHash)
        if (duplicateRejected) {
            output.recycle()
            Log.w(TAG, "duplikat isi canvas hash=$contentHash")
            return null
        }

        val bytes = withContext(Dispatchers.Default) { compress(output) }
        output.recycle()

        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "kompresi gagal")
            return null
        }

        Log.d(
            TAG,
            "canvas pixel ${outW}x$outH ${bytes.size / 1024} KB " +
                "attempted=$tilesAttempted ok=$tilesOk/need=$tilesNeeded " +
                "empty=$tilesEmpty timeout=$tilesTimeout pxFail=$tilesPixelCopyFail " +
                "qfail=$tilesQualityFail dup=$duplicateRejected partial=$partialCapture " +
                "css=${cssW}x$cssH bw=$bw x$bh"
        )

        val sourceInfo = fetchSourceInfo(activity, webView, key, eval)
        val sourceInfoJson = sourceInfo?.let { info ->
            runCatching { JSONObject().apply { 
                put("sourceType", info.sourceType)
                info.nativeUrl?.let { put("nativeUrl", it) }
                info.nativeWidth?.let { put("nativeWidth", it) }
                info.nativeHeight?.let { put("nativeHeight", it) }
                info.canvasBackingW?.let { put("canvasBackingW", it) }
                info.canvasBackingH?.let { put("canvasBackingH", it) }
                put("pixelCopyUsed", info.pixelCopyUsed)
                put("canvasTainted", info.canvasTainted)
            } }.toString()
        }

        return CanvasCapture(
            bytes = bytes,
            width = outW,
            height = outH,
            diagnostics = CaptureDiagnostics(
                key = key,
                outW = outW,
                outH = outH,
                tilesAttempted = tilesAttempted,
                tilesOk = tilesOk,
                tilesNeeded = tilesNeeded,
                tilesEmpty = tilesEmpty,
                tilesTimeout = tilesTimeout,
                tilesPixelCopyFail = tilesPixelCopyFail,
                tilesQualityFail = tilesQualityFail,
                qualityRejected = false,
                duplicateRejected = duplicateRejected,
                partialCapture = partialCapture,
                canvasBackingWidth = bw.roundToInt(),
                canvasBackingHeight = bh.roundToInt(),
                canvasCssWidth = cssW.roundToInt(),
                canvasCssHeight = cssH.roundToInt(),
                devicePixelRatio = dpr,
                viewportWidth = windowW,
                viewportHeight = windowH,
                canvasRectTop = location[1].toDouble(),
                canvasRectBottom = (location[1] + cssH).toDouble(),
                canvasRectHeight = cssH,
                scrollY = originalScroll,
                tileStep = step,
                tileOverlap = TILE_OVERLAP_CSS,
                sourceInfo = sourceInfoJson,
                failureReasons = failureReasons.toList()
            )
        )
    }

    private suspend fun waitForRectStable(
        eval: suspend (String) -> String?,
        key: String,
        timeoutMs: Long
    ): RectInfo? {
        var last: RectInfo? = null
        var stableCount = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (
            (coroutineContext[Job]?.isActive ?: false) &&
            System.currentTimeMillis() < deadline
        ) {
            val cur = readRectNow(eval, key)
            if (cur != null) {
                if (last != null &&
                    last.sliceHeight == cur.sliceHeight &&
                    last.sliceTop == cur.sliceTop &&
                    last.width == cur.width &&
                    last.left == cur.left &&
                    last.contentOffset == cur.contentOffset
                ) {
                    stableCount++
                    if (stableCount >= TILE_STABLE_COUNT) return cur
                } else {
                    stableCount = 0
                }
                last = cur
            }
            delay(TILE_RECT_STABILIZE_POLL_MS)
        }
        // Tidakkan null saja; panggilan yang lebih tinggi membedakan detached
        // dari timeout berdasarkan activity/attached.
        return null
    }

    private suspend fun fetchPrepWithRetry(
        activity: Activity,
        webView: WebView,
        key: String,
        eval: suspend (String) -> String?
    ): JSONObject? {
        // 1 percobaan awal + 2 retry = maksimal 3 kali.
        // Berbeda dengan versi sebelumnya, sini kita mengetahui alasan batal.
        var last: JSONObject? = null
        for (attempt in 1..3) {
            if ((activity.isFinishing || activity.isDestroyed) ||
                !webView.isAttachedToWindow
            ) {
                Log.w(TAG, "prep-direct dibatalkan: activity/attached tidak valid (percobaan $attempt)")
                last = JSONObject().apply { put("ok", false); put("error", "detached") }
                break
            }
            val prep = json(
                eval(
                    "window.__mangalensCanvasPrep&&" +
                        "window.__mangalensCanvasPrep(${quote(key)})"
                )
            )
            if (prep != null && prep.optBoolean("ok")) return prep
            val reason = when {
                prep == null -> "javscript-null"
                !prep.has("ok") -> "missing-ok"
                prep.optBoolean("ok") == false -> {
                    val err = prep.optString("error", "unknown")
                    when {
                        err == "detached" -> "detached"
                        err == "timeout" -> "timeout"
                        else -> "js-error:${err}"
                    }
                }
                else -> "unknown"
            }
            Log.w(TAG, "prep percobaan $attempt gagal: $reason (key=$key)")
            last = prep
            if (attempt < 3 && reason == "detached") {
                // Detached bisa pulih jika halaman baru saja mengganti elemen; singkirkan jeda
                delay(300)
                continue
            }
            // Untuk alasan lain (js-error/timeout), lanjut retry dengan jeda lebih pendek
            if (attempt < 3) {
                delay(200)
            }
        }
        return last
    }

    private data class RectInfo(
        val sliceTop: Double,
        val sliceHeight: Double,
        val left: Double,
        val width: Double,
        val contentOffset: Double
    )

    private suspend fun readRectNow(
        eval: suspend (String) -> String?,
        key: String
    ): RectInfo? {
        val info = json(
            eval(
                "window.__mangalensCanvasRectNow&&" +
                    "window.__mangalensCanvasRectNow(${quote(key)})"
            )
        )
        if (info == null || !info.optBoolean("ok")) return null
        val st = info.optDouble("sliceTop", 0.0)
        val sh = info.optDouble("sliceHeight", 0.0)
        val l = info.optDouble("left", 0.0)
        val w = info.optDouble("width", 0.0)
        val co = info.optDouble("contentOffset", 0.0)
        if (sh < 8 || w < 8) return null
        return RectInfo(st, sh, l, w, co)
    }

    private fun captureScale(pixelW: Double, pixelH: Double): Double {
        if (pixelW <= 0 || pixelH <= 0) return 1.0
        var scale = 1.0
        val pixels = pixelW * pixelH
        if (pixels > MAX_CAPTURE_PIXELS) scale = sqrt(MAX_CAPTURE_PIXELS / pixels)
        if (pixelW * scale > MAX_CAPTURE_WIDTH) scale *= MAX_CAPTURE_WIDTH / (pixelW * scale)
        return scale.coerceIn(0.05, 1.0)
    }

    private fun looksLikeComic(bitmap: Bitmap): Boolean = looksLikeComicTile(bitmap)

    private fun looksLikeComicTile(bitmap: Bitmap): Boolean {
        val sampleW = min(48, bitmap.width)
        val sampleH = min(48, bitmap.height)
        if (sampleW < 4 || sampleH < 4) return false
        val pixels = IntArray(sampleW * sampleH)
        val stepX = max(1, bitmap.width / sampleW)
        val stepY = max(1, bitmap.height / sampleH)
        var idx = 0
        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                pixels[idx++] = bitmap.getPixel(
                    min(bitmap.width - 1, x * stepX),
                    min(bitmap.height - 1, y * stepY)
                )
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

    private fun compress(bitmap: Bitmap): ByteArray? = runCatching {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        stream.toByteArray()
    }.getOrNull()

    private suspend fun pixelCopy(
        activity: Activity,
        src: Rect,
        dest: Bitmap
    ): Boolean {
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

    private fun quote(value: String) = JSONObject.quote(value)
}
