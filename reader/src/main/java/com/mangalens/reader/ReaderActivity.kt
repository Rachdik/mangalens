package com.mangalens.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.mangalens.reader.images.DetectedImage
import com.mangalens.reader.images.ImageListSheet
import com.mangalens.reader.images.ImageScanSession
import com.mangalens.reader.images.ImageStore
import com.mangalens.reader.images.NativeImageFetcher
import com.mangalens.reader.images.PageContext
import com.mangalens.translation.TranslationBackend
import com.mangalens.ui.theme.MangaLensTheme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var scanSession: ImageScanSession
    private var imageSheet: ImageListSheet? = null
    private var translatePipeline: ReadTranslatePipeline? = null
    private var translationManager: ImageTranslationManager? = null

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
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MangaLensTheme.applySystemBars(this)

        scanSession = ImageScanSession(
            scope = lifecycleScope,
            fetcher = NativeImageFetcher(),
            store = ImageStore(File(cacheDir, "source-images")),
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
        }
    }

    private fun setupAutoTranslation() {
        translatePipeline = ReadTranslatePipeline(this)
        translatePipeline?.setTranslationBackend(TranslationBackend.ML_KIT)

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
            scope = lifecycleScope
        )
        translationManager?.start()
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
        val url = result.imageUrl
        if (url.isNullOrBlank() || result.imageWidth <= 0 || result.imageHeight <= 0) {
            // Tidak ada konteks gambar (mis. OCR gagal): jangan tinggalkan overlay lama.
            return
        }

        val webView = binding.webView
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
            val fx = bounds.left.toDouble() / result.imageWidth
            val fy = bounds.top.toDouble() / result.imageHeight
            val fw = bounds.width().toDouble() / result.imageWidth
            val fh = bounds.height().toDouble() / result.imageHeight
            val background = "rgba(" +
                Color.red(bubble.fillColor) + "," +
                Color.green(bubble.fillColor) + "," +
                Color.blue(bubble.fillColor) + ",0.72)"
            val foreground = "#" + String.format(java.util.Locale.US, "%06X", 0xFFFFFF and bubble.textColor)
            "{\"fx\":$fx,\"fy\":$fy,\"fw\":$fw,\"fh\":$fh," +
                "\"bg\":\"$background\",\"fg\":\"$foreground\"," +
                "\"text\":${JSONObject.quote(bubble.targetText)}}"
        }
        val payload = "{\"url\":${JSONObject.quote(url)},\"items\":[$items]}"

        Log.d(
            TAG_OVERLAY,
            "render ${result.bubbles.size} terjemahan (${result.imageWidth}x${result.imageHeight})"
        )

        webView.evaluateJavascript(
            "(function(){var p=$payload;" +
                "return window.__mangalensOverlay?window.__mangalensOverlay(p):-1;})();"
        ) { domCount ->
            Log.d(TAG_OVERLAY, "overlay containers=$domCount")
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

    private fun render(state: ReaderUiState) {
        binding.titleView.text = state.title.ifBlank { state.url }
        binding.progressBar.progress = state.progress
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        val error = state.errorMessage
        binding.errorView.text = error
        binding.errorView.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
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
                        sequence = item.optInt("seq")
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
        super.onPause()
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

        fun intent(context: Context, url: String): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
