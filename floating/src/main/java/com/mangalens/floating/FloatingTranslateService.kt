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
import com.mangalens.translation.MlKitTranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service bubble terjemahan layar.
 *
 * Alur: ketuk bubble -> tangkap layar -> OCR -> deteksi balon -> terjemahkan ->
 * tempel teks Indonesia tepat di atas balon aslinya. MediaProjection menangkap
 * seluruh layar, jadi alur ini bekerja di MangaLens maupun di aplikasi/browser
 * lain tanpa perlu membuka MangaLens lagi.
 *
 * Service dirancang tetap hidup walaupun izin rekam layar dicabut sistem
 * (mis. saat berpindah halaman atau aplikasi): bubble tetap ada dan ketukan
 * berikutnya meminta izin ulang, bukan mematikan service.
 */
class FloatingTranslateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: FloatingSettings
    private lateinit var overlay: BubbleOverlay

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var translator: MangaPageTranslator? = null
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = FloatingSettings(this)
        overlay = BubbleOverlay(
            context = this,
            onTranslate = { translateScreen() },
            onStop = { stopSelf() }
        )
        FloatingTranslate.isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FloatingTranslate.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground wajib sebelum memakai MediaProjection (Android 14).
        startForegroundNow()

        intent?.getStringExtra(FloatingTranslate.EXTRA_SCRIPT)?.let { name ->
            runCatching { settings.script = LanguageScript.valueOf(name) }
        }
        intent?.getStringExtra(FloatingTranslate.EXTRA_LANGUAGE_TAG)?.let { tag ->
            settings.languageTag = tag
        }

        val resultCode = intent?.getIntExtra(FloatingTranslate.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.let {
            IntentCompat.getParcelableExtra(it, FloatingTranslate.EXTRA_RESULT_DATA, Intent::class.java)
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            if (!bindProjection(resultCode, data)) {
                overlay.show()
                overlay.setStatus(
                    "Izin rekam layar gagal dipakai. Ketuk bubble untuk mencoba lagi.",
                    true
                )
            }
        } else if (capture == null) {
            // Service dijalankan ulang tanpa izin (mis. setelah proses dibunuh).
            overlay.show()
            overlay.setStatus(
                "Izin rekam layar belum ada. Ketuk bubble untuk menyambung ulang.",
                true
            )
        } else {
            overlay.show()
        }

        // START_STICKY: bila sistem membunuh service, bubble kembali muncul
        // dengan status untuk menyambung ulang, bukan hilang tanpa jejak.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        overlay.hide()
        capture?.close()
        capture = null
        // Lepas referensi lebih dulu agar callback onStop tidak menambah view
        // baru setelah service dihancurkan.
        val current = projection
        projection = null
        runCatching { current?.stop() }
        translator?.close()
        translator = null
        FloatingTranslate.isRunning = false
        super.onDestroy()
    }

    private fun translateScreen() {
        if (job?.isActive == true) return

        // Ketukan kedua menghapus tempelan terjemahan (agar bisa lihat aslinya).
        if (overlay.hasTranslations()) {
            overlay.clearTranslations()
            overlay.flashStatus("Terjemahan dihapus. Ketuk bubble untuk menerjemahkan lagi.")
            return
        }

        val screenCapture = capture
        if (screenCapture == null) {
            overlay.setStatus("Izin rekam layar tidak aktif. Menyiapkan izin ulang…")
            FloatingTranslate.requestProjection(this)
            return
        }

        job = scope.launch {
            overlay.setStatus("Mengambil layar…")
            try {
                // Overlay MangaLens ikut terekam; sembunyikan dulu.
                overlay.setHidden(true)
                delay(OVERLAY_HIDE_DELAY_MS)
                val frame = try {
                    withContext(Dispatchers.IO) { screenCapture.capture() }
                } finally {
                    overlay.setHidden(false)
                }

                overlay.setStatus("Mendeteksi balon teks…")
                val result = try {
                    withContext(Dispatchers.Default) {
                        translator().translate(frame.bitmap, settings.script)
                    }
                } finally {
                    frame.bitmap.recycle()
                }

                showResult(result)
            } catch (e: ProjectionUnavailableException) {
                capture = null
                overlay.setStatus(e.message ?: "Izin rekam layar berakhir.", true)
            } catch (t: Throwable) {
                overlay.setStatus(t.message ?: "Gagal menerjemahkan layar.", true)
            }
        }
    }

    private fun showResult(result: PageTranslation) {
        val info = "OCR ${result.ocrBlocks} blok · ${result.detectedBubbles} balon · ${result.sourceTag}→id"

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
                if (result.detectedBubbles == 0) {
                    "Teks terbaca, tetapi tidak ada balon yang bisa ditempel ($info). " +
                        "Coba perbesar halaman sampai teks lebih jelas."
                } else {
                    "Balon terdeteksi tetapi terjemahan tidak berubah ($info). " +
                        "Model bahasa mungkin belum terunduh — sambungkan internet lalu ketuk lagi."
                },
                true
            )
            return
        }

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
                    // Abaikan sesi lama yang sengaja diganti sesi baru.
                    if (projection !== created) return
                    capture?.close()
                    capture = null
                    projection = null
                    overlay.clearTranslations()
                    overlay.setStatus(
                        "Izin rekam layar dihentikan sistem. Ketuk bubble lalu setujui izin untuk melanjutkan.",
                        true
                    )
                }
            },
            Handler(Looper.getMainLooper())
        )

        // Pasang referensi baru sebelum sesi lama dihentikan, supaya callback
        // onStop milik sesi lama tidak menghapus sesi yang baru.
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

    /** Satu pipeline untuk semua ketukan; provider internal di-cache per bahasa. */
    private fun translator(): MangaPageTranslator {
        translator?.let { return it }
        val created = MangaPageTranslator(MlKitOcrEngine()) { tag -> MlKitTranslationProvider(tag) }
        translator = created
        return created
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
            .setContentText("Ketuk bubble untuk menempelkan terjemahan di balon")
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
        const val OVERLAY_HIDE_DELAY_MS = 250L
    }
}
