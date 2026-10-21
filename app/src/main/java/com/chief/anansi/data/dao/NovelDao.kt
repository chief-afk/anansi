package com.chief.anansi.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.chief.anansi.data.entity.NovelEntity
import kotlinx.coroutines.flow.Flow

/**
 * One in-library novel with its computed unread count and continue-at data.
 * `continueChapter*` come from the novel's progress row's chapter; they are
 * null when the novel has never been opened.
 */
data class LibraryNovel(
    @Embedded val novel: NovelEntity,
    val unreadCount: Int,
    val continueChapterOrder: Int?,
    val continueChapterTitle: String?,
    val continuePercent: Int?,
)

@Dao
interface NovelDao {

    @Query(
        """
        SELECT novels.*,
               (SELECT COUNT(*) FROM chapters
                WHERE chapters.novelId = novels.id AND chapters.isRead = 0) AS unreadCount,
               cp.sortOrder AS continueChapterOrder,
               cp.title AS continueChapterTitle,
               rp.percent AS continuePercent
        FROM novels
        LEFT JOIN reading_progress rp ON rp.novelId = novels.id
        LEFT JOIN chapters cp ON cp.id = rp.chapterId
        WHERE novels.inLibrary = 1
        ORDER BY novels.addedAt DESC
        """,
    )
    fun observeLibrary(): Flow<List<LibraryNovel>>

    @Insert
    suspend fun insertNovel(novel: NovelEntity): Long

    @Upsert
    suspend fun upsertNovels(novels: List<NovelEntity>)

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun findById(id: Long): NovelEntity?

    /** The library row for a source-discovered novel, when one exists. */
    @Query("SELECT * FROM novels WHERE sourceId = :sourceId AND sourceNovelId = :sourceNovelId")
    fun observeBySourceNovel(sourceId: Long, sourceNovelId: String): Flow<NovelEntity?>

    @Query("UPDATE novels SET inLibrary = :inLibrary WHERE id = :novelId")
    suspend fun setInLibrary(novelId: Long, inLibrary: Boolean)
}
