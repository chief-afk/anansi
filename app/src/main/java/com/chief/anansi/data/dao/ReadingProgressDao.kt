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

/** A progress row joined to its novel and current chapter, for history. */
data class HistoryEntry(
    @Embedded val progress: ReadingProgressEntity,
    @Relation(parentColumn = "novelId", entityColumn = "id")
    val novel: NovelEntity,
    @Relation(parentColumn = "chapterId", entityColumn = "id")
    val chapter: ChapterEntity,
    val sourceIdentity: String,
)

@Dao
interface ReadingProgressDao {

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Transaction
    @Query(
        """
        SELECT reading_progress.*, sources.identity AS sourceIdentity
        FROM reading_progress
        JOIN novels ON novels.id = reading_progress.novelId
        JOIN sources ON sources.id = novels.sourceId
        ORDER BY reading_progress.updatedAt DESC
        """,
    )
    fun observeHistory(): Flow<List<HistoryEntry>>

    @Query("DELETE FROM reading_progress WHERE novelId = :novelId")
    suspend fun deleteForNovel(novelId: Long)

    @Query("DELETE FROM reading_progress")
    suspend fun clearAll()
}
