package com.chief.anansi.data.repo

import androidx.room.withTransaction
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.dao.LibraryNovel
import com.chief.anansi.data.dao.NovelWithProgress
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.plugin.Source
import com.chief.anansi.plugin.SourceNovel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Library reads and writes over novels, chapters, and read marks. Reads are
 * Flows, writes are suspend; [ensureSourceNovelRows] is the one write that
 * may fetch, creating the reader rows a novel needs before any chapter opens.
 */
class LibraryRepository(
    private val database: AnansiDatabase,
    private val loadSource: suspend (SourceEntity) -> Source,
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

    /**
     * The one owner of lazy row creation: returns the novel's row, loading
     * its details and inserting the novel plus chapter rows (inLibrary =
     * false) when no row exists yet, so reading never requires shelving
     * first. Unknown identities return null; a details failure propagates
     * to the caller.
     */
    suspend fun ensureSourceNovelRows(identity: String, novelPath: String): NovelEntity? {
        val source = database.sourceDao().findByIdentity(identity) ?: return null
        novelBySourceNovel(source.id, novelPath)?.let { return it }
        val details = loadSource(source).novelDetails(novelPath)
        return database.withTransaction {
            // Registration-style double-check: Room writes serialize on the
            // single connection, so the re-check sees a concurrent ensure.
            val existing = novelDao.findBySourceNovel(source.id, novelPath)
            if (existing != null) {
                existing
            } else {
                val (novel, chapters) =
                    sourceNovelRows(source, novelPath, details, System.currentTimeMillis())
                val novelId = novelDao.insertNovel(novel)
                chapterDao.upsertChapters(chapters.map { it.copy(novelId = novelId) })
                novel.copy(id = novelId)
            }
        }
    }

    suspend fun setChapterRead(novelId: Long, chapterId: Long, isRead: Boolean) {
        chapterDao.setChapterRead(novelId, chapterId, isRead)
    }

    suspend fun setAllChaptersRead(novelId: Long, isRead: Boolean) {
        chapterDao.setAllChaptersRead(novelId, isRead)
    }

    /** The library row for a novel discovered on a source, when it is shelved. */
    fun observeBySourceNovel(sourceId: Long, sourceNovelId: String): Flow<NovelEntity?> =
        novelDao.observeBySourceNovel(sourceId, sourceNovelId)

    /** One-shot [observeBySourceNovel] for screens that load once. */
    suspend fun novelBySourceNovel(sourceId: Long, sourceNovelId: String): NovelEntity? =
        novelDao.observeBySourceNovel(sourceId, sourceNovelId).first()

    /** A shelved novel with its chapters and, when present, its progress row. */
    suspend fun novelWithProgress(novelId: Long): NovelWithProgress? =
        chapterDao.findNovelWithProgress(novelId)

    /** A shelved novel's chapter rows, in source order. */
    fun observeChapters(novelId: Long): Flow<List<ChapterEntity>> =
        chapterDao.observeByNovel(novelId)

    /**
     * Toggles library membership for a known row without touching its
     * chapters or read marks; first-time shelving goes through
     * [addNovelWithChapters].
     */
    suspend fun setInLibrary(novelId: Long, inLibrary: Boolean) {
        novelDao.setInLibrary(novelId, inLibrary)
    }
}

/**
 * The entities [LibraryRepository.ensureSourceNovelRows] inserts for a
 * source-only novel: the novel row unshelved, chapter rows keyed by the
 * verbatim chapter path in source order, duplicate paths collapsed to the
 * first occurrence so the (novelId, chapterUrl) unique index never rejects
 * the insert.
 */
internal fun sourceNovelRows(
    source: SourceEntity,
    novelPath: String,
    details: SourceNovel,
    now: Long,
): Pair<NovelEntity, List<ChapterEntity>> {
    val novel = NovelEntity(
        sourceId = source.id,
        sourceNovelId = novelPath,
        title = details.name ?: novelPath,
        author = details.author,
        artist = details.artist,
        status = details.status,
        genre = details.genres,
        summary = details.summary,
        coverUrl = details.cover,
        url = novelPath,
        inLibrary = false,
        addedAt = now,
    )
    val chapters = details.chapters
        .distinctBy { it.path }
        .mapIndexed { index, chapter ->
            ChapterEntity(
                novelId = 0,
                // LNReader chapters carry no separate id; the verbatim path
                // is the source-local identity.
                sourceChapterId = chapter.path,
                chapterUrl = chapter.path,
                title = chapter.name,
                releaseDate = chapter.releaseTime,
                sortOrder = index,
            )
        }
    return novel to chapters
}
