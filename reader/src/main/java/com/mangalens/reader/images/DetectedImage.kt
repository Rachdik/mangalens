package com.mangalens.reader.images

import android.net.Uri

/**
 * Satu gambar yang terdeteksi di DOM. [docOrder] adalah posisi absolut di
 * dokumen sehingga urutan tidak berubah ketika gambar lain menyusul termuat.
 */
data class DetectedImage(
    val url: String,
    val width: Int,
    val height: Int,
    val docOrder: Int,
    val sequence: Int,
    /** True bila sumber adalah <canvas>, sehingga byte diambil lewat ekstraksi. */
    val isCanvas: Boolean = false
) {
    val key: String get() = normalizeKey(url)

    companion object {
        fun normalizeKey(rawUrl: String): String =
            runCatching {
                val uri = Uri.parse(rawUrl)
                uri.buildUpon().fragment(null).build().toString()
            }.getOrDefault(rawUrl)
    }
}
