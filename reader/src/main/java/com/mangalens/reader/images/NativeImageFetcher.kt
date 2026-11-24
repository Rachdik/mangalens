package com.mangalens.reader.images

import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Hasil pengambilan gambar dari URL.
 */
data class FetchOutcome(
    val isSuccess: Boolean,
    val bytes: ByteArray? = null,
    val contentType: String? = null,
    val reason: String? = null
) {
    companion object {
        fun success(bytes: ByteArray, contentType: String?): FetchOutcome =
            FetchOutcome(isSuccess = true, bytes = bytes, contentType = contentType)

        fun failure(reason: String): FetchOutcome =
            FetchOutcome(isSuccess = false, reason = reason)
    }

    val bytesOrNull: ByteArray?
        get() = if (isSuccess) bytes else null

    val reasonOrNull: String?
        get() = if (!isSuccess) reason else null
}

/**
 * Mengambil byte gambar dari native (OkHttp), bukan lewat canvas/screenshot.
 * Situs yang menolak hotlink biasanya menerima permintaan ini karena Referer
 * halaman dan cookie sesi WebView ikut dikirim.
 */
class NativeImageFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    suspend fun fetch(url: String, context: PageContext): FetchOutcome = withContext(Dispatchers.IO) {
        val cookie = CookieManager.getInstance().getCookie(url)
        val referers = buildList {
            if (context.pageUrl.isNotBlank()) add(context.pageUrl)
            originOf(context.pageUrl)?.let { add(it) }
            add(null)
        }.distinct()

        var lastFailure = "Tidak diketahui"
                for (referer in referers) {
            val outcome = attempt(url, referer, context.userAgent, cookie)
            if (outcome.isSuccess) return@withContext outcome
            lastFailure = outcome.reasonOrNull ?: lastFailure
        }
        FetchOutcome(isSuccess = false, reason = lastFailure)
    }

    private fun attempt(
        url: String,
        referer: String?,
        userAgent: String?,
        cookie: String?
    ): FetchOutcome {
        return try {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")

            referer?.let { builder.header("Referer", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

                        client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return FetchOutcome(isSuccess = false, reason = "HTTP ${response.code}")
                }
                val body = response.body ?: return FetchOutcome(isSuccess = false, reason = "Respons kosong")
                val contentType = body.contentType()?.toString()
                if (contentType != null && !contentType.startsWith("image/")) {
                    return FetchOutcome(isSuccess = false, reason = "Bukan gambar ($contentType)")
                }
                if (body.contentLength() > MAX_BYTES) {
                    return FetchOutcome(isSuccess = false, reason = "Ukuran melebihi batas")
                }
                val bytes = body.bytes()
                if (bytes.isEmpty()) return FetchOutcome(isSuccess = false, reason = "Byte kosong")
                if (bytes.size > MAX_BYTES) return FetchOutcome(isSuccess = false, reason = "Ukuran melebihi batas")
                FetchOutcome(isSuccess = true, bytes = bytes, contentType = contentType)
            }
        } catch (e: Exception) {
            FetchOutcome(isSuccess = false, reason = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun originOf(pageUrl: String): String? = runCatching {
        val uri = Uri.parse(pageUrl)
        val host = uri.host ?: return null
        "${uri.scheme ?: "https"}://$host/"
    }.getOrNull()

        companion object {
        private const val MAX_BYTES = 20L * 1024 * 1024
    }
}

/** Konteks halaman yang dibutuhkan saat mengambil gambar. */
data class PageContext(
    val pageUrl: String,
    val userAgent: String?
)
