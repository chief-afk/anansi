package com.chief.anansi.data.repo

import androidx.room.withTransaction
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.dao.LibraryNovel
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import kotlinx.coroutines.flow.Flow

/**
 * Library reads and writes over novels, chapters, and read marks. Reads are
 * Flows, writes are suspend; no networking happens here.
 */
class LibraryRepository(
    private val database: AnansiDatabase,
) {
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()

    fun observeLibrary(): Flow<List<LibraryNovel>> = novelDao.observeLibrary()

    /**
     * Persists a novel together with its chapter list in one transaction.
     * Existing read marks survive chapter updates because chapters are
     * keyed by (novel, chapterUrl).
     */
    suspend fun addNovelWithChapters(
        novel: NovelEntity,
        chapters: List<ChapterEntity>,
    ): Long = database.withTransaction {
        val novelId = novelDao.insertNovel(novel)
        chapterDao.upsertChapters(chapters.map { it.copy(novelId = novelId) })
        novelId
    }

    suspend fun setChapterRead(novelId: Long, chapterId: Long, isRead: Boolean) {
        chapterDao.setChapterRead(novelId, chapterId, isRead)
    }

    suspend fun setAllChaptersRead(novelId: Long, isRead: Boolean) {
        chapterDao.setAllChaptersRead(novelId, isRead)
    }
}
