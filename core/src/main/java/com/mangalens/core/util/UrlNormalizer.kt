package com.mangalens.core.util

import android.net.Uri

/**
 * Validasi URL input: hanya http/https, host harus punya titik.
 * Mengembalikan null bila URL tidak bisa dipakai.
 */
object UrlNormalizer {

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val candidate = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }

        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host ?: return null
        if (!host.contains('.')) return null

        return uri.buildUpon().scheme(scheme).build().toString()
    }

    fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull() ?: url
}
