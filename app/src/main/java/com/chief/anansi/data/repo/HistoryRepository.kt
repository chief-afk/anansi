package com.chief.anansi.data.repo

import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.dao.HistoryEntry
import com.chief.anansi.data.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reading history: progress records joined to novel and chapter, newest
 * first, with the timestamp the UI day-groups on. One progress row exists
 * per novel; upserting moves the novel's history entry.
 */
class HistoryRepository(
    database: AnansiDatabase,
) {
    private val progressDao = database.readingProgressDao()

    fun observeHistory(): Flow<List<HistoryEntry>> = progressDao.observeHistory()

    suspend fun upsertProgress(progress: ReadingProgressEntity) {
        progressDao.upsert(progress)
    }

    suspend fun removeEntry(novelId: Long) {
        progressDao.deleteForNovel(novelId)
    }

    suspend fun clearAll() {
        progressDao.clearAll()
    }
}
