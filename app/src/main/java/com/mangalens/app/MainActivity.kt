package com.mangalens.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.mangalens.core.theme.AccentTheme
import com.mangalens.core.theme.ThemeMode
import com.mangalens.core.theme.ThemeStore
import com.mangalens.ui.theme.MangaLensTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.mangalens.app.databinding.ActivityMainBinding
import com.mangalens.core.model.HistoryEntry
import com.mangalens.core.util.UrlNormalizer
import com.mangalens.floating.FloatingTranslate
import com.mangalens.reader.ReaderActivity
import com.mangalens.translation.TranslationBackend
import com.mangalens.translation.TranslationSettings
import kotlinx.coroutines.launch

class MainActivity : ThemedActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var translationSettings: TranslationSettings

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    /** Urutan harus sama dengan `@array/source_languages`. */
    private val sourceLanguages = listOf(
        "auto", "en", "ja", "ko", "zh", "es", "fr", "de", "pt", "ru", "vi", "th", "ms", "ar"
    )

    /** Urutan harus sama dengan `@array/target_languages`. */
    private val targetLanguages = listOf(
        "id", "en", "ja", "ko", "zh", "ms", "th", "vi", "es", "fr", "de", "pt", "ru", "ar"
    )

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            FloatingTranslate.start(this, result.resultCode, data)
            binding.floatingButton.postDelayed({ updateFloatingButton() }, 400)
            toast(getString(R.string.bubble_running))
        } else {
            toast(getString(R.string.screen_capture_denied))
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (FloatingTranslate.canDrawOverlays(this)) {
            requestProjection()
        } else {
            toast(getString(R.string.overlay_permission_needed))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Hasilnya tidak menghalangi: notifikasi hanya informatif.
        launchProjectionConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        translationSettings = TranslationSettings(this)
        setupProviderSpinner()
        setupLanguageSpinners()
        setupAppearance()

        historyAdapter = HistoryAdapter { entry -> openReader(entry.url) }
        binding.historyList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }

        binding.openButton.setOnClickListener {
            openReader(binding.urlInput.text?.toString().orEmpty())
        }

        binding.pasteButton.setOnClickListener {
            pasteFromClipboard()
        }

        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openReader(binding.urlInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        binding.clearHistoryButton.setOnClickListener {
            viewModel.clearHistory()
        }

        binding.floatingButton.setOnClickListener {
            onFloatingClicked()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.history.collect(::renderHistory)
            }
        }

        if (!handleProjectionRequest(intent)) {
            handleIncomingIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleProjectionRequest(intent)) {
            handleIncomingIntent(intent)
        }
    }

    /**
     * Bubble meminta izin rekam layar baru setelah izin lama dicabut sistem
     * (mis. saat berpindah halaman). Service tetap hidup, izin saja yang baru.
     */
    private fun handleProjectionRequest(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(FloatingTranslate.EXTRA_REQUEST_PROJECTION, false) != true) {
            return false
        }
        intent.removeExtra(FloatingTranslate.EXTRA_REQUEST_PROJECTION)

        if (!FloatingTranslate.canDrawOverlays(this)) {
            toast(getString(R.string.overlay_permission_needed))
            return true
        }
        requestProjection()
        return true
    }

    override fun onResume() {
        super.onResume()
        updateFloatingButton()
    }

    private fun renderHistory(items: List<HistoryEntry>) {
        historyAdapter.submitList(items)
        binding.emptyHistory.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.clearHistoryButton.isEnabled = items.isNotEmpty()
    }

    private fun openReader(rawUrl: String) {
        val normalized = UrlNormalizer.normalize(rawUrl)
        if (normalized == null) {
            binding.urlError.text = getString(R.string.invalid_url)
            binding.urlError.visibility = View.VISIBLE
            return
        }
        binding.urlError.visibility = View.GONE
        startActivity(ReaderActivity.intent(this, normalized))
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else {
            null
        }

        if (text.isNullOrBlank()) {
            toast(getString(R.string.paste_empty))
            return
        }

        val url = firstUrl(text) ?: text.trim()
        binding.urlInput.setText(url)
        binding.urlInput.setSelection(binding.urlInput.length())
        binding.urlError.visibility = View.GONE
        toast(getString(R.string.paste_done))
    }

    /** Ambil URL pertama dari teks clipboard (mis. teks berisi tautan). */
    private fun firstUrl(text: String): String? =
        URL_PATTERN.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    private fun handleIncomingIntent(intent: Intent?) {
        val raw = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return

        val url = firstUrl(raw) ?: raw.trim()
        if (url.isEmpty()) return

        binding.urlInput.setText(url)
        val normalized = UrlNormalizer.normalize(url)
        if (normalized == null) {
            binding.urlError.text = getString(R.string.invalid_url)
            binding.urlError.visibility = View.VISIBLE
            return
        }
        binding.urlError.visibility = View.GONE
        startActivity(ReaderActivity.intent(this, normalized))
    }

    private fun onFloatingClicked() {
        if (FloatingTranslate.isRunning) {
            FloatingTranslate.stop(this)
            binding.floatingButton.postDelayed({ updateFloatingButton() }, 400)
            return
        }

        if (translationSettings.backend == TranslationBackend.LLM &&
            !TranslationSettings.llmConfigured
        ) {
            // Bubble tetap boleh dinyalakan; pesan ini menjelaskan kenapa
            // terjemahan akan gagal sampai kunci API diisi.
            toast(getString(R.string.llm_missing_key)) 
        }

        if (!FloatingTranslate.canDrawOverlays(this)) {
            toast(getString(R.string.overlay_permission_needed))
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        requestProjection()
    }

    private fun requestProjection() {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun setupProviderSpinner() {
        binding.providerSpinner.setSelection(translationSettings.backend.ordinal)
        binding.providerSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    translationSettings.backend = TranslationBackend.values()
                        .getOrElse(position) { TranslationBackend.ML_KIT }
                    updateProviderHint()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        updateProviderHint()
    }

    private fun setupLanguageSpinners() {
        binding.scriptSpinner.setSelection(
            sourceLanguages.indexOf(translationSettings.sourceTag).coerceAtLeast(0)
        )
        binding.scriptSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    translationSettings.sourceTag =
                        sourceLanguages.getOrElse(position) { "auto" }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        binding.targetSpinner.setSelection(
            targetLanguages.indexOf(translationSettings.targetTag).coerceAtLeast(0)
        )
        binding.targetSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    translationSettings.targetTag =
                        targetLanguages.getOrElse(position) { "id" }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun updateProviderHint() {
        binding.providerHint.text = when (translationSettings.backend) {
            TranslationBackend.LLM ->
                if (TranslationSettings.llmConfigured) {
                    getString(R.string.provider_hint_llm, TranslationSettings.llmModel)
                } else {
                    getString(R.string.provider_hint_llm_missing)
                }

            TranslationBackend.ML_KIT -> getString(R.string.provider_hint_mlkit)
            TranslationBackend.MY_MEMORY -> getString(R.string.provider_hint_mymemory)
        }
    }

    private fun updateFloatingButton() {
        binding.floatingButton.setText(
            if (FloatingTranslate.isRunning) R.string.stop_bubble else R.string.start_bubble
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")
    }

    // ---------------------------------------------------------------------
    // Tampilan: watermark versi + pemilihan warna/mode
    // ---------------------------------------------------------------------

    private fun setupAppearance() {
        // Versi diambil dari BuildConfig (versionName Gradle) — tidak pernah
        // ditulis manual, jadi watermark selalu sama dengan APK yang berjalan.
        binding.watermark.text = getString(R.string.version_watermark, BuildConfig.VERSION_NAME)
        renderAppearance()
    }

    private fun renderAppearance() {
        val accent = ThemeStore.accent
        val mode = ThemeStore.mode
        binding.themeSummary.text = getString(R.string.appearance_summary, accent.label, mode.label)

        binding.accentRow.removeAllViews()
        for (option in AccentTheme.values()) {
            binding.accentRow.addView(buildAccentDot(option, option == accent))
        }

        binding.modeRow.removeAllViews()
        for (option in ThemeMode.values()) {
            binding.modeRow.addView(buildModeChip(option, option == mode))
        }
    }

    /** Swatch bulat sebagai pratinjau warna sebelum dipilih. */
    private fun buildAccentDot(option: AccentTheme, selected: Boolean): View {
        val size = (36 * resources.displayMetrics.density).toInt()
        val margin = (5 * resources.displayMetrics.density).toInt()
        val dot = View(this)
        val params = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = margin
            marginStart = margin
        }
        dot.layoutParams = params
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(option.previewColor)
            if (selected) {
                setStroke((3 * resources.displayMetrics.density).toInt(), 0xFF171725.toInt())
            } else {
                setStroke((1 * resources.displayMetrics.density).toInt(), 0x33000000)
            }
        }
        dot.background = shape
        dot.contentDescription = option.label
        dot.isClickable = true
        dot.isFocusable = true
        dot.setOnClickListener {
            MangaLensTheme.select(option, ThemeStore.mode)
            // recreate() memakai tema baru karena ThemedActivity menerapkannya
            // sebelum layout di-inflate.
            recreate()
        }
        return dot
    }

    private fun buildModeChip(option: ThemeMode, selected: Boolean): TextView {
        val density = resources.displayMetrics.density
        val chip = TextView(this)
        chip.text = option.label
        chip.textSize = 13f
        chip.gravity = android.view.Gravity.CENTER
        val horizontal = (14 * density).toInt()
        val vertical = (8 * density).toInt()
        chip.setPadding(horizontal, vertical, horizontal, vertical)
        chip.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * density).toInt() }
        val shape = GradientDrawable().apply {
            cornerRadius = 20 * density
            if (selected) {
                setColor(resolveThemeColor(androidx.appcompat.R.attr.colorPrimary))
                setStroke((1 * density).toInt(), resolveThemeColor(androidx.appcompat.R.attr.colorPrimary))
            } else {
                setColor(resolveThemeColor(com.mangalens.ui.R.attr.colorPrimaryLight))
                setStroke((1 * density).toInt(), resolveThemeColor(android.R.attr.textColorSecondary))
            }
        }
        chip.background = shape
        chip.setTextColor(
            if (selected) 0xFFFFFFFF.toInt()
            else resolveThemeColor(android.R.attr.textColorPrimary)
        )
        chip.isClickable = true
        chip.isFocusable = true
        chip.setOnClickListener {
            MangaLensTheme.select(ThemeStore.accent, option)
            recreate()
        }
        return chip
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typed = obtainStyledAttributes(intArrayOf(attr))
        val color = try {
            typed.getColor(0, 0xFF000000.toInt())
        } finally {
            typed.recycle()
        }
        return color
    }
}
