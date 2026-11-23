package com.mangalens.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mangalens.reader.images.DetectedImage
import com.mangalens.reader.images.ImageDownloadState
import com.mangalens.reader.images.ImageScanSession
import com.mangalens.reader.images.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Mengelola penerjemahan otomatis gambar yang dideteksi oleh ImageScanSession.
 * Saat gambar siap (terunduh dan tersedia di cache), pipeline akan otomatis
 * memproses gambar tersebut untuk terjemahan.
 */
class ImageTranslationManager(
    private val context: Context,
    private val scanSession: ImageScanSession,
    private val pipeline: ReadTranslatePipeline,
    private val scope: CoroutineScope
) {
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val processedImages = mutableSetOf<String>()
    private var job: Job? = null

    /** Mulai monitor otomatis untuk gambar yang siap diterjemahkan. */
    fun start() {
        stop()
        job = scope.launch {
            monitorImageStates()
        }
    }

    /** Hentikan monitor otomatis. */
    fun stop() {
        job?.cancel()
        job = null
        _isProcessing.value = false
    }

    /**
     * Reset daftar gambar yang sudah diproses.
     * Dipanggil saat halaman berubah agar gambar di halaman baru
     * bisa diproses meskipun URL-nya sama dengan gambar di halaman lama.
     */
    fun reset() {
        processedImages.clear()
    }

    /** Proses gambar tertentu secara manual (mis. saat user menekan bubble). */
    fun processImage(image: DetectedImage) {
        val states = scanSession.states.value
        val state = states[image.key]
        if (state is ImageDownloadState.Ready) {
            scope.launch { processImageFile(state.file, image) }
        }
    }

    private suspend fun monitorImageStates() {
        while (true) {
            val states = scanSession.states.value
            val images = scanSession.images.value

            for (image in images) {
                if (processedImages.contains(image.key)) continue

                val state = states[image.key]
                if (state is ImageDownloadState.Ready) {
                    _isProcessing.value = true
                    try {
                        processImageFile(state.file, image)
                        processedImages.add(image.key)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        _isProcessing.value = false
                    }
                }
            }

            kotlinx.coroutines.delay(2000)
        }
    }

    private suspend fun processImageFile(file: File, image: DetectedImage) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: return

        try {
            pipeline.processImage(bitmap, "auto", "id")
        } finally {
            if (bitmap.isMutable) {
                bitmap.recycle()
            }
        }
    }
}
