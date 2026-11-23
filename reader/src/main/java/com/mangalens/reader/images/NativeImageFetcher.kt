package com.mangalens.reader.images

import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
            when (val outcome = attempt(url, referer, context.userAgent, cookie)) {
                is FetchOutcome.Success -> return@withContext outcome
                is FetchOutcome.Failure -> lastFailure = outcome.reason
            }
        }
        FetchOutcome.Failure(lastFailure)
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
                    return FetchOutcome.Failure("HTTP ${response.code}")
                }
                val body = response.body ?: return FetchOutcome.Failure("Respons kosong")
                val contentType = body.contentType()?.toString()
                if (contentType != null && !contentType.startsWith("image/")) {
                    return FetchOutcome.Failure("Bukan gambar ($contentType)")
                }
                if (body.contentLength() > MAX_BYTES) {
                    return FetchOutcome.Failure("Ukuran melebihi batas")
                }
                val bytes = body.bytes()
                if (bytes.isEmpty()) return FetchOutcome.Failure("Byte kosong")
                if (bytes.size > MAX_BYTES) return FetchOutcome.Failure("Ukuran melebihi batas")
                FetchOutcome.Success(bytes, contentType)
            }
        } catch (e: Exception) {
            FetchOutcome.Failure(e.message ?: e.javaClass.simpleName)
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
