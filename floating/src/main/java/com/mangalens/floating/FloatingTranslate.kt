package com.mangalens.floating

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Titik masuk publik modul floating: dipakai layar utama untuk menyalakan
 * bubble dan mengirim izin rekam layar.
 */
object FloatingTranslate {

    const val ACTION_START = "com.mangalens.floating.action.START"
    const val ACTION_STOP = "com.mangalens.floating.action.STOP"

    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"
    const val EXTRA_SCRIPT = "script"
    const val EXTRA_LANGUAGE_TAG = "language_tag"

    /** Dibaca MainActivity untuk langsung meminta izin rekam layar lagi. */
    const val EXTRA_REQUEST_PROJECTION = "com.mangalens.floating.EXTRA_REQUEST_PROJECTION"

    internal const val CHANNEL_ID = "mangalens_screen_translate"
    internal const val NOTIFICATION_ID = 1201

    @Volatile
    var isRunning: Boolean = false
        internal set

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun start(
        context: Context,
        resultCode: Int,
        data: Intent,
        script: String,
        languageTag: String
    ) {
        val intent = Intent(context, FloatingTranslateService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, data)
            putExtra(EXTRA_SCRIPT, script)
            putExtra(EXTRA_LANGUAGE_TAG, languageTag)
        }
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, FloatingTranslateService::class.java).apply { action = ACTION_STOP }
        )
    }

    /**
     * Membuka layar utama dan meminta izin rekam layar baru. Dipakai saat izin
     * lama dicabut sistem sehingga bubble tidak perlu dimatikan.
     */
    fun requestProjection(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(EXTRA_REQUEST_PROJECTION, true)
        runCatching { context.startActivity(intent) }
    }
}
