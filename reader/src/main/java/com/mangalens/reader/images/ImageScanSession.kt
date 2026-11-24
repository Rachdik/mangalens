package com.mangalens.reader.images

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Menampung hasil pemindaian DOM, mengurutkan sesuai posisi dokumen, dan
 * mengunduh byte gambar dengan konkurensi terbatas. Gambar yang sudah ada di
 * cache tidak diunduh ulang.
 */
class ImageScanSession(
    private val scope: CoroutineScope,
    private val fetcher: NativeImageFetcher,
    private val store: ImageStore,
    private val pageContext: () -> PageContext,
    /**
     * Diperlukan hanya untuk sumber `<canvas>`: isinya tidak bisa diunduh lewat
     * HTTP, jadi harus diambil dari WebView. Null berarti canvas dilewati.
     */
    private val canvasExtractor: CanvasExtractor? = null
) {

    private val _images = MutableStateFlow<List<DetectedImage>>(emptyList())
    val images: StateFlow<List<DetectedImage>> = _images.asStateFlow()

    private val _states = MutableStateFlow<Map<String, ImageDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, ImageDownloadState>> = _states.asStateFlow()

    private val queue = Channel<DetectedImage>(QUEUE_CAPACITY)
    private val seen: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Naik setiap [reset]. Unduhan yang dimulai pada halaman lama tidak boleh
     * menulis state ke halaman baru, dan antrean lama dibuang agar pekerjaan
     * tidak menumpuk saat pengguna scroll cepat atau pindah chapter.
     */
    @Volatile
    private var generation = 0

    init {
        repeat(MAX_CONCURRENT) {
            scope.launch {
                for (image in queue) {
                    download(image)
                }
            }
        }
    }

    /** Dipanggil dari thread JavascriptInterface, jadi harus aman lintas thread. */
    fun onCandidates(candidates: List<DetectedImage>) {
        if (candidates.isEmpty()) return

        val fresh = synchronized(seen) {
            if (_images.value.size >= MAX_IMAGES) emptyList()
            else candidates.filter { seen.add(it.key) }
        }
        if (fresh.isEmpty()) return

        _images.update { current ->
            (current + fresh).sortedWith(compareBy({ it.docOrder }, { it.sequence }))
        }
        _states.update { current ->
            current + fresh.associate { it.key to ImageDownloadState.Pending as ImageDownloadState }
        }
        fresh.forEach { image ->
            if (inFlight.add(image.key)) {
                val sent = queue.trySend(image)
                if (sent.isFailure) inFlight.remove(image.key)
            }
        }
    }

    fun reset() {
        synchronized(seen) { seen.clear() }
        generation++
        // Buang antrean halaman lama; worker akan lanjut dengan antrean kosong.
        while (queue.tryReceive().isSuccess) Unit
        inFlight.clear()
        _images.value = emptyList()
        _states.value = emptyMap()
    }

    private suspend fun download(image: DetectedImage) {
        val startedGeneration = generation
        try {
            val cached = store.find(image.key)
            if (cached != null) {
                if (startedGeneration == generation) {
                    setState(image.key, ImageDownloadState.Ready(cached, cached.length(), null))
                }
                return
            }

            setState(image.key, ImageDownloadState.Downloading)

            if (image.isCanvas) {
                // Jalur canvas: ekstraksi dari WebView, lalu simpan byte seperti
                // gambar biasa agar pipeline OCR/inpainting/overlay tetap sama.
                var capture = canvasExtractor?.extract(image.key)
                if (startedGeneration != generation) return
                if (capture == null) {
                    // Satu kegagalan biasanya berarti WebView sedang menggulir/menggambar
                    // (mis. setelah fling). Beri jeda lalu coba sekali lagi sebelum
                    // menyerah, supaya halaman itu tidak kehilangan terjemahan.
                    android.util.Log.w("MangaLensScan", "canvas gagal diekstrak, mencoba ulang")
                    kotlinx.coroutines.delay(CANVAS_RETRY_DELAY_MS)
                    if (startedGeneration != generation) return
                    capture = canvasExtractor?.extract(image.key)
                    if (startedGeneration != generation) return
                }
                if (capture == null) {
                    android.util.Log.w("MangaLensScan", "canvas gagal diekstrak")
                    setState(image.key, ImageDownloadState.Failed("canvas tidak bisa diekstrak"))
                    return
                }
                val file = store.write(image.key, capture.bytes, "image/webp")
                if (startedGeneration == generation) {
                    android.util.Log.d(
                        "MangaLensScan",
                        "canvas siap ${capture.width}x${capture.height} " +
                            "${capture.bytes.size / 1024} KB"
                    )
                    setState(
                        image.key,
                        ImageDownloadState.Ready(file, capture.bytes.size.toLong(), "image/webp")
                    )
                }
                return
            }

            val outcome = fetcher.fetch(image.url, pageContext())
            if (startedGeneration != generation) return

            when {
                outcome.isSuccess -> {
                    val bytes = outcome.bytesOrNull ?: return
                    val file = store.write(image.key, bytes, outcome.contentType)
                    if (startedGeneration == generation) {
                        setState(
                            image.key,
                            ImageDownloadState.Ready(file, bytes.size.toLong(), outcome.contentType)
                        )
                    }
                }

                !outcome.isSuccess -> {
                    // Alasan kegagalan dicatat (tanpa URL lengkap/isi gambar) agar
                    // halaman yang tidak diterjemahkan bisa didiagnosis dari Logcat.
                    val reason = outcome.reasonOrNull ?: "Tidak diketahui"
                    android.util.Log.w("MangaLensScan", "unduh gagal: $reason")
                    setState(
                        image.key,
                        ImageDownloadState.Failed(reason)
                    )
                }
            }
        } finally {
            inFlight.remove(image.key)
        }
    }

    private fun setState(key: String, state: ImageDownloadState) {
        _states.update { it + (key to state) }
    }

    companion object {
        private const val MAX_CONCURRENT = 2

        /** Batas antrean agar scroll cepat tidak menumpuk pekerjaan tanpa batas. */
        private const val QUEUE_CAPACITY = 32

        /** Jeda sebelum ekstraksi canvas diulang setelah percobaan pertama gagal. */
        private const val CANVAS_RETRY_DELAY_MS = 1_500L

        /** Batas jumlah gambar per halaman agar daftar tidak tumbuh tak terbatas. */
        private const val MAX_IMAGES = 200
    }
}
