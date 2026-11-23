package com.mangalens.core.data

import com.mangalens.core.model.HistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Kontrak riwayat; implementasi nyata ada di modul `storage` (Room).
 * Modul UI (app, reader) hanya bergantung pada interface ini.
 */
interface HistoryStore {
    fun observeRecent(limit: Int): Flow<List<HistoryEntry>>

    suspend fun record(url: String, title: String)

    suspend fun clear()
}
