package com.mangalens.reader

import android.content.Context
import android.graphics.Bitmap
import com.mangalens.core.di.Dependencies
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.TranslatedBubble
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.LlmTranslationProvider
import com.mangalens.translation.MlKitTranslationProvider
import com.mangalens.translation.MyMemoryTranslationProvider
import com.mangalens.translation.TranslationBackend
import com.mangalens.translation.TranslationProvider
import kotlinx.coroutines.*
import java.io.File

class ReadTranslatePipeline(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private var translator: MangaPageTranslator? = null
    private var backend: TranslationBackend = TranslationBackend.ML_KIT

    var status: PipelineStatus = PipelineStatus.Idle
        private set
    var progress: Int = 0
        private set
    var onTranslationReady: ((List<TranslatedBubble>) -> Unit)? = null

    fun setTranslationBackend(backend: TranslationBackend) {
        this.backend = backend
        translator?.close()
        translator = null
    }

    fun processImage(bitmap: Bitmap, sourceTag: String = "auto", targetTag: String = "id") {
        if (!Dependencies.isReady()) {
            setStatus(PipelineStatus.Error)
            return
        }
        currentJob?.cancel()
        status = PipelineStatus.Processing
        progress = 0
        currentJob = scope.launch {
            try {
                val result = getTranslator().translate(bitmap, LanguageScript.AUTO, sourceTag, targetTag)
                progress = 100
                status = PipelineStatus.Done
                onTranslationReady?.invoke(result.bubbles)
            } catch (e: Exception) {
                status = PipelineStatus.Error
                onTranslationReady?.invoke(emptyList())
            }
        }
    }

    fun processImageFile(file: File, sourceTag: String = "auto", targetTag: String = "id") {
        if (!Dependencies.isReady()) {
            setStatus(PipelineStatus.Error)
            return
        }
        currentJob?.cancel()
        status = PipelineStatus.Processing
        progress = 10
        currentJob = scope.launch {
            try {
                val bitmap = file.loadBitmap()
                if (bitmap == null) {
                    status = PipelineStatus.Error
                    onTranslationReady?.invoke(emptyList())
                    return@launch
                }
                progress = 30
                status = PipelineStatus.Processing
                val result = getTranslator().translate(bitmap, LanguageScript.AUTO, sourceTag, targetTag)
                bitmap.recycle()
                progress = 100
                status = PipelineStatus.Done
                onTranslationReady?.invoke(result.bubbles)
            } catch (e: Exception) {
                status = PipelineStatus.Error
                onTranslationReady?.invoke(emptyList())
            }
        }
    }

    private fun getTranslator(): MangaPageTranslator {
        val existing = translator
        if (existing != null && backend == TranslationBackend.ML_KIT) return existing
        translator?.close()
        val created = MangaPageTranslator(MlKitOcrEngine()) { source, target ->
            when (backend) {
                TranslationBackend.ML_KIT -> MlKitTranslationProvider(source, target)
                TranslationBackend.LLM -> LlmTranslationProvider(source, target)
                TranslationBackend.MY_MEMORY -> MyMemoryTranslationProvider(source, target)
            }
        }
        translator = created
        return created
    }

    private fun File.loadBitmap(): Bitmap? = runCatching {
        android.graphics.BitmapFactory.decodeFile(absolutePath)
    }.getOrNull()

    private fun setStatus(newStatus: PipelineStatus) {
        status = newStatus
    }

    fun cancel() {
        currentJob?.cancel()
        status = PipelineStatus.Idle
        progress = 0
    }

    fun close() {
        currentJob?.cancel()
        translator?.close()
        translator = null
        scope.cancel()
        status = PipelineStatus.Idle
        progress = 0
    }

    enum class PipelineStatus { Idle, Processing, Done, Error }
}
