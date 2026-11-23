package com.mangalens.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
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
import com.mangalens.core.di.Dependencies
import com.mangalens.reader.databinding.ActivityReaderBinding
import kotlinx.coroutines.launch

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding

    private val viewModel: ReaderViewModel by viewModels {
        ReaderViewModel.factory(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureWebView()

        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }

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

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                viewModel.onPageStarted(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val pageUrl = url.orEmpty()
                val pageTitle = view?.title.orEmpty()
                viewModel.onPageFinished(pageUrl, pageTitle)

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

    private fun render(state: ReaderUiState) {
        binding.titleView.text = state.title.ifBlank { state.url }
        binding.progressBar.progress = state.progress
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        val error = state.errorMessage
        binding.errorView.text = error
        binding.errorView.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
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

    override fun onDestroy() {
        if (isFinishing) {
            binding.webView.stopLoading()
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "com.mangalens.reader.EXTRA_URL"
        private const val KEY_WEBVIEW_STATE = "reader_webview_state"

        fun intent(context: Context, url: String): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
