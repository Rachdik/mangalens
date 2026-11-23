package com.mangalens.storage.data

import com.mangalens.core.data.HistoryStore
import com.mangalens.core.model.HistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomHistoryStore(
    private val dao: HistoryDao,
    private val now: () -> Long = { System.currentTimeMillis() }
) : HistoryStore {

    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> =
        dao.observeRecent(limit).map { rows ->
            rows.map { HistoryEntry(url = it.url, title = it.title, visitedAt = it.visitedAt) }
        }

    override suspend fun record(url: String, title: String) {
        val safeTitle = title.trim().ifEmpty { url }
        dao.upsert(HistoryEntity(url = url, title = safeTitle, visitedAt = now()))
    }

    override suspend fun clear() = dao.clear()
}
