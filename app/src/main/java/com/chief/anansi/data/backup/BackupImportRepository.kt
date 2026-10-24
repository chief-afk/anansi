package com.chief.anansi.data.backup

import androidx.room.withTransaction
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import com.chief.anansi.data.entity.ReadingProgressEntity
import com.chief.anansi.data.repo.SourceEcosystem
import com.chief.anansi.data.repo.SourceRegistryRepository
import kotlinx.coroutines.CancellationException

/** One imported novel whose source is not installed, for the review list. */
data class NeedsSourceNovel(
    val title: String,
    val sourceName: String,
    val v1SourceId: Long,
)

/**
 * The import run's outcome: the restored/merged counts the result dialog
 * leads with, the needs-source review list, and every skip with its reason
 * (import never drops data silently).
 */
data class BackupImportResult(
    val novelsRestored: Int,
    val novelsMerged: Int,
    val chaptersInserted: Int,
    val readMarksApplied: Int,
    val historyRestored: Int,
    val needsSource: List<NeedsSourceNovel>,
    val skippedNovels: List<BackupItemSkip>,
    val skippedChapters: List<BackupItemSkip>,
    val skippedHistory: List<BackupItemSkip>,
    val categoriesSkipped: Int,
    val bookmarksSkipped: Int,
)

/**
 * Applies a parsed LNReader v1 backup to the library. Parse-then-write: the
 * whole file parses and validates before the first transaction, so a
 * top-level failure leaves the database untouched. The writes then run in
 * one transaction with per-novel error isolation: a novel that fails is
 * skipped, counted, and reported while the rest of the import proceeds.
 *
 * Everything is offline; chapters for official-shape novels arrive later
 * through the manual update checks against their source.
 */
class BackupImportRepository(
    private val database: AnansiDatabase,
    private val sourceRegistry: SourceRegistryRepository,
) {
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val readingProgressDao = database.readingProgressDao()

    suspend fun import(text: String): BackupImportResult {
        val parsed = LnReaderBackupParser.parse(text)
        val file = parsed.file
        val chapterUrlByBackupId = file.chapters.associate { it.chapterId to it.chapterUrl }
        val chaptersByNovel = file.chapters.groupBy { it.novelId }
        val historyByNovel = file.history.groupBy { it.historyNovelId }

        return database.withTransaction {
            val sourceRows = ensureSourceRows(file.novels)

            var restored = 0
            var merged = 0
            var chaptersInserted = 0
            var readMarks = 0
            var historyRestored = 0
            val needsSource = ArrayList<NeedsSourceNovel>()
            val skippedNovels = ArrayList<BackupItemSkip>()
            val skippedHistory = ArrayList<BackupItemSkip>()
            val plannedNovelIds = HashSet<Long>()

            for (novel in file.novels) {
                try {
                    val plan = BackupImportMapper.planNovel(
                        novel = novel,
                        chapters = chaptersByNovel[novel.novelId].orEmpty(),
                        historyRows = historyByNovel[novel.novelId].orEmpty(),
                        chapterUrlByBackupChapterId = chapterUrlByBackupId,
                    )
                    plannedNovelIds += novel.novelId
                    plan.historySkips.forEach { reason ->
                        skippedHistory += BackupItemSkip(novel.novelName, reason)
                    }
                    val sourceRow = sourceRows.getValue(novel.sourceId)
                    if (sourceRow.distUrl.isEmpty()) {
                        needsSource += NeedsSourceNovel(novel.novelName, novel.source, novel.sourceId)
                    }

                    val existing = novelDao.findBySourceNovel(sourceRow.id, plan.sourceNovelId)
                    val storedChapters = existing?.let { chapterDao.findByNovel(it.id) }.orEmpty()
                    val storedIdByUrl = storedChapters.associate { it.chapterUrl to it.id }
                    val outcome = BackupImportMapper.mergeNovel(
                        plan = plan,
                        existing = BackupImportMapper.ExistingNovelState(
                            title = existing?.title,
                            author = existing?.author,
                            artist = existing?.artist,
                            status = existing?.status,
                            genre = existing?.genre,
                            summary = existing?.summary,
                            coverUrl = existing?.coverUrl,
                            chapterReadByUrl = storedChapters.associate { it.chapterUrl to it.isRead },
                            historyUpdatedAtEpochMs = existing
                                ?.let { chapterDao.findNovelWithProgress(it.id)?.progress?.firstOrNull() }
                                ?.updatedAt,
                        ),
                    )

                    val novelId: Long
                    if (existing == null) {
                        novelId = novelDao.insertNovel(
                            NovelEntity(
                                sourceId = sourceRow.id,
                                sourceNovelId = plan.sourceNovelId,
                                title = plan.title,
                                author = plan.author,
                                artist = plan.artist,
                                status = plan.status,
                                genre = plan.genre,
                                summary = plan.summary,
                                coverUrl = plan.coverUrl,
                                url = plan.sourceNovelId,
                                inLibrary = true,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                        restored++
                    } else {
                        novelId = existing.id
                        novelDao.upsertNovels(
                            listOf(
                                existing.copy(
                                    title = outcome.title ?: existing.title,
                                    author = outcome.author ?: existing.author,
                                    artist = outcome.artist ?: existing.artist,
                                    status = outcome.status ?: existing.status,
                                    genre = outcome.genre ?: existing.genre,
                                    summary = outcome.summary ?: existing.summary,
                                    coverUrl = outcome.coverUrl ?: existing.coverUrl,
                                ),
                            ),
                        )
                        merged++
                    }

                    // Imported chapters arrive with the new-arrival state
                    // false: fresh-update marking belongs to update checks.
                    // The chapter's backup position is its sortOrder, so a
                    // merged re-import fills gaps without renumbering the
                    // inserted subset from 0.
                    val toInsert = outcome.chaptersToInsert.map { chapter ->
                        ChapterEntity(
                            novelId = novelId,
                            sourceChapterId = chapter.chapterUrl,
                            chapterUrl = chapter.chapterUrl,
                            title = chapter.title,
                            releaseDate = chapter.releaseDate,
                            sortOrder = chapter.sortOrder,
                            isRead = chapter.isRead,
                            isNew = false,
                            firstSeenAtEpochMs = 0,
                        )
                    }
                    if (toInsert.isNotEmpty()) {
                        chapterDao.upsertChapters(toInsert)
                        chaptersInserted += toInsert.size
                        readMarks += toInsert.count { it.isRead }
                    }
                    for (upgrade in outcome.readUpgrades) {
                        val chapterId = storedIdByUrl[upgrade.chapterUrl] ?: continue
                        chapterDao.setChapterRead(novelId, chapterId, true)
                        readMarks++
                    }

                    val history = outcome.history
                    if (history != null) {
                        // The upsert does not report row ids; re-read the
                        // novel's chapters to resolve the history pointer.
                        val idByUrl = if (toInsert.isEmpty()) {
                            storedIdByUrl
                        } else {
                            chapterDao.findByNovel(novelId).associate { it.chapterUrl to it.id }
                        }
                        val chapterId = idByUrl[history.chapterUrl]
                        if (chapterId == null) {
                            skippedHistory += BackupItemSkip(
                                novel.novelName,
                                "history chapter \"${history.chapterUrl}\" is missing after import",
                            )
                        } else {
                            readingProgressDao.upsert(
                                // The v1 format carries no reader position:
                                // offset and percent stay unset, and the
                                // result dialog says so.
                                ReadingProgressEntity(
                                    novelId = novelId,
                                    chapterId = chapterId,
                                    scrollOffset = 0,
                                    percent = 0,
                                    updatedAt = history.readAtEpochMs,
                                ),
                            )
                            historyRestored++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    skippedNovels += BackupItemSkip(novel.novelName, e.message ?: "unknown failure")
                }
            }

            // Chapters and history rows whose novel did not import are
            // skipped with a reason, never dropped silently.
            val skippedChapters = parsed.skippedChapters + file.chapters
                .filter { it.novelId !in plannedNovelIds }
                .map { BackupItemSkip(it.chapterUrl, "its novel is not part of this import") }
            val orphanHistory = parsed.skippedHistory + file.history
                .filter { it.historyNovelId !in plannedNovelIds }
                .map {
                    BackupItemSkip(
                        "history for novel ${it.historyNovelId}",
                        "its novel is not part of this import",
                    )
                }

            val counts = BackupImportMapper.skipCounts(parsed)
            BackupImportResult(
                novelsRestored = restored,
                novelsMerged = merged,
                chaptersInserted = chaptersInserted,
                readMarksApplied = readMarks,
                historyRestored = historyRestored,
                needsSource = needsSource,
                skippedNovels = skippedNovels + parsed.skippedNovels,
                skippedChapters = skippedChapters,
                skippedHistory = skippedHistory + orphanHistory,
                categoriesSkipped = counts.categoriesSkipped,
                bookmarksSkipped = counts.bookmarksSkipped,
            )
        }
    }

    /**
     * Registers a disabled, repo-less row for every v1 plugin id the file
     * needs that has no source yet (the source registration path); novels
     * landing under those rows are needs-source until a repository with the
     * plugin id claims them.
     */
    private suspend fun ensureSourceRows(
        novels: List<LnReaderBackupNovel>,
    ): Map<Long, com.chief.anansi.data.entity.SourceEntity> {
        val displayNames = HashMap<Long, String>()
        novels.forEach { displayNames.putIfAbsent(it.sourceId, it.source) }
        val rows = HashMap<Long, com.chief.anansi.data.entity.SourceEntity>()
        for ((v1SourceId, displayName) in displayNames) {
            val identity = BackupImportMapper.v1EraIdentity(v1SourceId)
            val row = sourceRegistry.findByIdentity(identity)
                ?: sourceRegistry.registerNeedsSource(
                    ecosystem = SourceEcosystem.LNREADER,
                    sourceId = v1SourceId.toString(),
                    displayName = displayName,
                )
            rows[v1SourceId] = row
        }
        return rows
    }
}
