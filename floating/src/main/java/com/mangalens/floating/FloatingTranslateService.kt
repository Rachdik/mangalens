package com.mangalens.floating

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.PageTranslation
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.LlmTranslationProvider
import com.mangalens.translation.MlKitTranslationProvider
import com.mangalens.translation.MyMemoryTranslationProvider
import com.mangalens.translation.TranslationBackend
import com.mangalens.translation.TranslationSettings
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class FloatingTranslateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: BubbleOverlay

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var translator: MangaPageTranslator? = null
    private var translatorBackend: TranslationBackend? = null
    private var currentJob: Job? = null
    private lateinit var translationSettings: TranslationSettings

    private val generationCounter = AtomicLong(0)
    private var currentGeneration = 0L

    private val ocrCache = ConcurrentHashMap<String, PageTranslation>()

    private var lastRefreshTime = 0L
    private val DEBOUNCE_DELAY_MS = 800L

    override fun onCreate() {
        super.onCreate()
        translationSettings = TranslationSettings(this)
        overlay = BubbleOverlay(
            context = this,
            onTranslate = { translateScreen(isRefresh = false) },
            onStop = { stopSelf() },
            onRefresh = { refreshScreen() },
            onPause = { pauseTranslation() },
            onResume = { resumeTranslation() },
            onSettings = { openSettings() },
            settings = translationSettings
        )
        FloatingTranslate.isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FloatingTranslate.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == FloatingTranslate.ACTION_RESHOW) {
            overlay.setHidden(false)
            return START_STICKY
        }

        startForegroundNow()

        val resultCode = intent?.getIntExtra(FloatingTranslate.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.let {
            IntentCompat.getParcelableExtra(it, FloatingTranslate.EXTRA_RESULT_DATA, Intent::class.java)
        }

        val bound = if (resultCode == Activity.RESULT_OK && data != null) {
            bindProjection(resultCode, data)
        } else {
            false
        }

        if (!overlay.show()) {
            FloatingTranslate.isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (resultCode == Activity.RESULT_OK && data != null && !bound) {
            overlay.setStatus("Izin rekam layar gagal dipakai. Ketuk bubble untuk mencoba lagi.", true)
        } else if (capture == null) {
            overlay.setStatus("Izin rekam layar belum ada. Ketuk bubble untuk menyambung ulang.", true)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        currentJob?.cancel()
        scope.cancel()
        overlay.hide()
        capture?.close()
        capture = null
        val current = projection
        projection = null
        runCatching { current?.stop() }
        translator?.close()
        translator = null
        FloatingTranslate.isRunning = false
        super.onDestroy()
    }

    fun refreshScreen() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTime < DEBOUNCE_DELAY_MS) {
            overlay.flashStatus("Mohon tunggu sebentar sebelum refresh lagi.", true)
            return
        }
        lastRefreshTime = now

        currentJob?.cancel()
        currentJob = null

        currentGeneration = generationCounter.incrementAndGet()
        // Refresh selalu mengambil frame baru dan mengabaikan cache OCR lama.
        translateScreen(isRefresh = true)
    }

    fun pauseTranslation() {
        currentJob?.cancel()
        currentJob = null
        overlay.setStatus("Terjemahan dijeda. Ketuk bubble untuk meneruskan.", false)
        FloatingTranslate.isPaused = true
    }

    fun resumeTranslation() {
        FloatingTranslate.isPaused = false
        overlay.setStatus("Melanjutkan terjemahan...", false)
        translateScreen(isRefresh = false)
    }

    fun openSettings() {
        // Jangan biarkan bubble/terjemahan layar sebelumnya menutupi halaman
        // Settings. Overlay ditampilkan lagi lewat ACTION_RESHOW saat Settings
        // ditinggalkan; service tetap foreground selama Settings terbuka.
        overlay.setHidden(true)
        // Harus halaman Settings yang sama dengan aplikasi utama. Modul floating
        // tidak bergantung pada modul app, jadi komponen dituju lewat nama kelas.
        val intent = Intent().apply {
            setClassName(packageName, "com.mangalens.app.SettingsActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                android.util.Log.w(TAG, "Settings gagal dibuka", it)
                overlay.setStatus("Pengaturan tidak bisa dibuka dari bubble.", true)
            }
    }

    private fun translateScreen(isRefresh: Boolean = false) {
        android.util.Log.d(
            TAG,
            "tap refresh=$isRefresh paused=${FloatingTranslate.isPaused} " +
                "capture=${capture != null} hasTranslations=${overlay.hasTranslations()}"
        )
        if (FloatingTranslate.isPaused) {
            overlay.setStatus("Terjemahan dijeda. Tekan tombol Resume untuk melanjutkan.", true)
            return
        }

        if (isRefresh) {
            // Hasil lama tidak relevan dengan halaman baru.
            overlay.clearTranslations()
            overlay.setRefreshing(true)
            overlay.setStatus("Mengambil konten terbaru...")
        } else if (overlay.hasTranslations()) {
            android.util.Log.d(TAG, "tap menghapus terjemahan lama")
            overlay.clearTranslations()
            overlay.flashStatus("Terjemahan dihapus. Ketuk bubble untuk menerjemahkan lagi.")
            return
        }

        val screenCapture = capture
        if (screenCapture == null) {
            if (isRefresh) overlay.setRefreshing(false)
            overlay.setStatus("Izin rekam layar tidak aktif. Menyiapkan izin ulang...")
            FloatingTranslate.requestProjection(this)
            return
        }

        currentJob?.cancel()

        currentJob = scope.launch {
            if (currentGeneration != generationCounter.get()) {
                return@launch
            }

                overlay.setStatus(if (isRefresh) "Refresh: mengambil layar..." else "Mengambil layar...")
                android.util.Log.d(TAG, "capture mulai refresh=$isRefresh")
                try {
                overlay.setHidden(true)
                delay(OVERLAY_HIDE_DELAY_MS)
                val frame = try {
                    withContext(Dispatchers.IO) { capture?.capture() }
                } finally {
                    overlay.setHidden(false)
                }

                if (frame == null) {
                    android.util.Log.w(TAG, "capture null")
                    overlay.setStatus("Gagal mengambil layar. Coba lagi.", true)
                    return@launch
                }
                android.util.Log.d(TAG, "capture ok ${frame.bitmap.width}x${frame.bitmap.height}")

                overlay.setStatus("Mendeteksi balon teks...")
                val sourceTag = translationSettings.sourceTag
                val targetTag = translationSettings.targetTag

                val bitmapHash = simpleBitmapHash(frame.bitmap)
                // Refresh wajib memproses frame baru; cache hanya untuk tap biasa.
                val cached = if (isRefresh) null else ocrCache[bitmapHash]
                val result = if (cached != null && cached.sourceTag == sourceTag && cached.targetTag == targetTag) {
                    cached
                } else {
                    val translateResult = withContext(Dispatchers.Default) {
                        translator().translate(
                            bitmap = frame.bitmap,
                            script = scriptFor(sourceTag),
                            sourceTag = sourceTag,
                            targetTag = targetTag
                        )
                    }
                    if (translateResult.ocrBlocks <= 50) {
                        ocrCache[bitmapHash] = translateResult
                    }
                    translateResult
                }

                if (currentGeneration != generationCounter.get()) {
                    return@launch
                }

                showResult(result)
            } catch (e: ProjectionUnavailableException) {
                capture = null
                overlay.setStatus(e.message ?: "Izin rekam layar berakhir.", true)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "gagal: ${t.javaClass.simpleName}", t)
                overlay.setStatus(t.message ?: "Gagal menerjemahkan layar.", true)
            } finally {
                if (currentGeneration == generationCounter.get()) {
                    overlay.setRefreshing(false)
                }
            }
        }
    }

    private fun simpleBitmapHash(bitmap: android.graphics.Bitmap): String {
        val sb = StringBuilder()
        sb.append(bitmap.width).append("x").append(bitmap.height)
        val step = max(1, bitmap.width / 10)
        for (y in 0 until minOf(bitmap.height, 10)) {
            for (x in 0 until minOf(bitmap.width, 10)) {
                sb.append(bitmap.getPixel(x * step, y * step))
            }
        }
        return sb.toString()
    }

    private fun showResult(result: PageTranslation) {
        val provider = result.providerName.ifBlank { translationSettings.backend.name }
        val info = "OCR ${result.ocrBlocks} blok · ${result.detectedBubbles} balon · " +
            "${result.sourceTag}→${translationSettings.targetTag} · $provider"

        if (result.ocrBlocks == 0) {
            overlay.setStatus(
                "Tidak ada teks yang terbaca di layar ($info). Perbesar gambar " +
                    "atau pastikan halaman sudah selesai dimuat.",
                true
            )
            return
        }

        if (result.bubbles.isEmpty()) {
            overlay.setStatus(
                "Teks terbaca, tetapi terjemahan tidak berubah ($info). " +
                    "Model bahasa mungkin belum terunduh — sambungkan internet lalu ketuk lagi.",
                true
            )
            return
        }

        android.util.Log.d(
            TAG,
            "result bubbles=${result.bubbles.size} provider=${result.providerName} " +
                "scale=${capture?.lastScale}"
        )
        val shown = overlay.showTranslations(result.bubbles, capture?.lastScale ?: 1f)
        if (shown == 0) {
            overlay.setStatus("Balon terdeteksi tetapi tidak ada teks yang bisa ditempel.", true)
        } else {
            overlay.flashStatus(
                "$shown balon diterjemahkan ($info). Ketuk bubble untuk menghapus.",
                durationMs = 4000L
            )
        }
    }

    private fun bindProjection(resultCode: Int, data: Intent): Boolean {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return false
        val created = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
            ?: return false

        created.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection !== created) return
                    capture?.close()
                    capture = null
                    projection = null
                    overlay.clearTranslations()
                    overlay.setStatus("Izin rekam layar dihentikan sistem. Ketuk bubble lalu setujui izin untuk melanjutkan.", true)
                }
            },
            Handler(Looper.getMainLooper())
        )

        capture?.close()
        capture = null
        val previous = projection
        projection = created
        runCatching { previous?.stop() }

        val session = ScreenCapture(this, created)
        if (runCatching { session.start() }.isFailure) {
            session.close()
            if (projection === created) projection = null
            runCatching { created.stop() }
            return false
        }

        capture = session
        overlay.clearTranslations()
        return true
    }

    private fun translator(): MangaPageTranslator {
        val backend = translationSettings.backend
        translator?.let { if (translatorBackend == backend) return it }

        translator?.close()
        val created = MangaPageTranslator(MlKitOcrEngine()) { source, target ->
            // Factory yang sama dengan Reader/Share; kunci LLM berasal dari
            // penyimpanan terenkripsi, bukan BuildConfig.
            com.mangalens.translation.TranslationProviders.create(
                backend,
                source,
                target,
                translationSettings.llmConfig(),
                autoFallback = translationSettings.autoFallback
            )
        }
        translator = created
        translatorBackend = backend
        return created
    }

    private fun scriptFor(sourceTag: String): LanguageScript = when {
        sourceTag == "auto" -> LanguageScript.AUTO
        sourceTag.startsWith("ja") -> LanguageScript.JAPANESE
        sourceTag.startsWith("ko") -> LanguageScript.KOREAN
        sourceTag.startsWith("zh") -> LanguageScript.CHINESE
        else -> LanguageScript.LATIN
    }

    private fun startForegroundNow() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                FloatingTranslate.CHANNEL_ID,
                "Terjemahan layar",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notification = NotificationCompat.Builder(this, FloatingTranslate.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("MangaLens aktif")
            .setContentText("Ketuk bubble: refresh | Ketuk lama: stop | Geser: pindah")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            FloatingTranslate.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private companion object {
        const val TAG = "MangaLensFloating"
        const val OVERLAY_HIDE_DELAY_MS = 250L
    }
}
