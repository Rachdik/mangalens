package com.mangalens.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.mangalens.core.theme.AccentTheme
import com.mangalens.core.theme.ThemeMode
import com.mangalens.core.theme.ThemeStore
import com.mangalens.floating.FloatingTranslate
import com.mangalens.translation.BackgroundColor
import com.mangalens.translation.LlmConfig
import com.mangalens.translation.LlmPreset
import com.mangalens.translation.GeminiTranslationProvider
import com.mangalens.translation.LlmTranslationProvider
import com.mangalens.translation.OverlayOpacity
import com.mangalens.translation.TextColor
import com.mangalens.translation.TextSize
import com.mangalens.translation.TranslationBackend
import com.mangalens.translation.TranslationRequest
import com.mangalens.translation.TranslationSettings
import com.mangalens.translation.TranslateResult
import com.mangalens.ui.theme.MangaLensTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Pengaturan terpusat MangaLens. */
class SettingsActivity : ThemedActivity() {

    private lateinit var translationSettings: TranslationSettings
    private lateinit var accentRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var previewCard: LinearLayout
    private lateinit var previewTitle: TextView

    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var backendSpinner: Spinner
    private lateinit var fallbackSwitch: Switch
    private lateinit var timeoutValue: TextView
    private lateinit var timeoutSeek: SeekBar

    private lateinit var llmPresetSpinner: Spinner
    private lateinit var llmBaseInput: EditText
    private lateinit var llmModelInput: EditText
    private lateinit var llmKeyInput: EditText
    private lateinit var llmStatus: TextView
    private lateinit var showKeyButton: Button

    private var keyVisible = false
    private var presetChanging = false

    private val sourceLanguages = listOf(
        "auto", "en", "ja", "ko", "zh", "es", "fr", "de", "pt", "ru", "vi", "th", "ms", "ar"
    )
    private val targetLanguages = listOf(
        "id", "en", "ja", "ko", "zh", "ms", "th", "vi", "es", "fr", "de", "pt", "ru", "ar"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translationSettings = TranslationSettings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(com.mangalens.ui.R.color.mangalens_window))
        }

        root.addView(buildHeader())
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        buildAppearance(content)
        buildAdvancedColors(content)
        buildTranslation(content)
        buildLlm(content)
        buildReaderAndFloating(content)
        buildAbout(content)
    }

    override fun onPause() {
        super.onPause()
        if (FloatingTranslate.isRunning) {
            val intent = Intent()
                .setClassName(packageName, "com.mangalens.floating.FloatingTranslateService")
                .setAction(FloatingTranslate.ACTION_RESHOW)
            runCatching { startService(intent) }
        }
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(com.mangalens.ui.R.color.mangalens_card))
            setPadding(dp(8), dp(8), dp(16), dp(8))
            elevation = dp(2).toFloat()
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(attr(androidx.appcompat.R.attr.colorPrimary))
            setPadding(dp(12), 0, dp(12), dp(4))
            isClickable = true
            contentDescription = "Kembali"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Pengaturan"
            textSize = 20f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_on_surface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bar.addView(back)
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        return bar
    }

    private fun buildAppearance(parent: LinearLayout) {
        val card = section(parent, "Tampilan", "Tema dan warna aksen")
        previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        previewTitle = TextView(this).apply {
            text = "Pratinjau tema"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val previewBody = TextView(this).apply {
            text = "Warna ini diterapkan ke layar utama, Reader, dialog, dan tombol."
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        previewCard.addView(previewTitle)
        previewCard.addView(previewBody)
        card.addView(previewCard, LinearLayout.LayoutParams(-1, -2))

        card.addView(label("Mode"))
        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        card.addView(modeRow)

        card.addView(label("Warna aksen"))
        accentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        card.addView(accentRow)

        val colorTitle = TextView(this).apply {
            text = "Warna aksen kustom"
            textSize = 13f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_muted))
            setPadding(0, dp(10), 0, dp(2))
        }
        card.addView(colorTitle)
        val colorPreview = View(this).apply {
            setBackgroundColor(com.mangalens.core.theme.ThemeStore.accentColor())
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        colorRow.addView(colorPreview)
        colorRow.addView(actionButton("Pilih warna aksen") {
            showColorPicker(colorPreview)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        card.addView(colorRow)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("Reset ke default") {
            ThemeStore.setAccent(AccentTheme.PURPLE)
            ThemeStore.setMode(ThemeMode.SYSTEM)
            MangaLensTheme.select(AccentTheme.PURPLE, ThemeMode.SYSTEM)
            recreate()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        renderAppearance()
    }

    private fun renderAppearance() {
        previewCard.background = rounded(
            ThemeStore.accent.previewColor,
            dp(14),
            stroke = 0x22000000
        )
        previewTitle.setTextColor(Color.WHITE)

        modeRow.removeAllViews()
        for (mode in ThemeMode.entries) {
            modeRow.addView(chip(mode.label, mode == ThemeStore.mode) {
                MangaLensTheme.select(ThemeStore.accent, mode)
                recreate()
            })
        }

        accentRow.removeAllViews()
        for (accent in AccentTheme.entries) {
            accentRow.addView(swatch(accent))
        }
    }

    private fun swatch(accent: AccentTheme): View {
        val size = dp(34)
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(7)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent.previewColor)
                setStroke(
                    if (accent == ThemeStore.accent) dp(3) else dp(1),
                    if (accent == ThemeStore.accent) Color.WHITE else 0x33000000
                )
            }
            contentDescription = accent.label
            isClickable = true
            setOnClickListener {
                MangaLensTheme.select(accent, ThemeStore.mode)
                recreate()
            }
        }
    }

    private fun buildAdvancedColors(parent: LinearLayout) {
        SettingsActivityAdvancedColors.build(this, parent)
    }

    private fun buildTranslation(parent: LinearLayout) {
        val card = section(parent, "Terjemahan", "Bahasa, provider, dan fallback")
        card.addView(label("Bahasa sumber"))
        sourceSpinner = spinner(sourceLanguages.map { languageLabel(it) })
        sourceSpinner.setSelection(sourceLanguages.indexOf(translationSettings.sourceTag).coerceAtLeast(0))
        sourceSpinner.onItemSelectedListener = simpleSelect { position ->
            translationSettings.sourceTag = sourceLanguages.getOrElse(position) { "auto" }
        }
        card.addView(sourceSpinner)

        card.addView(label("Bahasa target"))
        targetSpinner = spinner(targetLanguages.map { languageLabel(it) })
        targetSpinner.setSelection(targetLanguages.indexOf(translationSettings.targetTag).coerceAtLeast(0))
        targetSpinner.onItemSelectedListener = simpleSelect { position ->
            translationSettings.targetTag = targetLanguages.getOrElse(position) { "id" }
        }
        card.addView(targetSpinner)

        card.addView(label("Provider terjemahan"))
        backendSpinner = spinner(
            listOf("ML Kit on-device", "LLM (API key sendiri)", "Google Gemini", "MyMemory (gratis)")
        )
        backendSpinner.setSelection(translationSettings.backend.ordinal)
        backendSpinner.onItemSelectedListener = simpleSelect { position ->
            translationSettings.backend = TranslationBackend.entries
                .getOrElse(position) { TranslationBackend.ML_KIT }
        }
        card.addView(backendSpinner)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = "Fallback otomatis ke ML Kit"
            textSize = 14f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_on_surface))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fallbackSwitch = Switch(this).apply {
            isChecked = translationSettings.autoFallback
            setOnCheckedChangeListener { _, checked -> translationSettings.autoFallback = checked }
        }
        row.addView(fallbackSwitch)
        card.addView(row)

        val timeoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeoutRow.addView(label("Batas waktu"), LinearLayout.LayoutParams(0, -2, 1f))
        timeoutValue = TextView(this).apply {
            text = "${translationSettings.translationTimeoutSeconds} detik"
            textSize = 13f
            setTextColor(attr(androidx.appcompat.R.attr.colorPrimary))
        }
        timeoutRow.addView(timeoutValue)
        card.addView(timeoutRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        timeoutSeek = SeekBar(this).apply {
            max = 115
            progress = translationSettings.translationTimeoutSeconds - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val seconds = value + 5
                    timeoutValue.text = "$seconds detik"
                    if (fromUser) translationSettings.translationTimeoutSeconds = seconds
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        card.addView(timeoutSeek)
    }

    private fun buildLlm(parent: LinearLayout) {
        val card = section(parent, "LLM & API", "Kunci milik Anda, disimpan terenkripsi")
        card.addView(note("Gunakan API key sendiri. Pemakaian dapat dikenai biaya oleh penyedia; " +
            "MangaLens tidak menjamin kuota gratis dan tidak pernah mengirim kunci " +
            "ke server MangaLens."))

        llmPresetSpinner = spinner(LlmPreset.entries.map { it.label })
        val preset = LlmPreset.fromKey(translationSettings.llmProviderKey)
        presetChanging = true
        llmPresetSpinner.setSelection(LlmPreset.entries.indexOf(preset))
        presetChanging = false
        llmPresetSpinner.onItemSelectedListener = simpleSelect { position ->
            if (presetChanging) return@simpleSelect
            val selected = LlmPreset.entries.getOrElse(position) { LlmPreset.OPENAI }
            translationSettings.llmProviderKey = selected.key
            if (selected == LlmPreset.GEMINI) {
                translationSettings.backend = TranslationBackend.GEMINI
                backendSpinner.setSelection(TranslationBackend.GEMINI.ordinal)
            } else if (selected != LlmPreset.CUSTOM) {
                translationSettings.backend = TranslationBackend.LLM
                backendSpinner.setSelection(TranslationBackend.LLM.ordinal)
            }
            if (selected != LlmPreset.CUSTOM) {
                translationSettings.llmBaseUrl = selected.baseUrl
                translationSettings.llmModel = selected.defaultModel
                llmBaseInput.setText(selected.baseUrl)
                llmModelInput.setText(selected.defaultModel)
            }
        }
        card.addView(label("Provider"))
        card.addView(llmPresetSpinner)

        card.addView(label("Base URL"))
        llmBaseInput = input(translationSettings.llmBaseUrl, "https://api.provider.com/v1")
        llmBaseInput.inputType = InputType.TYPE_TEXT_VARIATION_URI
        card.addView(llmBaseInput)

        card.addView(label("Model"))
        llmModelInput = input(translationSettings.llmModel, "nama-model")
        card.addView(llmModelInput)

        card.addView(label("API key"))
        llmKeyInput = input("", "sk-...").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        card.addView(llmKeyInput)

        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        showKeyButton = actionButton("Tampilkan") {
            keyVisible = !keyVisible
            llmKeyInput.inputType = if (keyVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            llmKeyInput.setSelection(llmKeyInput.text.length)
            showKeyButton.text = if (keyVisible) "Sembunyikan" else "Tampilkan"
        }
        keyRow.addView(showKeyButton, LinearLayout.LayoutParams(0, -2, 1f))
        keyRow.addView(actionButton("Simpan") { saveKey() }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(keyRow)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(actionButton("Tes koneksi") { testConnection() }, LinearLayout.LayoutParams(0, -2, 1f))
        actionRow.addView(actionButton("Hapus kunci") {
            translationSettings.llmApiKey = ""
            llmKeyInput.setText("")
            setLlmStatus("Kunci API dihapus dari perangkat.", false)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actionRow)

        llmStatus = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
            setTextColor(color(com.mangalens.ui.R.color.mangalens_muted))
        }
        card.addView(llmStatus)

        val fetchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fetchRow.addView(actionButton("Ambil daftar model") { fetchModels() }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(fetchRow)

        val usageTitle = TextView(this).apply {
            text = "Penggunaan token (agregat lokal)"
            textSize = 13f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_muted))
            setPadding(0, dp(10), 0, dp(2))
        }
        card.addView(usageTitle)
        val usageText = TextView(this).apply {
            text = com.mangalens.translation.TokenUsageStore.summary(this@SettingsActivity)
            textSize = 12f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_on_surface))
        }
        card.addView(usageText)
        card.addView(actionButton("Reset penggunaan") {
            com.mangalens.translation.TokenUsageStore.reset(this@SettingsActivity)
            usageText.text = com.mangalens.translation.TokenUsageStore.summary(this@SettingsActivity)
        })
        card.addView(note("Estimasi biaya resmi tidak dihitung; harga dan kuota mengikuti provider."))

        val existing = translationSettings.llmApiKey
        if (existing.isNotBlank()) {
            setLlmStatus("Kunci tersimpan (${existing.take(3)}…${existing.takeLast(2)}).", false)
        } else {
            setLlmStatus("Belum ada kunci. Provider LLM tidak akan dipakai sampai diisi.", false)
        }
    }

    private fun saveKey() {
        val key = llmKeyInput.text.toString().trim()
        if (key.isBlank()) { setLlmStatus("Kunci kosong; tidak disimpan.", true); return }
        translationSettings.llmApiKey = key
        translationSettings.llmBaseUrl = llmBaseInput.text.toString()
        translationSettings.llmModel = llmModelInput.text.toString()
        llmKeyInput.setText("")
        setLlmStatus("Kunci disimpan terenkripsi di perangkat (tidak masuk APK/Logcat).", false)
    }

    private fun testConnection() {
        val key = if (llmKeyInput.text.isNotBlank()) llmKeyInput.text.toString().trim() else translationSettings.llmApiKey
        if (key.isBlank()) { setLlmStatus("Isi API key terlebih dahulu.", true); return }
        val base = llmBaseInput.text.toString().trim()
        val model = llmModelInput.text.toString().trim()
        if (base.isBlank() || model.isBlank()) { setLlmStatus("Base URL dan model wajib diisi.", true); return }
        setLlmStatus("Menghubungi provider…", false)
        lifecycleScope.launch {
            val config = LlmConfig(apiKey = key, baseUrl = base, model = model, timeoutMs = 20_000)
            val result = withContext(Dispatchers.IO) {
                val provider = LlmTranslationProvider("en", "id", config.apiKey, config.baseUrl, config.model, config.timeoutMs)
                runCatching { provider.translate(listOf(TranslationRequest(id = "test", text = "Hello, this is a test."))) }
                    .getOrElse { TranslateResult.Failure(it.message ?: "Permintaan gagal.") }
            }
            when (result) {
                is TranslateResult.Success -> {
                    val text = result.texts["test"].orEmpty()
                    setLlmStatus(if (text.isBlank()) "Terhubung, tetapi provider mengembalikan teks kosong." else "Berhasil terhubung. Contoh hasil: \"$text\"", text.isBlank())
                }
                is TranslateResult.Failure -> setLlmStatus(sanitize(result.message), true)
            }
        }
    }

    private fun fetchModels() {
        val key = if (llmKeyInput.text.isNotBlank()) llmKeyInput.text.toString().trim() else translationSettings.llmApiKey
        if (key.isBlank()) { setLlmStatus("Isi API key terlebih dahulu.", true); return }
        val base = llmBaseInput.text.toString().trim()
        val model = llmModelInput.text.toString().trim()
        setLlmStatus("Mengambil daftar model…", false)
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) {
                when (translationSettings.backend) {
                    TranslationBackend.GEMINI -> GeminiTranslationProvider("en", "id", key, model.ifBlank { "gemini-1.5-flash" }, 20_000).fetchModels()
                    TranslationBackend.LLM -> LlmTranslationProvider("en", "id", key, base, model, 20_000).fetchModels()
                    else -> emptyList()
                }
            }
            if (models.isEmpty()) setLlmStatus("Gagal mengambil model atau provider tidak mendukung.", true)
            else {
                setLlmStatus("${models.size} model ditemukan.", false)
                AlertDialog.Builder(this@SettingsActivity).setTitle("Pilih model").setItems(models.toTypedArray()) { _, which -> llmModelInput.setText(models[which]) }.show()
            }
        }
    }

    private fun showColorPicker(preview: View) {
        val initial = com.mangalens.core.theme.ThemeStore.accentColor()
        val r = intArrayOf(Color.red(initial))
        val g = intArrayOf(Color.green(initial))
        val b = intArrayOf(Color.blue(initial))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val hexView = TextView(this).apply {
            text = String.format("#%02X%02X%02X", r[0], g[0], b[0])
            textSize = 16f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_on_surface))
        }
        layout.addView(hexView)
        fun makeSeek(label: String, arr: IntArray): SeekBar {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, 0, dp(8), 0) })
            return SeekBar(this).apply {
                max = 255; progress = arr[0]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                        arr[0] = progress
                        hexView.text = String.format("#%02X%02X%02X", r[0], g[0], b[0])
                        preview.setBackgroundColor(Color.rgb(r[0], g[0], b[0]))
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }.apply { row.addView(this, LinearLayout.LayoutParams(0, -2, 1f)); layout.addView(row) }
        }
        makeSeek("R", r); makeSeek("G", g); makeSeek("B", b)
        AlertDialog.Builder(this).setTitle("Pilih warna aksen").setView(layout)
            .setPositiveButton("Terapkan") { _, _ ->
                val color = Color.rgb(r[0], g[0], b[0])
                com.mangalens.core.theme.ThemeStore.setCustomAccent(color)
                preview.setBackgroundColor(color)
                recreate()
            }.setNegativeButton("Batal", null).show()
    }

    private fun sanitize(message: String): String {
        var safe = message.replace(Regex("(?i)sk-[^\\s\\\"']{6,}"), "[kunci disembunyikan]")
        val key = translationSettings.llmApiKey
        if (key.length >= 6) safe = safe.replace(key, "[kunci disembunyikan]")
        return "Gagal: ${safe.take(240)}"
    }

    private fun setLlmStatus(text: String, isError: Boolean) {
        llmStatus.text = text
        llmStatus.setTextColor(if (isError) color(com.mangalens.ui.R.color.mangalens_error) else color(com.mangalens.ui.R.color.mangalens_muted))
    }

    private fun buildReaderAndFloating(parent: LinearLayout) {
        val reader = section(parent, "Reader", "Tampilan overlay terjemahan")
        reader.addView(label("Opacity latar"))
        val opacitySpinner = spinner(OverlayOpacity.entries.map { it.name.lowercase(Locale.US) })
        opacitySpinner.setSelection(OverlayOpacity.entries.indexOf(translationSettings.overlayOpacity))
        opacitySpinner.onItemSelectedListener = simpleSelect { position ->
            translationSettings.overlayOpacity = OverlayOpacity.entries.getOrElse(position) { OverlayOpacity.HALF }
        }
        reader.addView(opacitySpinner)

        reader.addView(label("Ukuran teks"))
        val sizeSpinner = spinner(TextSize.entries.map { it.name.lowercase(Locale.US) })
        sizeSpinner.setSelection(TextSize.entries.indexOf(translationSettings.textSize))
        sizeSpinner.onItemSelectedListener = simpleSelect { position ->
            translationSettings.textSize = TextSize.entries.getOrElse(position) { TextSize.NORMAL }
        }
        reader.addView(sizeSpinner)

        reader.addView(label("Warna teks"))
        val textColorSpinner = spinner(TextColor.entries.map { it.name.lowercase(Locale.US) })
        textColorSpinner.setSelection(TextColor.entries.indexOf(translationSettings.textColor))
        textColorSpinner.onItemSelectedListener = simpleSelect { position ->
            translationSettings.textColor = TextColor.entries.getOrElse(position) { TextColor.AUTO }
        }
        reader.addView(textColorSpinner)

        reader.addView(label("Warna latar"))
        val bgSpinner = spinner(BackgroundColor.entries.map { it.name.lowercase(Locale.US) })
        bgSpinner.setSelection(BackgroundColor.entries.indexOf(translationSettings.backgroundColor))
        bgSpinner.onItemSelectedListener = simpleSelect { position ->
            translationSettings.backgroundColor = BackgroundColor.entries.getOrElse(position) { BackgroundColor.AUTO }
        }
        reader.addView(bgSpinner)

        val inpaintRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        inpaintRow.addView(TextView(this).apply {
            text = "Gunakan inpainting (hapus teks asli)"
            textSize = 14f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_on_surface))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val inpaintSwitch = Switch(this).apply {
            isChecked = translationSettings.inpaintingEnabled
            setOnCheckedChangeListener { _, checked -> translationSettings.inpaintingEnabled = checked }
        }
        inpaintRow.addView(inpaintSwitch)
        reader.addView(inpaintRow)

        val floating = section(parent, "Floating Apps", "Tombol refresh & izin")
        floating.addView(note("Bubble memiliki tombol refresh bulat di sampingnya. Refresh selalu " +
            "mengambil screenshot baru, membatalkan proses lama, dan tidak memakai " +
            "cache OCR layar sebelumnya."))
        floating.addView(actionButton("Buka izin tampil di atas aplikasi") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
    }

    private fun buildAbout(parent: LinearLayout) {
        val about = section(parent, "Tentang", "Versi dan informasi build")
        about.addView(note("MangaLens ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
            "Package: $packageName\n\n" +
            "Komponen open source: AndroidX/AppCompat (Apache 2.0), ML Kit " +
            "(Google Play Services), OkHttp/Coil (Apache 2.0), Kotlin (Apache 2.0)."))
        about.addView(actionButton("Kembali ke beranda") { finish() })
    }

    // ------------------------------------------------------------- helpers

    private fun section(parent: LinearLayout, title: String, subtitle: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(com.mangalens.ui.R.color.mangalens_card), dp(16), stroke = color(com.mangalens.ui.R.color.mangalens_outline))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 17f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_on_surface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = subtitle; textSize = 12f
            setTextColor(color(com.mangalens.ui.R.color.mangalens_muted))
            setPadding(0, dp(2), 0, dp(8))
        })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return card
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 13f
        setTextColor(color(com.mangalens.ui.R.color.mangalens_muted))
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 12f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(color(com.mangalens.ui.R.color.mangalens_muted))
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        background = rounded(if (selected) attr(androidx.appcompat.R.attr.colorPrimary) else color(com.mangalens.ui.R.color.mangalens_input), dp(20), stroke = 0x22000000)
        setTextColor(if (selected) Color.WHITE else color(com.mangalens.ui.R.color.mangalens_on_surface))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Dapat diakses oleh AdvancedColorSettingsBuilder. */
    internal fun actionButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        setTextColor(attr(androidx.appcompat.R.attr.colorPrimary))
        background = rounded(color(com.mangalens.ui.R.color.mangalens_input), dp(12), stroke = 0x22000000)
        setOnClickListener { onClick() }
    }

    private fun input(value: String, hint: String): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 13f
        setSingleLine()
        setTextColor(color(com.mangalens.ui.R.color.mangalens_on_surface))
        setHintTextColor(color(com.mangalens.ui.R.color.mangalens_muted))
        background = rounded(color(com.mangalens.ui.R.color.mangalens_input), dp(10), stroke = color(com.mangalens.ui.R.color.mangalens_outline))
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun simpleSelect(onSelected: (Int) -> Unit): AdapterView.OnItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun languageLabel(tag: String): String = when (tag) {
        "auto" -> "Otomatis"; "id" -> "Indonesia"; "en" -> "Inggris"; "ja" -> "Jepang"
        "ko" -> "Korea"; "zh" -> "Tionghoa"; "es" -> "Spanyol"; "fr" -> "Prancis"
        "de" -> "Jerman"; "pt" -> "Portugis"; "ru" -> "Rusia"; "vi" -> "Vietnam"
        "th" -> "Thailand"; "ms" -> "Melayu"; "ar" -> "Arab"; else -> tag
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke)
    }

    private fun color(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)

    private fun attr(attr: Int): Int {
        val typed = obtainStyledAttributes(intArrayOf(attr))
        return try { typed.getColor(0, ThemeStore.accent.previewColor) } finally { typed.recycle() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Dapat diakses oleh AdvancedColorSettingsBuilder. */
    internal fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
}
