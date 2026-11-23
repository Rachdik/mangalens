package com.mangalens.core.model

/**
 * Satu entri riwayat halaman yang pernah dibuka di reader.
 */
data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long
)
