package com.chief.anansi.data.repo

import androidx.room.withTransaction
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.dao.UpdateRow
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.plugin.Source
import com.chief.anansi.ui.browse.failureKindOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * One novel's update-check outcome. `Failed` carries the reason the source
 * adapter raised (the error registry's own wording) with its kind label;
 * `Skipped` means no source call happened at all.
 */
sealed interface NovelCheckResult {
    val novelId: Long

    /** The check ran; [newCount] chapters arrived and are marked new. */
    data class Checked(override val novelId: Long, val newCount: Int) : NovelCheckResult

    /** The source call failed; [reason] and [failureKind] render in the row. */
    data class Failed(
        override val novelId: Long,
        val reason: String,
        val failureKind: String,
    ) : NovelCheckResult

    /** No check ran: the novel's source is unregistered or disabled. */
    data class Skipped(override val novelId: Long) : NovelCheckResult
}

/** The run-level counts the Updates tab's completion snackbar summarizes. */
data class CheckAllSummary(
    val updated: Int,
    val caughtUp: Int,
    val failed: Int,
    val skipped: Int,
)

/**
 * Manual update checks over library novels (the Updates tab's engine; the
 * future background worker will call the same API). Each check fetches the
 * novel's chapter list through its own source's novelDetails operation - the
 * same Source call the details screen uses - diffs it against the stored
 * rows keyed by (novel, chapterUrl), inserts the arrivals marked new, and
 * stamps the novel's last-checked time. A failed source never throws out of
 * a check: it becomes that novel's [NovelCheckResult.Failed] and the run
 * continues.
 */
class UpdateRepository(
    private val database: AnansiDatabase,
    private val sourceRegistry: SourceRegistryRepository,
    private val loadSource: suspend (SourceEntity) -> Source,
) {
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val sourceDao = database.sourceDao()

    /** The Updates list: every library novel with its new-arrival aggregate. */
    fun observeUpdateRows(): Flow<List<UpdateRow>> = novelDao.observeUpdateRows()

    /**
     * Checks every library novel, one at a time in the caller's scope. The
     * callbacks carry per-row progress (the row's spinner, the row's
     * failure); skipped novels get no source call and are only counted.
     */
    suspend fun checkAll(
        onNovelStarted: (Long) -> Unit,
        onNovelResult: (NovelCheckResult) -> Unit,
    ): CheckAllSummary {
        var updated = 0
        var caughtUp = 0
        var failed = 0
        var skipped = 0
        for (novel in novelDao.libraryNovels()) {
            onNovelStarted(novel.id)
            val result = checkNovel(novel.id)
            onNovelResult(result)
            when (result) {
                is NovelCheckResult.Checked -> if (result.newCount > 0) updated++ else caughtUp++
                is NovelCheckResult.Failed -> failed++
                is NovelCheckResult.Skipped -> skipped++
            }
        }
        return CheckAllSummary(updated, caughtUp, failed, skipped)
    }

    /**
     * One novel's check. Novels whose source is gone or disabled are
     * skipped; a failed source call still stamps the novel's checked time
     * (the attempt happened) and returns the failure's own reason.
     */
    suspend fun checkNovel(novelId: Long): NovelCheckResult {
        val novel = novelDao.findById(novelId) ?: return NovelCheckResult.Skipped(novelId)
        if (!novel.inLibrary) return NovelCheckResult.Skipped(novelId)
        val source = sourceDao.findById(novel.sourceId)
            ?: return NovelCheckResult.Skipped(novelId)
        if (!source.enabled) return NovelCheckResult.Skipped(novelId)

        val checkedAt = System.currentTimeMillis()
        return try {
            val fetched = loadSource(source).novelDetails(novel.url)
            val storedUrls = chapterDao.findByNovel(novel.id).mapTo(HashSet()) { it.chapterUrl }
            val arrivals = fetched.chapters
                .mapIndexed { order, chapter ->
                    ChapterEntity(
                        novelId = novel.id,
                        // LNReader chapters carry no separate id; the
                        // verbatim path is the source-local identity.
                        sourceChapterId = chapter.path,
                        chapterUrl = chapter.path,
                        title = chapter.name,
                        releaseDate = chapter.releaseTime,
                        // The fetched list order is the chapter's source
                        // order; the diff keys on the path alone.
                        sortOrder = order,
                        isRead = false,
                        isNew = true,
                        firstSeenAtEpochMs = checkedAt,
                    )
                }
                .filter { it.chapterUrl !in storedUrls }
            database.withTransaction {
                chapterDao.upsertChapters(arrivals)
                novelDao.touchChecked(novel.id, checkedAt)
            }
            NovelCheckResult.Checked(novel.id, arrivals.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            novelDao.touchChecked(novel.id, checkedAt)
            NovelCheckResult.Failed(
                novelId = novel.id,
                reason = e.message ?: "unknown failure",
                failureKind = failureKindOf(e),
            )
        }
    }

    /**
     * Opening a novel clears its new-arrival state (v1's unread-on-open):
     * wired to the details screen open and the reader entry.
     */
    suspend fun clearNewArrivals(novelId: Long) {
        chapterDao.clearNewArrivals(novelId)
    }
}
