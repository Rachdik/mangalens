package com.mangalens.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

/** Izin rekam layar tidak bisa dipakai lagi; pengguna harus menyetujui ulang. */
class ProjectionUnavailableException(message: String) : IllegalStateException(message)

/**
 * Satu frame hasil tangkapan layar. [scale] = lebar bitmap / lebar layar,
 * dipakai untuk memetakan koordinat balon hasil OCR kembali ke koordinat layar.
 */
class CaptureFrame(
    val bitmap: Bitmap,
    val scale: Float
)

/**
 * Sesi tangkapan layar yang bertahan lama.
 *
 * Penting: sejak Android 14 `createVirtualDisplay()` hanya boleh dipanggil
 * SEKALI untuk satu [MediaProjection]. Versi lama membuat VirtualDisplay baru
 * pada setiap ketukan bubble, sehingga ketukan kedua Melempar SecurityException
 * dan bubble tampak mati. Di sini VirtualDisplay + ImageReader dibuat sekali
 * lalu dipakai ulang untuk semua tangkapan.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection
) {

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var screenWidth = 0
    private var screenHeight = 0

    /** Skala frame terakhir (bitmap / layar), dipakai saat menempel terjemahan. */
    @Volatile
    var lastScale: Float = 1f
        private set

    @Synchronized
    fun start() {
        if (display != null) return

        val (width, height) = screenSize()
        screenWidth = width
        screenHeight = height

        val captureThread = HandlerThread("mangalens-capture").apply { start() }
        val handler = Handler(captureThread.looper)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        val virtualDisplay = try {
            projection.createVirtualDisplay(
                "mangalens-capture",
                width,
                height,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )
        } catch (t: Throwable) {
            runCatching { imageReader.close() }
            runCatching { captureThread.quitSafely() }
            throw ProjectionUnavailableException(
                "Izin rekam layar tidak bisa dipakai lagi. Ketuk bubble untuk menyetujui ulang."
            )
        }

        reader = imageReader
        display = virtualDisplay
        thread = captureThread

        // Beri waktu compositor menggambar frame pertama.
        Thread.sleep(FIRST_FRAME_DELAY_MS)
    }

    /** @throws ProjectionUnavailableException bila izin rekam sudah dicabut. */
    fun capture(): CaptureFrame {
        val imageReader = synchronized(this) { reader }
            ?: throw ProjectionUnavailableException(
                "Izin rekam layar tidak aktif. Ketuk bubble untuk menyetujui ulang."
            )

        // Buang frame lama supaya overlay MangaLens yang baru disembunyikan
        // tidak ikut terekam.
        drain(imageReader)
        Thread.sleep(FRESH_FRAME_DELAY_MS)

        val image = acquireImage(imageReader)
            ?: throw ProjectionUnavailableException(
                "Layar tidak menghasilkan gambar. Coba ketuk bubble sekali lagi."
            )

        val raw = try {
            imageToBitmap(image, screenWidth, screenHeight)
        } finally {
            image.close()
        }

        val longest = max(raw.width, raw.height)
        val frame = if (longest <= MAX_OCR_DIMENSION) {
            CaptureFrame(raw, raw.width.toFloat() / screenWidth)
        } else {
            val scale = MAX_OCR_DIMENSION.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== raw) raw.recycle()
            CaptureFrame(scaled, scaled.width.toFloat() / screenWidth)
        }

        // Frame hitam = compositor belum menggambar atau izin dicabut sebagian.
        // Lebih baik dilaporkan jelas daripada OCR 0 blok tanpa alasan.
        if (frame.bitmap.looksBlack()) {
            frame.bitmap.recycle()
            throw ProjectionUnavailableException(
                "Layar terekam kosong (hitam). Ketuk bubble sekali lagi; bila berulang, " +
                    "matikan lalu nyalakan ulang bubble."
            )
        }

        lastScale = frame.scale
        return frame
    }

    @Synchronized
    fun close() {
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { thread?.quitSafely() }
        thread = null
    }

    private fun drain(imageReader: ImageReader) {
        while (true) {
            val image = imageReader.acquireLatestImage() ?: break
            runCatching { image.close() }
        }
    }

    private fun acquireImage(imageReader: ImageReader): Image? {
        val deadline = SystemClock.uptimeMillis() + CAPTURE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val image = imageReader.acquireLatestImage()
            if (image != null) return image
            Thread.sleep(30)
        }
        return null
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)

        if (rowPadding == 0) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching {
                context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * True bila frame hampir seluruhnya hitam. Halaman manga yang benar selalu
     * punya kontras (garis/teks), jadi ini menandakan tangkapan gagal.
     */
    private fun Bitmap.looksBlack(): Boolean {
        val stepX = max(1, width / GRID)
        val stepY = max(1, height / GRID)
        var minLuma = 255
        var maxLuma = 0
        var total = 0L
        var samples = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val color = getPixel(x, y)
                val luma = ((color shr 16 and 0xFF) * 299 +
                    (color shr 8 and 0xFF) * 587 +
                    (color and 0xFF) * 114) / 1000
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma
                total += luma
                samples++
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return true
        val average = total / samples
        return average < 10 && abs(maxLuma - minLuma) < 8
    }

    private companion object {
        const val FIRST_FRAME_DELAY_MS = 200L
        const val FRESH_FRAME_DELAY_MS = 60L
        const val CAPTURE_TIMEOUT_MS = 4_000L
        // Jangan memperkecil layar ponsel: teks balon manga kecil, dan
        // pengecilan ke 1600 px membuat banyak huruf salah terbaca.
        const val MAX_OCR_DIMENSION = 2400
        const val GRID = 16
    }
}
