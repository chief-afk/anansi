package com.chief.anansi.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import com.chief.anansi.data.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

/** Novel plus its chapters and, when present, its single progress row. */
data class NovelWithProgress(
    @Embedded val novel: NovelEntity,
    @Relation(parentColumn = "id", entityColumn = "novelId")
    val chapters: List<ChapterEntity>,
    @Relation(parentColumn = "id", entityColumn = "novelId")
    val progress: List<ReadingProgressEntity>,
)

@Dao
interface ChapterDao {

    @Upsert
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY sortOrder")
    suspend fun findByNovel(novelId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY sortOrder")
    fun observeByNovel(novelId: Long): Flow<List<ChapterEntity>>

    @Transaction
    @Query("SELECT * FROM novels WHERE id = :novelId")
    suspend fun findNovelWithProgress(novelId: Long): NovelWithProgress?

    @Query("UPDATE chapters SET isRead = :isRead WHERE novelId = :novelId AND id = :chapterId")
    suspend fun setChapterRead(novelId: Long, chapterId: Long, isRead: Boolean)

    @Query("UPDATE chapters SET isRead = :isRead WHERE novelId = :novelId")
    suspend fun setAllChaptersRead(novelId: Long, isRead: Boolean)

    /** Opening the novel clears its new-arrival state (v1 unread-on-open). */
    @Query("UPDATE chapters SET isNew = 0 WHERE novelId = :novelId AND isNew = 1")
    suspend fun clearNewArrivals(novelId: Long)
}
