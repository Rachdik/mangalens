package com.mangalens.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import com.mangalens.core.di.Dependencies
import com.mangalens.inpainting.BasicInpaintingEngine
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.PageTranslation
import com.mangalens.inpainting.TranslatedBubble
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.TranslationBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Pipeline terjemahan gambar halaman untuk Reader.
 *
 * Catatan kepemilikan bitmap:
 * - [processImage] memakai bitmap milik pemanggil; pipeline TIDAK me-recycle-nya.
 * - [processImageFile] memuat sendiri dan selalu me-recycle di blok finally,
 *   termasuk saat terjadi error atau job dibatalkan.
 */
class ReadTranslatePipeline(
    @Suppress("unused") private val context: Context,
    /** Konfigurasi LLM runtime (kunci dari penyimpanan terenkripsi). */
    private val llmConfigProvider: () -> com.mangalens.translation.LlmConfig = {
        com.mangalens.translation.LlmConfig()
    },
    /** Fallback on-device bila provider utama gagal. */
    private val autoFallbackProvider: () -> Boolean = { false },
    private val inpaintingEnabledProvider: () -> Boolean = { false }
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private var translator: MangaPageTranslator? = null
    private var translatorBackend: TranslationBackend? = null
    private var translatorFallback: Boolean? = null
    private var backend: TranslationBackend = TranslationBackend.ML_KIT

    var status: PipelineStatus = PipelineStatus.Idle
        private set
    var progress: Int = 0
        private set
    var onImageTranslated: ((ImageTranslation) -> Unit)? = null

    /** Status yang bisa ditampilkan ke pengguna, mis. "Mengenali teks…". */
    var onStage: ((String) -> Unit)? = null

    /**
     * Cache kecil hasil per halaman: membuka ulang gambar yang sama (mis. ganti
     * mode Asli -> Terjemahan) tidak menjalankan OCR/terjemahan/inpainting lagi.
     * Kunci memuat versi file, bahasa, provider, dan status inpainting.
     */
    private val pageCache = object : LinkedHashMap<String, ImageTranslation>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageTranslation>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

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
            val page = getTranslator()
                .translate(bitmap, LanguageScript.AUTO, sourceTag, targetTag)
            com.mangalens.translation.TokenUsageStore.add(
                context,
                page.usage,
                page.providerName,
                ""
            )
            ImageTranslation(
                null,
                page.bubbles,
                bitmap.width,
                bitmap.height,
                page.providerName
            )
        }
    }

    /**
     * Pipeline memuat file sendiri lalu me-recycle bitmap setelah selesai.
     * Mengembalikan [Job] agar pemanggil bisa menunggu sampai gambar ini selesai
     * sebelum memproses gambar berikutnya (memanggil lagi akan membatalkan job).
     */
    fun processImageFile(
        file: File,
        imageUrl: String? = null,
        sourceTag: String = "auto",
        targetTag: String = "id"
    ): Job? {
        if (!Dependencies.isReady()) {
            status = PipelineStatus.Error
            return null
        }
        progress = 10
        onStage?.invoke("Memuat gambar…")
        val script = scriptFor(sourceTag)
        val cacheKey = listOf(
            file.absolutePath,
            file.length(),
            file.lastModified(),
            sourceTag,
            targetTag,
            backend,
            autoFallbackProvider(),
            inpaintingEnabledProvider(),
            imageUrl.orEmpty()
        ).joinToString("|")
        return startJob {
            pageCache[cacheKey]?.let { cached ->
                progress = 100
                onStage?.invoke("Menampilkan terjemahan (cache)…")
                return@startJob cached
            }
            val bitmap = decodeBounded(file)
                ?: return@startJob ImageTranslation(imageUrl, emptyList(), 0, 0)
            try {
                progress = 30
                val translator = getTranslator()
                translator.onStage = { stage ->
                    progress = when (stage) {
                        com.mangalens.inpainting.TranslationStage.RECOGNIZING -> 40
                        com.mangalens.inpainting.TranslationStage.GROUPING -> 55
                        com.mangalens.inpainting.TranslationStage.TRANSLATING -> 65
                        com.mangalens.inpainting.TranslationStage.INPAINTING -> 85
                    }
                    onStage?.invoke(stageLabel(stage))
                }
                // Webtoon 1000x12000 tidak boleh dikecilkan menjadi 125 px lalu
                // di-OCR: teksnya hilang. Gambar dipotong vertikal dan tiap
                // potongan di-OCR pada lebar penuh, lalu koordinat digabung.
                val slices = splitForOcr(bitmap)
                val bubbles = mutableListOf<TranslatedBubble>()
                var providerName = ""
                var inpaintedDataUrl: String? = null

                if (inpaintingEnabledProvider()) {
                    val sliceInpainted = mutableListOf<Pair<Bitmap, PageTranslation>>()
                    for (slice in slices) {
                        val (inpaintedSlice, page) = translator.translateWithInpaint(
                            slice.bitmap,
                            script,
                            BasicInpaintingEngine(),
                            sourceTag,
                            targetTag
                        )
                        com.mangalens.translation.TokenUsageStore.add(
                            context,
                            page.usage,
                            page.providerName,
                            ""
                        )
                        providerName = page.providerName.ifBlank { providerName }
                        sliceInpainted += inpaintedSlice to page
                    }
                    val (composite, inpaintedBubbles) = compositeInpaintedSlices(
                        original = bitmap,
                        slices = slices,
                        sliceInpainted = sliceInpainted
                    )
                    // Sebelumnya hasil ini di-destructure ke `bubbles` lokal yang
                    // menutupi daftar bersama, sehingga Reader menerima 0 balon
                    // padahal gambar inpainting sudah berisi 5 terjemahan.
                    bubbles += inpaintedBubbles
                    inpaintedDataUrl = bitmapToDataUrl(composite)
                    if (!composite.isRecycled) composite.recycle()
                    for (slice in slices) {
                        if (slice.created && !slice.bitmap.isRecycled) slice.bitmap.recycle()
                    }
                } else {
                    var lastCoveredBottom = 0
                    for (slice in slices) {
                        val page = translator.translate(
                            slice.bitmap,
                            script,
                            sourceTag,
                            targetTag
                        )
                        com.mangalens.translation.TokenUsageStore.add(
                            context,
                            page.usage,
                            page.providerName,
                            ""
                        )
                        providerName = page.providerName.ifBlank { providerName }
                        val overlapTop = if (slice.top == 0) 0 else lastCoveredBottom - OCR_OVERLAP_PX
                        for (bubble in page.bubbles) {
                            val absolute = Rect(bubble.bounds)
                            absolute.offset(0, slice.top)
                            val text = Rect(bubble.textBounds)
                            text.offset(0, slice.top)
                            if (slice.top > 0 && text.centerY() < overlapTop + OCR_OVERLAP_PX) continue
                            bubbles += bubble.copy(
                                bounds = absolute,
                                textBounds = text,
                                textLines = bubble.textLines.map { line ->
                                    Rect(line).apply { offset(0, slice.top) }
                                },
                                id = bubble.id + "@" + slice.top
                            )
                        }
                        lastCoveredBottom = slice.top + slice.bitmap.height
                        if (slice.created && !slice.bitmap.isRecycled) slice.bitmap.recycle()
                    }
                }
                val ordered = bubbles
                    .sortedWith(compareBy({ it.textBounds.top }, { it.textBounds.left }))
                    .mapIndexed { index, bubble -> bubble.copy(readingOrder = index + 1) }
                onStage?.invoke("Menampilkan terjemahan…")
                val result = ImageTranslation(
                    imageUrl,
                    ordered,
                    bitmap.width,
                    bitmap.height,
                    providerName,
                    inpaintedDataUrl
                )
                if (ordered.isNotEmpty() || inpaintedDataUrl != null) {
                    pageCache[cacheKey] = result
                }
                result
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /**
     * Bahasa sumber yang dipilih pengguna dipakai langsung sebagai skrip OCR.
     * Sebelumnya Reader selalu memakai AUTO sehingga halaman Jepang menjalankan
     * recognizer Latin + Jepang (dan dulu Korea/Tiongkok juga).
     */
    private fun scriptFor(sourceTag: String): LanguageScript = when (sourceTag.lowercase()) {
        "ja", "jp" -> LanguageScript.JAPANESE
        "ko", "kr" -> LanguageScript.KOREAN
        "zh", "zh-cn", "zh-tw" -> LanguageScript.CHINESE
        "en", "es", "fr", "de", "pt", "id", "in", "vi", "ms", "th", "ar", "ru" ->
            LanguageScript.LATIN

        else -> LanguageScript.AUTO
    }

    private fun stageLabel(stage: com.mangalens.inpainting.TranslationStage): String = when (stage) {
        com.mangalens.inpainting.TranslationStage.RECOGNIZING -> "Mengenali teks…"
        com.mangalens.inpainting.TranslationStage.GROUPING -> "Mengelompokkan balon…"
        com.mangalens.inpainting.TranslationStage.TRANSLATING -> "Menerjemahkan…"
        com.mangalens.inpainting.TranslationStage.INPAINTING -> "Menghapus teks asli…"
    }

    private fun startJob(block: suspend () -> ImageTranslation): Job {
        currentJob?.cancel()
        status = PipelineStatus.Processing
        progress = 0
        currentJob = scope.launch {
            try {
                val result = block()
                progress = 100
                status = PipelineStatus.Done
                onImageTranslated?.invoke(result)
            } catch (c: CancellationException) {
                // Job diganti job baru atau pipeline ditutup: bukan error.
                status = PipelineStatus.Idle
                throw c
            } catch (t: Throwable) {
                status = PipelineStatus.Error
                progress = 0
                onStage?.invoke("Gagal memproses halaman. Gambar asli tetap ditampilkan.")
                // Hasil kosong tanpa URL: overlay lama dibuang, gambar asli tidak
                // pernah diganti, jadi halaman pengguna tetap utuh.
                onImageTranslated?.invoke(ImageTranslation(null, emptyList(), 0, 0))
            }
        }
        return currentJob!!
    }

    private fun getTranslator(): MangaPageTranslator {
        val fallback = autoFallbackProvider()
        val existing = translator
        if (existing != null && translatorBackend == backend && translatorFallback == fallback) {
            return existing
        }

        closeTranslator()
        val created = MangaPageTranslator(MlKitOcrEngine()) { source, target ->
            // Factory bersama dengan Share Receiver, supaya kedua jalur masuk
            // tidak pernah memakai pemetaan provider yang berbeda.
            com.mangalens.translation.TranslationProviders.create(
                backend,
                source,
                target,
                llmConfigProvider(),
                autoFallback = fallback
            )
        }
        translator = created
        translatorBackend = backend
        translatorFallback = fallback
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

    /**
     * Sample hanya bila piksel total terlalu besar. Lebar harus tetap cukup
     * untuk OCR (minimal ~700 px); dulu batas sisi terpanjang membuat webtoon
     * 1000x12000 turun ke 125x1500 sehingga teks tidak terbaca.
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        fun pixels(s: Int): Long = (width / s).toLong() * (height / s)
        while (sample < 8 && pixels(sample) > MAX_DECODE_PIXELS && width / (sample * 2) >= 700) {
            sample *= 2
        }
        return sample
    }

    private data class OcrSlice(
        val bitmap: Bitmap,
        val top: Int,
        val created: Boolean
    )

    /** Potongan vertikal dengan tumpang tindih agar balon di batas tidak terbelah. */
    private fun splitForOcr(bitmap: Bitmap): List<OcrSlice> {
        if (bitmap.height <= OCR_SLICE_HEIGHT) {
            return listOf(OcrSlice(bitmap, 0, false))
        }
        val slices = mutableListOf<OcrSlice>()
        var top = 0
        while (top < bitmap.height) {
            val height = min(OCR_SLICE_HEIGHT, bitmap.height - top)
            if (height < OCR_MIN_SLICE_HEIGHT && slices.isNotEmpty()) break
            val piece = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            slices += OcrSlice(piece, top, true)
            if (top + height >= bitmap.height) break
            top += OCR_SLICE_HEIGHT - OCR_OVERLAP_PX
        }
        return slices
    }

    /**
     * Gabungkan hasil inpaint per-slice menjadi satu gambar penuh.
     *
     * Alasan pemrosesan perslice dengan koordinat lokal:
     * - [MangaPageTranslator.translateWithInpaint] membuat mask dan hasil inpaint
     *   dengan ukuran sama sebagai bitmap masukan (slice). Ia tidak tahu tentang
     *   gambar original atau offset slice.
     * - Jadi setiap slice menghasilkan (inpaintedSlice, page) dalam koordinat lokal.
     * - Bubbles harus di-offsetkan ke koordinat original, duplikat di area overlap
     *   dibuang, lalu inpaintedSlice dikomposit kembali ke gambar penuh.
     *
     * Strategi komposit:
     * - Gambar penuh diinisialisasi sebagai salinan gambar asli.
     * - Setiap slice di-composit dari atas ke bawah; slice di posisi lebih rendah
     *   menimpa area overlap. Ini konsisten dengan logika OCR (slice akhir
     *   bersifat menentukan di area overlap) dan menghindari celahan
     *   karena overlap hanya 120 px.
     *
     * Deduplikasi bubbles:
     * - Bubbles dari slice yang lebih bawah dianggap lebih dapat diandalkan di
     *   area overlap karena pada area itu teks asli tumpang tindih dengan potongan
     *   di atasnya; kita pertahankan bubble terbawah yang menyentuh area overlap.
     * - ID bubble dibuat unik per posisi slice (`bubble.id + "@" + slice.top`)
     *   sehingga bubble yang sama di slice berbeda tidak dianggap identik oleh
     *   penggabungan — semuanya dianggap berbeda dan diduplikasi, lalu dibuang
     *   oleh aturan overlap di bawah.
     */
    private fun compositeInpaintedSlices(
        original: Bitmap,
        slices: List<OcrSlice>,
        sliceInpainted: List<Pair<Bitmap, PageTranslation>>
    ): Pair<Bitmap, List<TranslatedBubble>> {
        val width = original.width
        val height = original.height
        val full = original.copy(Bitmap.Config.ARGB_8888, true)
        val fullPixels = IntArray(width * height)
        full.getPixels(fullPixels, 0, width, 0, 0, width, height)

        val allBubbles = mutableListOf<TranslatedBubble>()
        val topToBottom = slices.zip(sliceInpainted).sortedBy { it.first.top }

        for (pair in topToBottom) {
            val slice = pair.first
            val inpaintedSlice = pair.second.first
            val page = pair.second.second
            // Komposit hasil inpaint slice ke gambar penuh.
            if (!inpaintedSlice.isRecycled) {
                val slicePixels = IntArray(inpaintedSlice.width * inpaintedSlice.height)
                inpaintedSlice.getPixels(
                    slicePixels, 0, inpaintedSlice.width, 0, 0,
                    inpaintedSlice.width, inpaintedSlice.height
                )
                copyRect(
                    srcPixels = slicePixels,
                    dstPixels = fullPixels,
                    srcWidth = inpaintedSlice.width,
                    dstX = 0,
                    dstY = slice.top,
                    width = inpaintedSlice.width,
                    height = inpaintedSlice.height,
                    dstWidth = width
                )
            }

            // Pindahkan bubbles ke koordinat original, lalu pertahankan hanya yang
            // tidak tumpang tindih dengan slice yang lebih bawah.
            for (bubble in page.bubbles) {
                val absBounds = Rect(bubble.bounds).apply { offset(0, slice.top) }
                val absText = Rect(bubble.textBounds).apply { offset(0, slice.top) }
                val absLines = bubble.textLines.map { Rect(it).apply { offset(0, slice.top) } }
                allBubbles += bubble.copy(
                    bounds = absBounds,
                    textBounds = absText,
                    textLines = absLines,
                    id = bubble.id + "@" + slice.top
                )
            }
        }

        full.setPixels(fullPixels, 0, width, 0, 0, width, height)

        // Deduplikasi: hanya bubble yang SAMA (ditemukan di dua slice yang
        // tumpang tindih) yang dibuang.
        //
        // Versi sebelumnya membuang setiap bubble yang mulai di atas bubble
        // sebelumnya (`top < coveredBottom`). Itu juga membuang balon sah yang
        // berdampingan/bertumpuk vertikal di panel yang sama — pada halaman uji
        // 5 balon terjemahan menjadi 0 sehingga overlay Reader kosong.
        val ordered = allBubbles.sortedWith(
            compareBy({ it.textBounds.top }, { it.textBounds.left })
        )
        val kept = mutableListOf<TranslatedBubble>()
        for (bubble in ordered) {
            if (kept.any { sameBubble(it.textBounds, bubble.textBounds) }) continue
            kept += bubble
        }

        val finalOrdered = kept.sortedWith(
            compareBy({ it.textBounds.top }, { it.textBounds.left })
        ).mapIndexed { index, bubble -> bubble.copy(readingOrder = index + 1) }

        return full to finalOrdered
    }

    /** True bila dua kotak teks menunjuk balon yang sama (bukan sekadar berdekatan). */
    private fun sameBubble(a: Rect, b: Rect): Boolean {
        val intersection = Rect()
        if (!intersection.setIntersect(a, b)) return false
        val overlap = intersection.width().toLong() * intersection.height()
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return smaller > 0 && overlap * 10 >= smaller * 6
    }

    /** Salin kotak dari src ke dst pada posisi dstX/dstY. */
    private fun copyRect(
        srcPixels: IntArray,
        dstPixels: IntArray,
        srcWidth: Int,
        dstX: Int,
        dstY: Int,
        width: Int,
        height: Int,
        dstWidth: Int
    ) {
        val left = dstX.coerceIn(0, dstWidth - 1)
        val top = dstY.coerceIn(0, dstPixels.size / dstWidth)
        val right = (dstX + width).coerceAtMost(dstWidth)
        val bottom = (dstY + height).coerceAtMost(dstPixels.size / dstWidth)
        for (y in top until bottom) {
            val srcRow = (y - dstY) * srcWidth
            val dstRow = y * dstWidth
            val copyW = right - left
            if (copyW <= 0) continue
            val srcStart = srcRow + (left - dstX)
            val dstStart = dstRow + left
            for (x in 0 until copyW) {
                dstPixels[dstStart + x] = srcPixels[srcStart + x]
            }
        }
    }

    private fun closeTranslator() {
        runCatching { translator?.close() }
        translator = null
        translatorBackend = null
    }

    private fun bitmapToDataUrl(bitmap: Bitmap): String? = runCatching {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

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
        /** Batas memori bitmap yang didekode (ARGB 4 byte/piksel). */
        const val MAX_DECODE_PIXELS = 24_000_000L
        const val OCR_SLICE_HEIGHT = 2000
        const val OCR_OVERLAP_PX = 120
        const val OCR_MIN_SLICE_HEIGHT = 240

        /** Cukup 3 halaman terakhir: hemat memori, tetap menutup navigasi bolak-balik. */
        const val MAX_CACHE_ENTRIES = 3
    }
}

/**
 * Hasil terjemahan satu gambar.
 *
 * [imageUrl] adalah URL gambar yang diterjemahkan (null untuk pemrosesan
 * bitmap manual), [imageWidth]/[imageHeight] adalah ukuran bitmap yang dipakai
 * OCR. Overlay memakai keduanya untuk mengubah koordinat piksel menjadi fraksi
 * 0..1 lalu menskalakannya ke ukuran gambar yang benar-benar dirender WebView.
 */
data class ImageTranslation(
    val imageUrl: String?,
    val bubbles: List<TranslatedBubble>,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Provider yang benar-benar dipakai; kosong bila tidak ada terjemahan. */
    val providerName: String = "",
    /** Data URL gambar yang sudah di-inpainting (opsional). */
    val inpaintedDataUrl: String? = null
)
