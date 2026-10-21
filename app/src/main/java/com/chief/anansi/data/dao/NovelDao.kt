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

/** One Updates-tab row: a library novel with its new-arrival aggregate. */
data class UpdateRow(
    @Embedded val novel: NovelEntity,
    val sourceName: String,
    val sourceIdentity: String,
    val newCount: Int,
    val newestNewTitle: String?,
    val newestNewUrl: String?,
)

/** One imported novel of a single source with its chapter count. */
data class SourceNovelRow(
    @Embedded val novel: NovelEntity,
    val chapterCount: Int,
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

    /**
     * One Updates-tab row per library novel: its source display line and the
     * new-arrival aggregate (count plus the newest arrival, which is the
     * arrival with the greatest source order). Rows the reader never checked
     * carry `lastCheckedAtEpochMs = 0` and sort last.
     */
    @Query(
        """
        SELECT novels.*, sources.displayName AS sourceName, sources.identity AS sourceIdentity,
               (SELECT COUNT(*) FROM chapters c
                WHERE c.novelId = novels.id AND c.isNew = 1) AS newCount,
               (SELECT c.title FROM chapters c
                WHERE c.novelId = novels.id AND c.isNew = 1
                ORDER BY c.sortOrder DESC LIMIT 1) AS newestNewTitle,
               (SELECT c.chapterUrl FROM chapters c
                WHERE c.novelId = novels.id AND c.isNew = 1
                ORDER BY c.sortOrder DESC LIMIT 1) AS newestNewUrl
        FROM novels
        JOIN sources ON sources.id = novels.sourceId
        WHERE novels.inLibrary = 1
        ORDER BY novels.lastCheckedAtEpochMs DESC, novels.addedAt DESC
        """,
    )
    fun observeUpdateRows(): Flow<List<UpdateRow>>

    @Query("SELECT * FROM novels WHERE inLibrary = 1 ORDER BY addedAt DESC")
    suspend fun libraryNovels(): List<NovelEntity>

    @Query("UPDATE novels SET lastCheckedAtEpochMs = :checkedAt WHERE id = :novelId")
    suspend fun touchChecked(novelId: Long, checkedAt: Long)

    @Insert
    suspend fun insertNovel(novel: NovelEntity): Long

    @Upsert
    suspend fun upsertNovels(novels: List<NovelEntity>)

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun findById(id: Long): NovelEntity?

    /** The library row for a source-discovered novel, when one exists. */
    @Query("SELECT * FROM novels WHERE sourceId = :sourceId AND sourceNovelId = :sourceNovelId")
    fun observeBySourceNovel(sourceId: Long, sourceNovelId: String): Flow<NovelEntity?>

    /** One-shot [observeBySourceNovel] for non-UI callers (backup import). */
    @Query("SELECT * FROM novels WHERE sourceId = :sourceId AND sourceNovelId = :sourceNovelId")
    suspend fun findBySourceNovel(sourceId: Long, sourceNovelId: String): NovelEntity?

    /** A source's imported library novels, newest first, with chapter counts. */
    @Query(
        """
        SELECT novels.*,
               (SELECT COUNT(*) FROM chapters
                WHERE chapters.novelId = novels.id) AS chapterCount
        FROM novels
        WHERE novels.sourceId = :sourceId AND novels.inLibrary = 1
        ORDER BY novels.addedAt DESC
        """,
    )
    fun observeBySource(sourceId: Long): Flow<List<SourceNovelRow>>

    @Query("UPDATE novels SET inLibrary = :inLibrary WHERE id = :novelId")
    suspend fun setInLibrary(novelId: Long, inLibrary: Boolean)

    @Query("UPDATE novels SET coverUrl = :coverUrl WHERE id = :novelId")
    suspend fun setCoverUrl(novelId: Long, coverUrl: String?)

    @Query("DELETE FROM novels WHERE id = :novelId")
    suspend fun deleteById(novelId: Long)
}
