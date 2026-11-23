package com.mangalens.reader

import android.content.Context
import com.mangalens.reader.images.DetectedImage
import com.mangalens.reader.images.ImageDownloadState
import com.mangalens.reader.images.ImageScanSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Mengelola penerjemahan otomatis gambar yang dideteksi oleh ImageScanSession.
 *
 * Penting: kelas ini TIDAK memuat bitmap sendiri. Bitmap dimuat dan di-recycle
 * oleh [ReadTranslatePipeline.processImageFile]. Versi sebelumnya memuat bitmap
 * lalu memanggil `pipeline.processImage(bitmap)` (yang langsung kembali karena
 * hanya meluncurkan coroutine) dan me-recycle bitmap di blok finally — sehingga
 * bitmap bisa dipakai setelah di-recycle oleh coroutine pipeline.
 */
class ImageTranslationManager(
    @Suppress("unused") private val context: Context,
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
        job = scope.launch { monitorImageStates() }
    }

    /** Hentikan monitor otomatis. */
    fun stop() {
        job?.cancel()
        job = null
        _isProcessing.value = false
    }

    /**
     * Reset daftar gambar yang sudah diproses, dipanggil saat halaman berubah
     * agar gambar di halaman baru tetap bisa diproses.
     */
    fun reset() {
        processedImages.clear()
    }

    /** Proses satu gambar secara manual (mis. saat pengguna menekan bubble). */
    fun processImage(image: DetectedImage) {
        val state = scanSession.states.value[image.key]
        if (state is ImageDownloadState.Ready) {
            scope.launch { pipeline.processImageFile(state.file, "auto", "id")?.join() }
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
                        // Tunggu sampai gambar ini benar-benar selesai: memanggil
                        // processImageFile lagi akan membatalkan job yang berjalan,
                        // sehingga gambar sebelumnya tidak pernah selesai.
                        val job = pipeline.processImageFile(state.file, "auto", "id")
                        if (job == null) continue
                        job.join()
                        if (job.isCancelled) continue
                        processedImages.add(image.key)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        // Satu gambar gagal tidak boleh menghentikan monitoring.
                        processedImages.add(image.key)
                    } finally {
                        _isProcessing.value = false
                    }
                }
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2000L
    }
}
