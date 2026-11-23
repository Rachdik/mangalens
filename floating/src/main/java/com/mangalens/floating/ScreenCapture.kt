package com.mangalens.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager

/**
 * Mengambil satu frame layar lewat MediaProjection. Dipanggil dari thread IO.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection
) {

    fun capture(): Bitmap {
        val width = screenWidth()
        val height = screenHeight()
        val densityDpi = context.resources.displayMetrics.densityDpi

        val thread = HandlerThread("mangalens-capture").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var display: android.hardware.display.VirtualDisplay? = null

        try {
            display = projection.createVirtualDisplay(
                "mangalens-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            // Beri waktu compositor menggambar frame pertama.
            Thread.sleep(FIRST_FRAME_DELAY_MS)

            val image = acquireImage(reader) ?: error("Layar tidak menghasilkan gambar")
            val raw = try {
                imageToBitmap(image, width, height)
            } finally {
                image.close()
            }

            val longest = maxOf(raw.width, raw.height)
            if (longest <= MAX_OCR_DIMENSION) return raw

            val scale = MAX_OCR_DIMENSION.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt(),
                (raw.height * scale).toInt(),
                true
            )
            if (scaled !== raw) raw.recycle()
            return scaled
        } finally {
            runCatching { display?.release() }
            runCatching { reader.close() }
            runCatching { thread.quitSafely() }
        }
    }

    private fun acquireImage(reader: ImageReader): Image? {
        val deadline = SystemClock.uptimeMillis() + CAPTURE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) return image
            Thread.sleep(40)
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

    private fun screenWidth(): Int = screenSize().first

    private fun screenHeight(): Int = screenSize().second

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

    private companion object {
        const val FIRST_FRAME_DELAY_MS = 250L
        const val CAPTURE_TIMEOUT_MS = 4_000L
        const val MAX_OCR_DIMENSION = 1600
    }
}
