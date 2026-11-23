package com.mangalens.reader.images

sealed interface FetchOutcome {
    data class Success(val bytes: ByteArray, val contentType: String?) : FetchOutcome

    data class Failure(val reason: String) : FetchOutcome
}

/** Konteks halaman yang dibutuhkan saat mengambil gambar. */
data class PageContext(
    val pageUrl: String,
    val userAgent: String?
)
