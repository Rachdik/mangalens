package com.mangalens.app.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * Menerima gambar dari Android Sharesheet / Intent ACTION_SEND dengan MIME image.
 *
 * Gambar di-decode dengan batas dimensi, diproses lewat pipeline OCR ->
 * deteksi balon -> terjemahan yang sama dengan Reader, lalu hasilnya
 * ditampilkan sebagai dialog teks.
 *
 * Kepemilikan bitmap: [pendingBitmap] selalu di-recycle di blok finally,
 * termasuk saat decoding/terjemahan gagal, agar tidak menahan memori.
 */
class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var translationSettings: TranslationSettings
    private var pendingBitmap: Bitmap? = null

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
        when (intent?.action) {
            android.content.Intent.ACTION_SEND -> handleSend(intent)
            android.content.Intent.ACTION_VIEW -> handleView(intent)
            else -> {
                toast("Tidak ada tindakan yang dikenali")
                finish()
            }
        }
    }

    private fun handleSend(intent: android.content.Intent) {
        val type = intent.type
        if (type != null && type.startsWith("image/")) {
            val uri = intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) {
                when (uri.scheme) {
                    "file" -> handleFileUri(uri)
                    "content" -> loadBitmapFromUri(uri)
                    else -> {
                        toast("Tipe URI tidak didukung: ${uri.scheme}")
                        finish()
                    }
                }
                return
            }
        }
        // Fallback: aplikasi lain membagikan teks berisi URL.
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            val url = com.mangalens.core.util.UrlNormalizer.normalize(text) ?: text.trim()
            if (url.isNotBlank()) {
                startActivity(com.mangalens.reader.ReaderActivity.intent(this, url))
                finish()
                return
            }
        }
        toast("Tidak ada gambar atau URL yang dikirim")
        finish()
    }

    private fun handleView(intent: android.content.Intent) {
        val uri = intent.data
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
            startActivity(com.mangalens.reader.ReaderActivity.intent(this, uri.toString()))
            finish()
        } else if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
            if (uri.scheme == "file") handleFileUri(uri) else loadBitmapFromUri(uri)
        } else {
            toast("Tautan tidak didukung")
            finish()
        }
    }

    private fun handleFileUri(uri: Uri) {
        val path = uri.path
        if (path.isNullOrBlank()) {
            toast("File tidak ditemukan")
            finish()
            return
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            toast("File tidak ditemukan")
            finish()
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeBounded(file.absolutePath) }
            if (bitmap == null) {
                toast("Tidak bisa membaca file gambar")
                finish()
            } else {
                processBitmap(bitmap)
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeBounded(uri) }
            if (bitmap == null) {
                toast("Gambar tidak bisa dibaca atau rusak")
                finish()
                return@launch
            }
            processBitmap(bitmap)
        }
    }

    /**
     * Decode dua tahap: baca ukuran dulu, hitung inSampleSize, baru decode.
     * Mencegah OOM saat pengguna membagikan foto beresolusi sangat besar.
     */
    private fun decodeBounded(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()

    private fun decodeBounded(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            open(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun open(uri: Uri): InputStream? = contentResolver.openInputStream(uri)

    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longest = max(width, height)
        while (longest / sample > MAX_DIMENSION) {
            sample *= 2
            if (sample > 8) break
        }
        return sample
    }

    private fun processBitmap(bitmap: Bitmap) {
        if (!Dependencies.isReady()) {
            recycle(bitmap)
            toast("Aplikasi belum siap")
            finish()
            return
        }

        pendingBitmap = bitmap
        binding.floatingButton.text = "Menerjemahkan..."
        binding.floatingButton.isEnabled = false

        lifecycleScope.launch {
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
                        runCatching { translator.close() }
                    }
                }
                showResult(result)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                showError(t.message ?: "tidak diketahui")
            } finally {
                // Bitmap tidak dipakai lagi setelah terjemahan selesai.
                recycle(bitmap)
            }
        }
    }

    private fun showResult(result: PageTranslation) {
        if (result.ocrBlocks == 0) {
            showError(
                "Tidak ada teks yang terbaca. Coba gambar dengan teks yang lebih jelas."
            )
            return
        }
        if (result.bubbles.isEmpty()) {
            showError(
                "Teks terbaca (${result.ocrBlocks} blok) tetapi tidak ada yang bisa " +
                    "diterjemahkan. Mungkin bahasa sumber sudah sama dengan bahasa tujuan."
            )
            return
        }

        binding.floatingButton.text = "${result.bubbles.size} teks diterjemahkan"
        binding.floatingButton.isEnabled = true
        binding.floatingButton.setOnClickListener { finish() }
        showTranslationDialog(result)
    }

    private fun showError(message: String) {
        toast(message)
        binding.floatingButton.text = "Tutup"
        binding.floatingButton.isEnabled = true
        binding.floatingButton.setOnClickListener { finish() }
    }

    private fun showTranslationDialog(result: PageTranslation) {
        val bubbles = result.bubbles
        val message = buildString {
            appendLine("Diterjemahkan ${bubbles.size} teks:")
            appendLine()
            appendLine("Bahasa: ${result.sourceTag} -> ${translationSettings.targetTag}")
            appendLine("OCR: ${result.ocrBlocks} blok, ${result.detectedBubbles} balon")
            appendLine()
            appendLine("-".repeat(40))
            appendLine()
            for (bubble in bubbles) {
                appendLine("Teks asli: ${bubble.sourceText}")
                appendLine("Terjemahan: ${bubble.targetText}")
                appendLine()
                appendLine("-".repeat(40))
                appendLine()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Hasil Terjemahan")
            .setMessage(message)
            .setPositiveButton("Tutup") { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
    }

    private fun recycle(bitmap: Bitmap) {
        if (pendingBitmap === bitmap) pendingBitmap = null
        if (!bitmap.isRecycled) runCatching { bitmap.recycle() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        pendingBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
        pendingBitmap = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_DIMENSION = 2200
    }
}
