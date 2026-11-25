package com.mangalens.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.util.Log
import com.mangalens.core.di.Dependencies
import com.mangalens.inpainting.TranslatedBubble
import com.mangalens.reader.databinding.ActivityReaderBinding
import com.mangalens.reader.images.CanvasCapture
import com.mangalens.reader.images.CanvasExtractor
import com.mangalens.reader.images.CanvasPixelCapture
import com.mangalens.reader.images.CanvasQuality
import com.mangalens.reader.images.DetectedImage
import com.mangalens.reader.images.ImageListSheet
import com.mangalens.reader.images.ImageScanSession
import com.mangalens.reader.images.ImageStore
import com.mangalens.reader.images.NativeImageFetcher
import com.mangalens.reader.images.PageContext
import com.mangalens.reader.images.FetchOutcome
import com.mangalens.translation.TranslationBackend
import com.mangalens.translation.TranslationSettings
import com.mangalens.ui.theme.MangaLensTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

/**
 * Info sumber gambar canvas untuk diagnostik.
 */
data class CanvasSourceInfo(
    val nativeUrl: String,
    val nativeWidth: Int = 0,
    val nativeHeight: Int = 0,
    val canvasTainted: Boolean = false
)

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var scanSession: ImageScanSession
    private var imageSheet: ImageListSheet? = null
    private var translatePipeline: ReadTranslatePipeline? = null
    private var translationManager: ImageTranslationManager? = null
    private lateinit var translationSettings: TranslationSettings

    /** Sidik isi canvas yang sudah diproses; mencegah overlay ganda. */
    private val canvasContentHashes = mutableSetOf<Int>()

    /** Deteksi perubahan pengaturan saat kembali dari layar Settings. */
    private var lastSettingsSignature: String? = null

    /** Mode tampilan Reader: true = bitmap inpainting + overlay, false = asli. */
    private var showingTranslation = true

    /** Hasil terakhir, dipakai saat pengguna berpindah kembali ke mode terjemahan. */
    private var lastTranslation: ImageTranslation? = null
    private var statusHideToken = 0

    private val scanScript: String by lazy {
        runCatching {
            assets.open(SCAN_SCRIPT).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private val viewModel: ReaderViewModel by viewModels {
        ReaderViewModel.factory(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        MangaLensTheme.apply(this)
        translationSettings = TranslationSettings(this)
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MangaLensTheme.applySystemBars(this)

        scanSession = ImageScanSession(
            scope = lifecycleScope,
            fetcher = NativeImageFetcher(),
            store = ImageStore(File(cacheDir, "source-images")),
            // Canvas tidak bisa diunduh lewat HTTP: isinya diambil dari WebView.
            canvasExtractor = object : CanvasExtractor {
                override suspend fun extract(key: String): CanvasCapture? = extractCanvas(key)
            },
            pageContext = {
                PageContext(
                    pageUrl = binding.webView.url ?: viewModel.state.value.url,
                    userAgent = binding.webView.settings.userAgentString
                )
            }
        )

        configureWebView()

        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }
        binding.imageCountButton.setOnClickListener { openImageSheet() }
        binding.reloadButton.setOnClickListener {
            // Refresh halaman: memuat ulang HTML/WebView, bukan sekadar OCR.
            Toast.makeText(this, R.string.reader_reloading, Toast.LENGTH_SHORT).show()
            Log.d(TAG_OVERLAY, "refresh halaman: webView.reload()")
            binding.webView.reload()
        }
        binding.retranslateButton.setOnClickListener { refreshTranslation() }
        binding.readerSettingsButton.setOnClickListener { openSettings() }
        binding.modeButton.setOnClickListener { toggleTranslationMode() }
        updateModeButton()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        val restored = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE)
        if (restored != null) {
            binding.webView.restoreState(restored)
        } else {
            val initialUrl = viewModel.state.value.url
            if (initialUrl.isNotBlank()) binding.webView.loadUrl(initialUrl)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanSession.images.collect { images ->
                    binding.imageCountButton.text = getString(R.string.image_count, images.size)
                }
            }
        }

        // Setup terjemahan otomatis
        if (Dependencies.isReady()) {
            setupAutoTranslation()
            // Baseline agar perubahan pengaturan saat Reader terbuka terdeteksi
            // di onResume dan langsung memicu terjemahan ulang.
            lastSettingsSignature = settingsSignature()
        }
    }

    private fun setupAutoTranslation() {
        translatePipeline = ReadTranslatePipeline(
            context = this,
            llmConfigProvider = { translationSettings.llmConfig() },
            autoFallbackProvider = { translationSettings.autoFallback },
            // Reader selalu menyiapkan bitmap inpainting: mode "Terjemahan"
            // memang menampilkan gambar yang teks aslinya sudah dihapus.
            // Pengaturan global tetap berlaku untuk jalur lain.
            inpaintingEnabledProvider = { true }
        )
        applyTranslationSettings()

        translatePipeline?.onStage = { text ->
            binding.webView.post { showStatus(text) }
        }

        // Callback dipanggil dari coroutine pipeline (Dispatchers.IO). Semua metode
        // WebView wajib dijalankan di thread yang membuat WebView, jadi hasilnya
        // diposting ke thread UI sebelum menyentuh WebView.
        translatePipeline?.onImageTranslated = { result ->
            binding.webView.post { applyTranslationOverlay(result) }
        }

        translationManager = ImageTranslationManager(
            context = this,
            scanSession = scanSession,
            pipeline = translatePipeline!!,
            scope = lifecycleScope,
            sourceTag = translationSettings.sourceTag,
            targetTag = translationSettings.targetTag
        )
        translationManager?.start()
    }

    /**
     * Reader wajib memakai pengaturan yang sama dengan layar utama/Floating.
     * Sebelumnya backend dikunci ke ML_KIT dan tag dikunci "auto"/"id".
     */
    private fun applyTranslationSettings() {
        val backend = translationSettings.backend
        translatePipeline?.setTranslationBackend(backend)
        translationManager?.setLanguageTags(
            translationSettings.sourceTag,
            translationSettings.targetTag
        )
    }

    /**
     * Menempel hasil terjemahan pada gambar yang benar.
     *
     * Koordinat bubble diubah menjadi fraksi 0..1 terhadap bitmap yang dipakai
     * OCR, lalu JavaScript memetakannya ke ukuran/posisi gambar yang sedang
     * dirender (getBoundingClientRect). Dengan begitu overlay tetap menempel saat
     * gambar diperkecil/diperbesar CSS situs, saat lazy-load menggeser halaman,
     * maupun saat halaman digulir.
     */
    private fun applyTranslationOverlay(result: ImageTranslation) {
        if (isFinishing || isDestroyed) return
        Log.d(
            TAG_OVERLAY,
            "result url=${result.imageUrl} bubbles=${result.bubbles.size} " +
                "provider=${result.providerName.ifBlank { "-" }} " +
                "inpainted=${result.inpaintedDataUrl != null} " +
                "size=${result.imageWidth}x${result.imageHeight}"
        )
        val url = result.imageUrl
        if (url.isNullOrBlank() || result.imageWidth <= 0 || result.imageHeight <= 0) {
            // Tidak ada konteks gambar (mis. OCR gagal): jangan tinggalkan overlay lama.
            return
        }

        val webView = binding.webView
        lastTranslation = result
        result.inpaintedDataUrl?.let { dataUrl ->
            // Ganti sumber gambar dengan bitmap inpainting lewat helper JS, yang
            // menyimpan sumber asli agar mode "Asli" bisa memulihkannya tanpa
            // memuat ulang situs.
            val script = "(function(){return window.__mangalensApplyInpainted?" +
                "window.__mangalensApplyInpainted(" + JSONObject.quote(url) + "," +
                JSONObject.quote(dataUrl) + "):false;})();"
            webView.evaluateJavascript(script) { applied ->
                Log.d(TAG_OVERLAY, "inpainted src applied=$applied url=$url")
            }
        }
        if (result.bubbles.isEmpty()) {
            // Gambar ini tidak punya teks: pastikan overlay lamanya dibuang,
            // bukan meninggalkan kotak kosong.
            webView.evaluateJavascript(
                "window.__mangalensRemoveOverlay&&" +
                    "window.__mangalensRemoveOverlay(${JSONObject.quote(url)});",
                null
            )
            return
        }
        val items = result.bubbles.joinToString(",") { bubble ->
            val bounds = bubble.bounds
            val textBounds = bubble.textBounds
            val fx = bounds.left.toDouble() / result.imageWidth
            val fy = bounds.top.toDouble() / result.imageHeight
            val fw = bounds.width().toDouble() / result.imageWidth
            val fh = bounds.height().toDouble() / result.imageHeight
            val tx = textBounds.left.toDouble() / result.imageWidth
            val ty = textBounds.top.toDouble() / result.imageHeight
            val tw = textBounds.width().toDouble() / result.imageWidth
            val th = textBounds.height().toDouble() / result.imageHeight
            val background = "rgba(" +
                Color.red(bubble.fillColor) + "," +
                Color.green(bubble.fillColor) + "," +
                Color.blue(bubble.fillColor) + ",0.94)"
            val foreground = "#" + String.format(java.util.Locale.US, "%06X", 0xFFFFFF and bubble.textColor)
            "{\"id\":${JSONObject.quote(bubble.id)}," +
                "\"order\":${bubble.readingOrder},\"conf\":\"${bubble.confidence.name}\"," +
                "\"fx\":$fx,\"fy\":$fy,\"fw\":$fw,\"fh\":$fh," +
                "\"tx\":$tx,\"ty\":$ty,\"tw\":$tw,\"th\":$th," +
                "\"bg\":\"$background\",\"fg\":\"$foreground\"," +
                "\"text\":${JSONObject.quote(bubble.targetText)}}"
        }
        val payload = "{\"url\":${JSONObject.quote(url)},\"items\":[$items]}"

        Log.d(
            TAG_OVERLAY,
            "render ${result.bubbles.size} terjemahan (${result.imageWidth}x${result.imageHeight}) " +
                "provider=${result.providerName.ifBlank { "-" }}"
        )
        // Bukti segmentasi: satu baris log per kelompok teks (bukan satu blok
        // gabungan), termasuk ID, urutan baca, dan keyakinan batas balon.
        result.bubbles.forEach { bubble ->
            Log.d(
                TAG_OVERLAY,
                "bubble id=${bubble.id} order=${bubble.readingOrder} conf=${bubble.confidence} " +
                    "bounds=${bubble.textBounds.toShortString()} src=${bubble.sourceText.take(48)} " +
                    "-> ${bubble.targetText.take(64)}"
            )
        }

        // Terapkan mode aktif: bila pengguna sedang melihat gambar asli, overlay
        // dan bitmap inpainting disembunyikan sampai ia memilih Terjemahan lagi.
        webView.evaluateJavascript(
            "window.__mangalensImageMode=" +
                JSONObject.quote(if (showingTranslation) "translated" else "original") + ";" +
                "(function(){var p=$payload;" +
                "return window.__mangalensOverlay?window.__mangalensOverlay(p):-1;})();"
        ) { domCount ->
            Log.d(TAG_OVERLAY, "overlay containers=$domCount mode=${if (showingTranslation) "translated" else "original"}")
            webView.evaluateJavascript(
                "window.__mangalensReaderState?window.__mangalensReaderState():'{}'"
            ) { state ->
                Log.d(TAG_READER_STATE, state.orEmpty())
                showStatus("Terjemahan siap · ${result.bubbles.size} balon")
            }
        }
    }

    /**
     * Berpindah antara bitmap inpainting + terjemahan dan halaman asli.
     * Hasil terakhir dipakai ulang, jadi tidak ada OCR/terjemahan ulang saat
     * pengguna bolak-balik.
     */
    private fun toggleTranslationMode() {
        showingTranslation = !showingTranslation
        updateModeButton()
        val webView = binding.webView
        webView.evaluateJavascript(
            "window.__mangalensSetImageMode?" +
                "window.__mangalensSetImageMode(${JSONObject.quote(if (showingTranslation) "translated" else "original")})" +
                ":'no-js'"
        ) { mode ->
            Log.d(TAG_OVERLAY, "mode=$mode")
            webView.evaluateJavascript(
                "window.__mangalensReaderState?window.__mangalensReaderState():'{}'"
            ) { state -> Log.d(TAG_READER_STATE, "mode changed $state") }
        }
        if (showingTranslation && lastTranslation == null) {
            showStatus("Menerjemahkan halaman…")
            refreshTranslation()
        } else {
            showStatus(if (showingTranslation) "Mode terjemahan" else "Mode gambar asli")
        }
    }

    private fun updateModeButton() {
        binding.modeButton.setText(
            if (showingTranslation) R.string.reader_mode_translated else R.string.reader_mode_original
        )
    }

    /** Status dipakai agar pengguna tahu tahap mana yang sedang berjalan. */
    private fun showStatus(text: String) {
        if (isFinishing || isDestroyed) return
        binding.statusView.text = text
        binding.statusView.visibility = View.VISIBLE
        statusHideToken++
        val token = statusHideToken
        if (text.startsWith("Terjemahan siap")) {
            binding.webView.postDelayed({
                if (token == statusHideToken && !isDestroyed) {
                    binding.statusView.visibility = View.GONE
                }
            }, 2500)
        }
    }

    private fun configureWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.addJavascriptInterface(ScanBridge(), BRIDGE_NAME)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                scanSession.reset()
                translationManager?.reset()
                canvasContentHashes.clear()
                lastTranslation = null
                // Hentikan OCR/terjemahan halaman lama; hasilnya sudah tidak relevan.
                translatePipeline?.cancel()
                // Overlay halaman lama harus hilang total sebelum halaman baru diproses.
                binding.webView.evaluateJavascript(
                    "window.__mangalensClearOverlays&&window.__mangalensClearOverlays();",
                    null
                )
                viewModel.onPageStarted(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val pageUrl = url.orEmpty()
                val pageTitle = view?.title.orEmpty()
                viewModel.onPageFinished(pageUrl, pageTitle)
                injectScanner()

                if (pageUrl.isNotBlank() && Dependencies.isReady()) {
                    lifecycleScope.launch {
                        runCatching { Dependencies.historyStore.record(pageUrl, pageTitle) }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    viewModel.onError(error.description?.toString().orEmpty())
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                viewModel.onProgress(newProgress)
            }
        }
    }

    private fun injectScanner() {
        if (scanScript.isBlank()) return
        binding.webView.evaluateJavascript(scanScript, null)
    }

    private fun openImageSheet() {
        val sheet = imageSheet ?: ImageListSheet(this, scanSession).also { imageSheet = it }
        sheet.show()
    }

    /**
     * Terjemahkan ulang halaman yang sudah terbuka tanpa memuat ulang internet.
     * Overlay lama dibuang, pencarian gambar/OCR dimulai dari cache, dan proses
     * lama dibatalkan agar tidak ada dua pemindaian berjalan bersamaan.
     */
    private fun refreshTranslation() {
        Log.d(TAG_OVERLAY, "refresh terjemahan tanpa reload halaman")
        Toast.makeText(this, R.string.reader_retranslating, Toast.LENGTH_SHORT).show()
        binding.webView.evaluateJavascript(
            "window.__mangalensClearOverlays&&window.__mangalensClearOverlays();",
            null
        )
        translatePipeline?.cancel()
        translationManager?.reset()
        scanSession.reset()
        canvasContentHashes.clear()
        lastTranslation = null
        showStatus("Memindai gambar…")
        lastSettingsSignature = settingsSignature()
        injectScanner()
        // Force rescan: gambar yang sama harus dikirim ulang walau URL/tanda
        // "sudah dipindai" belum berubah.
        binding.webView.evaluateJavascript(
            "window.__mangalensSetActive&&window.__mangalensSetActive(true);" +
                "window.__mangalensForceRescan?window.__mangalensForceRescan():" +
                "(window.__mangalensScan&&window.__mangalensScan());",
            null
        )
    }

    private fun openSettings() {
        // SettingsActivity berada di modul app; Reader tidak boleh bergantung
        // pada modul app, jadi dibuka lewat nama komponen runtime.
        val intent = Intent().setClassName(packageName, "com.mangalens.app.SettingsActivity")
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Pengaturan tidak tersedia", Toast.LENGTH_SHORT).show() }
    }

    private fun settingsSignature(): String =
        "${translationSettings.backend}|${translationSettings.sourceTag}|" +
            "${translationSettings.targetTag}|${translationSettings.autoFallback}|" +
            "${translationSettings.llmModel}|${translationSettings.llmConfigured}|" +
            "${translationSettings.inpaintingEnabled}"

    private fun render(state: ReaderUiState) {
        binding.titleView.text = state.title.ifBlank { state.url }
        binding.progressBar.progress = state.progress
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        val error = state.errorMessage
        binding.errorView.text = error
        binding.errorView.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
    }


    /**
     * Mengambil isi `<canvas>` dari halaman lewat JavaScript, lalu mengubah data
     * URL menjadi byte gambar. Semua error (canvas tainted, kosong, detached)
     * dikembalikan sebagai null agar pipeline menandainya gagal — bukan hasil
     * terjemahan palsu dan bukan crash.
     */
    private suspend fun extractCanvas(key: String): CanvasCapture? {
        if (isFinishing || isDestroyed) return null

        // 1. Coba ekstraksi asli dari canvas (toDataURL)
        val script = "(function(){try{return window.__mangalensExtractCanvas" +
            "?window.__mangalensExtractCanvas(" + JSONObject.quote(key) + ")" +
            ":JSON.stringify({ok:false,error:'no-extractor'});}" +
            "catch(e){return JSON.stringify({ok:false,error:String(e)});}})()"

        val json = evalJs(script)?.let { unquoteJson(it) }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        if (json != null && json.optBoolean("ok")) {
            val dataUrl = json.optString("url", "")
            if (dataUrl.isNotBlank()) {
                val capture = decodeDataUrl(dataUrl)
                if (capture != null) {
                    Log.d(TAG_OVERLAY, "canvas native extraction OK: ${capture.width}x${capture.height}")
                    // Simpan untuk diagnostik
                    if (isDebuggableBuild()) {
                        exportCanvasSample(key + "_native", capture)
                    }
                    return capture
                }
            }
        } else if (json != null) {
            Log.w(TAG_OVERLAY, "canvas toDataURL gagal: ${json.optString("error")}")
        }

        // 2. Cek apakah ada sumber gambar asli yang bisa diambil lewat HTTP
        val sourceInfo = fetchCanvasSourceImage(key)
        if (sourceInfo != null && sourceInfo.nativeUrl != null) {
            Log.d(TAG_OVERLAY, "found native source: ${sourceInfo.nativeUrl} " +
                "${sourceInfo.nativeWidth}x${sourceInfo.nativeHeight}")
            // Coba unduh dari URL asli
            val nativeCapture = tryNativeImageFetch(sourceInfo.nativeUrl, key)
            if (nativeCapture != null) {
                Log.d(TAG_OVERLAY, "native fetch OK: ${nativeCapture.width}x${nativeCapture.height}")
                if (isDebuggableBuild()) {
                    exportCanvasSample(key + "_native_fetch", nativeCapture)
                }
                return nativeCapture
            }
        }

        // 3. Fallback: PixelCopy untuk canvas tainted atau tanpa sumber asli
        Log.d(TAG_OVERLAY, "falling back to PixelCopy for key=$key")
        return CanvasPixelCapture.capture(
            activity = this,
            webView = binding.webView,
            key = key,
            eval = { evalJs(it) },
            acceptContent = { canvasContentHashes.add(it) }
        )?.also { capture ->
            // Build debug menyimpan hasil tangkapan agar bisa diperiksa/diekspor
            // (adb pull dari folder files milik aplikasi). Build rilis tidak menulis apa pun.
            if (isDebuggableBuild()) exportCanvasSample(key, capture)
        }
    }

    /**
     * Ambil info sumber gambar dari JavaScript untuk mencoba ekstraksi native.
     */
    private suspend fun fetchCanvasSourceImage(key: String): CanvasSourceInfo? {
        val script = "(function(){try{return window.__mangalensCanvasSourceInfo" +
            "?window.__mangalensCanvasSourceInfo(" + JSONObject.quote(key) + ")" +
            ":JSON.stringify({sourceType:'unknown'});}" +
            "catch(e){return JSON.stringify({sourceType:'error',error:String(e)});}})()"

        val json = evalJs(script)?.let { unquoteJson(it) }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        if (json == null) return null

        val sourceType = json.optString("sourceType", "unknown")
        if (sourceType == "unknown" || sourceType == "error" || sourceType == "detached") {
            return null
        }

        val nativeUrl = json.optString("nativeUrl").ifBlank { null }
        val nativeWidth = json.optInt("nativeWidth", 0)
        val nativeHeight = json.optInt("nativeHeight", 0)
        val canvasTainted = json.optBoolean("canvasTainted", false)

        // Hanya ambil jika ada URL asli dan bukan canvas tainted complex
        if (sourceType == "native" && !nativeUrl.isNullOrBlank()) {
            return CanvasSourceInfo(
                nativeUrl = nativeUrl,
                nativeWidth = nativeWidth,
                nativeHeight = nativeHeight,
                canvasTainted = canvasTainted
            )
        }

        return null
    }

    /**
     * Coba unduh gambar asli dari URL yang ditemukan.
     */
    private suspend fun tryNativeImageFetch(url: String, key: String): CanvasCapture? {
        try {
            val context = PageContext(
                pageUrl = binding.webView.url?.toString() ?: "",
                userAgent = binding.webView.settings.userAgentString
            )
            val fetcher = NativeImageFetcher()
            val outcome = fetcher.fetch(url, context)
            when {
                outcome.isSuccess -> {
                    val bytes = outcome.bytesOrNull ?: return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        val verdict = validateDecoded(bytes, bounds.outWidth, bounds.outHeight)
                        if (verdict?.ok != true) {
                            Log.w(
                                TAG_OVERLAY,
                                "native fetch ditolak: ${verdict?.reason ?: "tidak bisa didekode"}"
                            )
                            return null
                        }
                        Log.d(TAG_OVERLAY, "native fetch berhasil: ${bounds.outWidth}x${bounds.outHeight}")
                        return CanvasCapture(bytes, bounds.outWidth, bounds.outHeight)
                    }
                }
                !outcome.isSuccess -> {
                    Log.w(TAG_OVERLAY, "native fetch gagal: ${outcome.reasonOrNull}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG_OVERLAY, "native fetch exception: ${e.message}")
        }
        return null
    }

            private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun exportCanvasSample(key: String, capture: CanvasCapture) {
        runCatching {
            val dir = getExternalFilesDir("canvas-capture") ?: return
            if (!dir.exists()) dir.mkdirs()
            val name = "canvas-" + key.replace(Regex("[^A-Za-z0-9]+"), "_").takeLast(48) + ".jpg"
            val file = File(dir, name)
            file.writeBytes(capture.bytes)
            Log.d(TAG_OVERLAY, "sampel canvas disimpan: ${file.absolutePath}")
        }
    }

    private suspend fun evalJs(script: String): String? {
        if (isFinishing || isDestroyed) return null
        return withTimeoutOrNull(JS_EVAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { continuation ->
                    binding.webView.evaluateJavascript(script) { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            }
        }
    }

    private fun unquoteJson(raw: String): String? =
        runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()

    /** Data URL -> byte gambar; validasi bahwa isinya benar-benar gambar. */
    private fun decodeDataUrl(dataUrl: String): CanvasCapture? {
        val comma = dataUrl.indexOf(',')
        if (comma <= 0) return null
        val bytes = runCatching {
            Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG_OVERLAY, "canvas bukan gambar valid")
            return null
        }
        // Jalur native juga wajib melewati validasi kualitas yang sama dengan
        // PixelCopy. Ukuran byte saja tidak membedakan halaman dari canvas kosong.
        val verdict = validateDecoded(bytes, bounds.outWidth, bounds.outHeight)
        if (verdict?.ok != true) {
            Log.w(
                TAG_OVERLAY,
                "canvas native ditolak: ${verdict?.reason ?: "tidak bisa didekode"} " +
                    "${bounds.outWidth}x${bounds.outHeight} ${bytes.size / 1024} KB"
            )
            return null
        }
        Log.d(
            TAG_OVERLAY,
            "canvas terekstrak ${bounds.outWidth}x${bounds.outHeight} ${bytes.size / 1024} KB " +
                "stdDev=${verdict.stdDev} ink=${verdict.inkFraction}"
        )
        return CanvasCapture(bytes, bounds.outWidth, bounds.outHeight)
    }

    /**
     * Decode sampel kecil (bukan gambar penuh) lalu jalankan [CanvasQuality].
     * Bitmap sampel selalu di-recycle; hasil hanya berisi verdict.
     */
    private fun validateDecoded(bytes: ByteArray, width: Int, height: Int): CanvasQuality.Verdict? {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / sample > VALIDATION_MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            .getOrNull() ?: return null
        return try {
            CanvasQuality.validate(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun parseCandidates(json: String): List<DetectedImage> = runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                add(
                    DetectedImage(
                        url = url,
                        width = item.optInt("w"),
                        height = item.optInt("h"),
                        docOrder = item.optInt("docOrder"),
                        sequence = item.optInt("seq"),
                        isCanvas = item.optInt("canvas") == 1
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private inner class ScanBridge {
        @JavascriptInterface
        fun onImages(json: String) {
            val candidates = parseCandidates(json)
            if (candidates.isNotEmpty()) scanSession.onCandidates(candidates)
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        // Hentikan pemindaian/pemicu redraw selama Reader tidak aktif.
        binding.webView.evaluateJavascript(
            "window.__mangalensSetActive&&window.__mangalensSetActive(false)",
            null
        )
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val signature = settingsSignature()
        val changed = lastSettingsSignature != null && lastSettingsSignature != signature
        lastSettingsSignature = signature
        applyTranslationSettings()
        if (changed && !binding.webView.url.isNullOrBlank()) {
            refreshTranslation()
            return
        }
        binding.webView.evaluateJavascript(
            "window.__mangalensSetActive&&window.__mangalensSetActive(true);" +
                "window.__mangalensScan&&window.__mangalensScan();",
            null
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webState = Bundle()
        binding.webView.saveState(webState)
        outState.putBundle(KEY_WEBVIEW_STATE, webState)
    }

    /**
     * Semua resource dilepas di sini. JavaScript interface dilepas sebelum
     * WebView dihancurkan agar tidak ada callback dari halaman yang masih
     * memegang referensi Activity.
     */
    override fun onDestroy() {
        translationManager?.stop()
        translationManager = null
        translatePipeline?.close()
        translatePipeline = null
        imageSheet = null
        scanSession.reset()

        binding.webView.apply {
            stopLoading()
            runCatching { removeJavascriptInterface(BRIDGE_NAME) }
            removeAllViews()
            runCatching { destroy() }
        }

        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "com.mangalens.reader.EXTRA_URL"
        private const val KEY_WEBVIEW_STATE = "reader_webview_state"
        private const val SCAN_SCRIPT = "mangalens_scan.js"
        private const val BRIDGE_NAME = "MangaLensBridge"
        private const val TAG_OVERLAY = "MangaLensOverlay"
        private const val TAG_READER_STATE = "MangaLensReader"

        /** Batas tunggu satu panggilan JavaScript; WebView bisa tidak memanggil balik. */
        private const val JS_EVAL_TIMEOUT_MS = 8_000L

        /** Sisi terpanjang bitmap sampel untuk validasi kualitas. */
        private const val VALIDATION_MAX_DIMENSION = 720

        fun intent(context: Context, url: String): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
