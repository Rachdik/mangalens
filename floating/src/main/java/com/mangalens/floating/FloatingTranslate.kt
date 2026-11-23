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
}
