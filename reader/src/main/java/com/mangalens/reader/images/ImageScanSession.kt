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
    private val pageContext: () -> PageContext
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
            val outcome = fetcher.fetch(image.url, pageContext())
            if (startedGeneration != generation) return

            when (outcome) {
                is FetchOutcome.Success -> {
                    val file = store.write(image.key, outcome.bytes, outcome.contentType)
                    if (startedGeneration == generation) {
                        setState(
                            image.key,
                            ImageDownloadState.Ready(file, outcome.bytes.size.toLong(), outcome.contentType)
                        )
                    }
                }

                is FetchOutcome.Failure -> {
                    // Alasan kegagalan dicatat (tanpa URL lengkap/isi gambar) agar
                    // halaman yang tidak diterjemahkan bisa didiagnosis dari Logcat.
                    android.util.Log.w("MangaLensScan", "unduh gagal: ${outcome.reason}")
                    setState(
                        image.key,
                        ImageDownloadState.Failed(outcome.reason)
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

        /** Batas jumlah gambar per halaman agar daftar tidak tumbuh tak terbatas. */
        private const val MAX_IMAGES = 200
    }
}
