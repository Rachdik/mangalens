package com.mangalens.floating

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service yang menampilkan bubble. Setiap ketukan bubble:
 * tangkap layar -> OCR -> terjemahkan -> tampilkan hasil di panel overlay.
 */
class FloatingTranslateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: FloatingSettings
    private lateinit var overlay: BubbleOverlay
    private val translator = ScreenTranslator(MlKitOcrEngine())

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
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

        if (projection == null) {
            val resultCode = intent?.getIntExtra(FloatingTranslate.EXTRA_RESULT_CODE, 0) ?: 0
            val data = intent?.let {
                IntentCompat.getParcelableExtra(it, FloatingTranslate.EXTRA_RESULT_DATA, Intent::class.java)
            }
            if (resultCode != Activity.RESULT_OK || data == null || !bindProjection(resultCode, data)) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        overlay.show()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        overlay.hide()
        runCatching { projection?.stop() }
        projection = null
        capture = null
        FloatingTranslate.isRunning = false
        super.onDestroy()
    }

    private fun translateScreen() {
        if (job?.isActive == true) return
        val screenCapture = capture
        if (screenCapture == null) {
            overlay.setStatus("Izin rekam layar tidak aktif. Nyalakan ulang bubble.", true)
            return
        }

        job = scope.launch {
            overlay.setStatus("Mengambil layar…")
            try {
                // Overlay MangaLens ikut terekam; sembunyikan dulu.
                overlay.setHidden(true)
                delay(OVERLAY_HIDE_DELAY_MS)
                val bitmap: Bitmap = try {
                    withContext(Dispatchers.IO) { screenCapture.capture() }
                } finally {
                    overlay.setHidden(false)
                }
                overlay.setStatus("Mengenali teks…")
                val lines = withContext(Dispatchers.Default) {
                    translator.translate(bitmap, settings.script, settings.languageTag)
                }
                bitmap.recycle()

                if (lines.isEmpty()) {
                    overlay.setStatus("Tidak ada teks yang terbaca di layar.")
                } else {
                    overlay.setStatus("${lines.size} blok teks diterjemahkan")
                    overlay.showResult(lines)
                }
            } catch (t: Throwable) {
                overlay.setStatus(t.message ?: "Gagal menerjemahkan layar.", true)
            }
        }
    }

    private companion object {
        const val OVERLAY_HIDE_DELAY_MS = 250L
    }

    private fun bindProjection(resultCode: Int, data: Intent): Boolean {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return false
        val created = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
            ?: return false

        created.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper())
        )

        projection = created
        capture = ScreenCapture(this, created)
        return true
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
            .setContentText("Ketuk bubble untuk menerjemahkan layar")
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
}
