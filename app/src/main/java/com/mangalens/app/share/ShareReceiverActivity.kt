package com.mangalens.app.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mangalens.app.MainViewModel
import com.mangalens.app.databinding.ActivityMainBinding
import com.mangalens.core.di.Dependencies
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.PageTranslation
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.LlmTranslationProvider
import com.mangalens.translation.MlKitTranslationProvider
import com.mangalens.translation.MyMemoryTranslationProvider
import com.mangalens.translation.TranslationBackend
import com.mangalens.translation.TranslationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Menerima gambar yang dibagikan lewat Android Sharesheet / Intent ACTION_SEND.
 * Gambar di-extract dari URI, lalu diproses lewat pipeline OCR->translation yang sama.
 * Hasil terjemahan ditampilkan sebagai dialog teks.
 */
class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private lateinit var translationSettings: TranslationSettings
    private var pendingBitmap: Bitmap? = null

    private fun requestImageAccess(uri: Uri) {
        loadBitmapFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        translationSettings = TranslationSettings(this)
        setupUi()
        handleIncomingIntent()
    }

    private fun setupUi() {
        binding.openButton.setOnClickListener { finish() }
        binding.urlInput.visibility = android.view.View.GONE
        binding.pasteButton.visibility = android.view.View.GONE
        binding.clearHistoryButton.visibility = android.view.View.GONE
        binding.historyList.visibility = android.view.View.GONE
        binding.emptyHistory.visibility = android.view.View.GONE
        binding.urlError.visibility = android.view.View.GONE
        binding.floatingButton.visibility = android.view.View.VISIBLE
        binding.floatingButton.text = "Menunggu gambar..."
        binding.floatingButton.isEnabled = false
        binding.floatingButton.setOnClickListener { finish() }
        binding.providerSpinner.visibility = android.view.View.GONE
        binding.scriptSpinner.visibility = android.view.View.GONE
        binding.targetSpinner.visibility = android.view.View.GONE
        binding.providerHint.visibility = android.view.View.GONE
    }

    private fun handleIncomingIntent() {
        when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                val type = intent.type
                if (type != null && type.startsWith("image/")) {
                    val uri = intent.getParcelableExtra(
                        android.content.Intent.EXTRA_STREAM,
                        Uri::class.java
                    )
                    if (uri != null) {
                        when (uri.scheme) {
                            "file" -> handleFileUri(uri)
                            "content" -> handleContentUri(uri)
                            else -> {
                                Toast.makeText(
                                    this,
                                    "Tipe URI tidak didukung: " + uri.scheme,
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        }
                        return
                    }
                }
                val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    val url = firstUrl(text) ?: text.trim()
                    if (url.isNotBlank()) {
                        startActivity(
                            com.mangalens.reader.ReaderActivity.intent(this, url)
                        )
                        finish()
                        return
                    }
                }
                Toast.makeText(
                    this,
                    "Tidak ada gambar atau URL yang dikirim",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
            android.content.Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
                    startActivity(
                        com.mangalens.reader.ReaderActivity.intent(
                            this,
                            uri.toString()
                        )
                    )
                    finish()
                } else {
                    Toast.makeText(
                        this,
                        "Tautan tidak didukung",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
            else -> {
                Toast.makeText(
                    this,
                    "Tidak ada tindakan yang dikenali",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun handleFileUri(uri: Uri) {
        val file = File(uri.path ?: return)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(this, "File tidak ditemukan", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: run {
                Toast.makeText(this, "Tidak bisa membaca file gambar", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        processBitmap(bitmap)
    }

    private fun handleContentUri(uri: Uri) {
        requestImageAccess(uri)
    }

    private fun loadBitmapFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val stream: InputStream? = contentResolver.openInputStream(uri)
                if (stream == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            "Tidak bisa membuka gambar",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    return@launch
                }
                val bitmap = stream.use { BitmapFactory.decodeStream(it) }
                if (bitmap == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            "Gambar rusak atau tidak didukung",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    return@launch
                }
                processBitmap(bitmap)
            } catch (e: SecurityException) {
                runOnUiThread {
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "Izin akses ditolak",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "Gagal membaca gambar: " + e.message,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun processBitmap(bitmap: Bitmap) {
        if (!Dependencies.isReady()) {
            Toast.makeText(this, "Aplikasi belum siap", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pendingBitmap = bitmap
        binding.floatingButton.text = "Menerjemahkan..."
        binding.floatingButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = withContext(Dispatchers.Default) {
                    val translator = MangaPageTranslator(
                        ocr = MlKitOcrEngine(),
                        providerFactory = { source, target ->
                            when (translationSettings.backend) {
                                TranslationBackend.ML_KIT -> MlKitTranslationProvider(source, target)
                                TranslationBackend.LLM -> LlmTranslationProvider(source, target)
                                TranslationBackend.MY_MEMORY -> MyMemoryTranslationProvider(source, target)
                            }
                        }
                    )
                    try {
                        translator.translate(
                            bitmap = bitmap,
                            script = LanguageScript.AUTO,
                            sourceTag = translationSettings.sourceTag,
                            targetTag = translationSettings.targetTag
                        )
                    } finally {
                        translator.close()
                    }
                }
                launch(Dispatchers.Main) {
                    showResult(result)
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@ShareReceiverActivity,
                        "Gagal menerjemahkan: " + (e.message ?: "tidak diketahui"),
                        Toast.LENGTH_LONG
                    ).show()
                    binding.floatingButton.text = "Coba lagi"
                    binding.floatingButton.isEnabled = true
                }
            }
        }
    }

    private fun showResult(result: PageTranslation) {
        pendingBitmap?.recycle()
        pendingBitmap = null
        if (result.ocrBlocks == 0) {
            Toast.makeText(
                this,
                "Tidak ada teks yang terbaca di gambar. Coba gambar lain dengan teks yang lebih jelas.",
                Toast.LENGTH_LONG
            ).show()
            binding.floatingButton.text = "Coba lagi"
            binding.floatingButton.isEnabled = true
            return
        }
        if (result.bubbles.isEmpty()) {
            Toast.makeText(
                this,
                "Teks terbaca (" + result.ocrBlocks.toString() + " blok) tetapi tidak ada yang bisa diterjemahkan. Mungkin bahasa sumber sudah Indonesia, atau model belum siap.",
                Toast.LENGTH_LONG
            ).show()
            binding.floatingButton.text = "Coba lagi"
            binding.floatingButton.isEnabled = true
            return
        }
        binding.floatingButton.text = result.bubbles.size.toString() + " teks diterjemahkan"
        binding.floatingButton.isEnabled = true
        binding.floatingButton.setOnClickListener { finish() }
        showTranslationDialog(result)
    }

    private fun showTranslationDialog(result: PageTranslation) {
        val bubbles = result.bubbles
        val message = buildString {
            appendLine("Diterjemahkan " + bubbles.size.toString() + " teks:")
            appendLine()
            appendLine("Bahasa: " + result.sourceTag + " -> " + translationSettings.targetTag)
            appendLine("OCR: " + result.ocrBlocks.toString() + " blok, " + result.detectedBubbles.toString() + " balon")
            appendLine()
            appendLine("-".repeat(40))
            appendLine()
            for (bubble in bubbles) {
                appendLine("Teks asli: " + bubble.sourceText)
                appendLine("Terjemahan: " + bubble.targetText)
                appendLine()
                appendLine("-".repeat(40))
                appendLine()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Hasil Terjemahan")
            .setMessage(message)
            .setPositiveButton("Tutup") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Kembali ke MangaLens") { dialog, _ -> dialog.dismiss(); finish() }
            .setCancelable(true)
            .show()
    }

    private fun firstUrl(text: String): String? =
        com.mangalens.core.util.UrlNormalizer.normalize(text)

    override fun onDestroy() {
        pendingBitmap?.recycle()
        pendingBitmap = null
        super.onDestroy()
    }
}
