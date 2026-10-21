package com.chief.anansi.data.backup

import com.chief.anansi.data.repo.SourceEcosystem
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The pure DTO-to-domain mapper for LNReader v1 backup imports: novel and
 * chapter plans keyed the way the Anansi schema keys them, identity
 * resolution through the registration minting rule, history remapped through the
 * backup's own chapter list, and the merge-upgrade comparison against the
 * stored rows. No Android, no Room: the import repository applies what this
 * mapper decides, and the unit tests pin every rule here.
 */
object BackupImportMapper {

    private val HISTORY_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * The v1-era ecosystem identity a backup's numeric plugin id maps onto,
     * computed with the same deterministic minting rule registration uses.
     * A novel whose plugin is not installed still carries its final
     * identity, so adding a repository with that plugin id re-links it.
     */
    fun v1EraIdentity(v1SourceId: Long): String =
        SourceEcosystem.LNREADER.identity(v1SourceId.toString())

    /** One chapter the import wants stored, in the backup's array order. */
    data class PlannedChapter(
        val chapterUrl: String,
        val title: String,
        val releaseDate: String?,
        val isRead: Boolean,
    )

    /** The novel's single history pointer, already remapped to a chapter url. */
    data class PlannedHistory(val chapterUrl: String, val readAtEpochMs: Long)

    /** One novel's full import intent. */
    data class NovelPlan(
        val identity: String,
        val v1SourceId: Long,
        val sourceDisplayName: String,
        /** The plugin-relative path, the novel's source-local identity. */
        val sourceNovelId: String,
        val title: String,
        val author: String?,
        val artist: String?,
        val status: String?,
        val genre: String?,
        val summary: String?,
        val coverUrl: String?,
        val chapters: List<PlannedChapter>,
        val history: PlannedHistory?,
        /** Why backup history rows for this novel could not be applied. */
        val historySkips: List<String>,
    )

    /**
     * Maps one backup novel plus its chapters and history rows into a plan.
     * `history` picks the newest parseable row; unmapped chapter pointers
     * and unparseable timestamps come back as [NovelPlan.historySkips].
     */
    fun planNovel(
        novel: LnReaderBackupNovel,
        chapters: List<LnReaderBackupChapter>,
        historyRows: List<LnReaderBackupHistoryRow>,
        chapterUrlByBackupChapterId: Map<Long, String>,
    ): NovelPlan {
        val planned = chapters.map { chapter ->
            PlannedChapter(
                chapterUrl = chapter.chapterUrl,
                title = chapter.chapterName,
                releaseDate = chapter.releaseDate?.takeIf { it.isNotEmpty() },
                isRead = chapter.read == 1,
            )
        }
        val skips = ArrayList<String>()
        var newest: PlannedHistory? = null
        for (row in historyRows) {
            val chapterUrl = chapterUrlByBackupChapterId[row.historyChapterId]
            if (chapterUrl == null) {
                skips += "history chapter ${row.historyChapterId} is not in the backup's chapter list"
                continue
            }
            val readAt = parseHistoryTime(row.historyTimeRead)
            if (readAt == null) {
                skips += "history time \"${row.historyTimeRead}\" is not in the yyyy-MM-dd HH:mm:ss format"
                continue
            }
            if (newest == null || readAt > newest.readAtEpochMs) {
                newest = PlannedHistory(chapterUrl, readAt)
            }
        }
        return NovelPlan(
            identity = v1EraIdentity(novel.sourceId),
            v1SourceId = novel.sourceId,
            sourceDisplayName = novel.source,
            sourceNovelId = novel.novelUrl,
            title = novel.novelName,
            author = novel.author?.takeIf { it.isNotEmpty() },
            artist = novel.artist?.takeIf { it.isNotEmpty() },
            status = novel.status?.takeIf { it.isNotEmpty() },
            genre = novel.genre?.takeIf { it.isNotEmpty() },
            summary = novel.novelSummary?.takeIf { it.isNotEmpty() },
            coverUrl = novel.novelCover?.takeIf { it.isNotEmpty() },
            chapters = planned,
            history = newest,
            historySkips = skips,
        )
    }

    /** The stored state the merge comparison needs; all defaults for a fresh import. */
    data class ExistingNovelState(
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val status: String? = null,
        val genre: String? = null,
        val summary: String? = null,
        val coverUrl: String? = null,
        val chapterReadByUrl: Map<String, Boolean> = emptyMap(),
        val historyUpdatedAtEpochMs: Long? = null,
    )

    /** What to write for one novel; null fields keep the stored value. */
    data class NovelMergeOutcome(
        val title: String?,
        val author: String?,
        val artist: String?,
        val status: String?,
        val genre: String?,
        val summary: String?,
        val coverUrl: String?,
        val chaptersToInsert: List<PlannedChapter>,
        /** Stored chapters whose read mark upgrades from false to true. */
        val readUpgrades: List<PlannedChapter>,
        /** The history pointer to apply, or null when the stored one wins. */
        val history: PlannedHistory?,
    )

    /**
     * The merge-only upgrade rules: metadata fills only where currently
     * empty, missing chapters insert, read marks upgrade from false to true
     * and never regress, and history applies only when it is newer than the
     * stored row (always, when nothing is stored yet).
     */
    fun mergeNovel(plan: NovelPlan, existing: ExistingNovelState): NovelMergeOutcome {
        val inserts = ArrayList<PlannedChapter>()
        val upgrades = ArrayList<PlannedChapter>()
        for (chapter in plan.chapters) {
            when (existing.chapterReadByUrl[chapter.chapterUrl]) {
                null -> inserts += chapter
                false -> if (chapter.isRead) upgrades += chapter
                else -> Unit // stored read mark is already true; never downgrade
            }
        }
        val historyApplies = plan.history != null &&
            (existing.historyUpdatedAtEpochMs == null || plan.history.readAtEpochMs > existing.historyUpdatedAtEpochMs)
        return NovelMergeOutcome(
            title = plan.title.takeIf { existing.title.isNullOrEmpty() },
            author = plan.author.takeIf { existing.author.isNullOrEmpty() },
            artist = plan.artist.takeIf { existing.artist.isNullOrEmpty() },
            status = plan.status.takeIf { existing.status.isNullOrEmpty() },
            genre = plan.genre.takeIf { existing.genre.isNullOrEmpty() },
            summary = plan.summary.takeIf { existing.summary.isNullOrEmpty() },
            coverUrl = plan.coverUrl.takeIf { existing.coverUrl.isNullOrEmpty() },
            chaptersToInsert = inserts,
            readUpgrades = upgrades,
            history = plan.history.takeIf { historyApplies },
        )
    }

    /**
     * The data the v0.1.0 schema cannot store, counted for the result
     * dialog's "never drops data silently" lines: every category
     * Categories) and every bookmarked chapter (downloads
     * manager).
     */
    data class SkipCounts(val categoriesSkipped: Int, val bookmarksSkipped: Int)

    fun skipCounts(parsed: ParsedLnReaderBackup): SkipCounts = SkipCounts(
        categoriesSkipped = parsed.file.categories.size + parsed.skippedCategories,
        bookmarksSkipped = parsed.file.chapters.count { it.bookmark == 1 },
    )

    /** Parses the v1 history timestamp into epoch milliseconds; null when malformed. */
    fun parseHistoryTime(text: String): Long? = try {
        LocalDateTime.parse(text, HISTORY_TIME_FORMAT)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}
