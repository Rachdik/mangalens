package com.mangalens.core.di

import com.mangalens.core.data.HistoryStore

/**
 * DI manual (tanpa Hilt). Diisi sekali oleh Application di modul `app`.
 * Modul lain mengambil dependensi dari sini sehingga tidak perlu saling
 * bergantung ke `app`.
 */
object Dependencies {
    @Volatile
    lateinit var historyStore: HistoryStore

    fun isReady(): Boolean = this::historyStore.isInitialized
}
