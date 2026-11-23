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

    private val queue = Channel<DetectedImage>(Channel.UNLIMITED)
    private val seen: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

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
            candidates.filter { seen.add(it.key) }
        }
        if (fresh.isEmpty()) return

        _images.update { current ->
            (current + fresh).sortedWith(compareBy({ it.docOrder }, { it.sequence }))
        }
        _states.update { current ->
            current + fresh.associate { it.key to ImageDownloadState.Pending as ImageDownloadState }
        }
        fresh.forEach { image ->
            if (inFlight.add(image.key)) queue.trySend(image)
        }
    }

    fun reset() {
        synchronized(seen) { seen.clear() }
        inFlight.clear()
        _images.value = emptyList()
        _states.value = emptyMap()
    }

    private suspend fun download(image: DetectedImage) {
        try {
            val cached = store.find(image.key)
            if (cached != null) {
                setState(image.key, ImageDownloadState.Ready(cached, cached.length(), null))
                return
            }

            setState(image.key, ImageDownloadState.Downloading)
            when (val outcome = fetcher.fetch(image.url, pageContext())) {
                is FetchOutcome.Success -> {
                    val file = store.write(image.key, outcome.bytes, outcome.contentType)
                    setState(
                        image.key,
                        ImageDownloadState.Ready(file, outcome.bytes.size.toLong(), outcome.contentType)
                    )
                }

                is FetchOutcome.Failure -> setState(
                    image.key,
                    ImageDownloadState.Failed(outcome.reason)
                )
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
    }
}
