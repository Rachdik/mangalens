package com.mangalens.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mangalens.core.di.Dependencies
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.TranslatedBubble
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.LlmTranslationProvider
import com.mangalens.translation.MlKitTranslationProvider
import com.mangalens.translation.MyMemoryTranslationProvider
import com.mangalens.translation.TranslationBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/**
 * Pipeline terjemahan gambar halaman untuk Reader.
 *
 * Catatan kepemilikan bitmap:
 * - [processImage] memakai bitmap milik pemanggil; pipeline TIDAK me-recycle-nya.
 * - [processImageFile] memuat sendiri dan selalu me-recycle di blok finally,
 *   termasuk saat terjadi error atau job dibatalkan.
 */
class ReadTranslatePipeline(
    @Suppress("unused") private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private var translator: MangaPageTranslator? = null
    private var translatorBackend: TranslationBackend? = null
    private var backend: TranslationBackend = TranslationBackend.ML_KIT

    var status: PipelineStatus = PipelineStatus.Idle
        private set
    var progress: Int = 0
        private set
    var onTranslationReady: ((List<TranslatedBubble>) -> Unit)? = null

    fun setTranslationBackend(backend: TranslationBackend) {
        if (this.backend == backend && translator != null) return
        this.backend = backend
        closeTranslator()
    }

    /** Bitmap milik pemanggil; pipeline tidak me-recycle-nya. */
    fun processImage(bitmap: Bitmap, sourceTag: String = "auto", targetTag: String = "id") {
        if (!Dependencies.isReady() || bitmap.isRecycled) {
            status = PipelineStatus.Error
            return
        }
        startJob {
            getTranslator()
                .translate(bitmap, LanguageScript.AUTO, sourceTag, targetTag)
                .bubbles
        }
    }

    /**
     * Pipeline memuat file sendiri lalu me-recycle bitmap setelah selesai.
     * Mengembalikan [Job] agar pemanggil bisa menunggu sampai gambar ini selesai
     * sebelum memproses gambar berikutnya (memanggil lagi akan membatalkan job).
     */
    fun processImageFile(file: File, sourceTag: String = "auto", targetTag: String = "id"): Job? {
        if (!Dependencies.isReady()) {
            status = PipelineStatus.Error
            return null
        }
        progress = 10
        return startJob {
            val bitmap = decodeBounded(file) ?: return@startJob emptyList()
            try {
                progress = 30
                getTranslator()
                    .translate(bitmap, LanguageScript.AUTO, sourceTag, targetTag)
                    .bubbles
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun startJob(block: suspend () -> List<TranslatedBubble>): Job {
        currentJob?.cancel()
        status = PipelineStatus.Processing
        progress = 0
        currentJob = scope.launch {
            try {
                val bubbles = block()
                progress = 100
                status = PipelineStatus.Done
                onTranslationReady?.invoke(bubbles)
            } catch (c: CancellationException) {
                // Job diganti job baru atau pipeline ditutup: bukan error.
                status = PipelineStatus.Idle
                throw c
            } catch (t: Throwable) {
                status = PipelineStatus.Error
                onTranslationReady?.invoke(emptyList())
            }
        }
        return currentJob!!
    }

    private fun getTranslator(): MangaPageTranslator {
        val existing = translator
        if (existing != null && translatorBackend == backend) return existing

        closeTranslator()
        val created = MangaPageTranslator(MlKitOcrEngine()) { source, target ->
            when (backend) {
                TranslationBackend.ML_KIT -> MlKitTranslationProvider(source, target)
                TranslationBackend.LLM -> LlmTranslationProvider(source, target)
                TranslationBackend.MY_MEMORY -> MyMemoryTranslationProvider(source, target)
            }
        }
        translator = created
        translatorBackend = backend
        return created
    }

    /**
     * Decode dengan batas dimensi agar halaman webtoon raksasa tidak
     * menghabiskan memori. Sisi terpanjang dibatasi [MAX_DECODE_DIMENSION].
     */
    private fun decodeBounded(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / sample > MAX_DECODE_DIMENSION) {
            sample *= 2
            if (sample > 8) break
        }
        return sample
    }

    private fun closeTranslator() {
        runCatching { translator?.close() }
        translator = null
        translatorBackend = null
    }

    fun cancel() {
        currentJob?.cancel()
        status = PipelineStatus.Idle
        progress = 0
    }

    fun close() {
        currentJob?.cancel()
        currentJob = null
        closeTranslator()
        scope.cancel()
        status = PipelineStatus.Idle
        progress = 0
    }

    enum class PipelineStatus { Idle, Processing, Done, Error }

    private companion object {
        const val MAX_DECODE_DIMENSION = 2400
    }
}
