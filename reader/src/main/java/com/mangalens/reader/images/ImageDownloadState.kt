package com.mangalens.reader.images

import java.io.File

/** Status unduhan per gambar, dipakai panel daftar gambar. */
sealed interface ImageDownloadState {
    data object Pending : ImageDownloadState

    data object Downloading : ImageDownloadState

    data class Ready(
        val file: File,
        val bytes: Long,
        val mimeType: String?
    ) : ImageDownloadState

    data class Failed(val reason: String) : ImageDownloadState
}
