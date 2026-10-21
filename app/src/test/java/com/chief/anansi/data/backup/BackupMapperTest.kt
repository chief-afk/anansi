package com.chief.anansi.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapper tests: novel/chapter/history mapping to
 * the domain models, identity resolution through the registration minting rule,
 * the merge-upgrade rules (read false to true only, metadata fills empty
 * fields only, history newer-wins), and the skipped-categories and
 * skipped-bookmarks summary counts.
 */
class BackupMapperTest {

    private fun novel(
        novelId: Long = 11,
        novelUrl: String = "synthetic-peak-of-the-void/",
        sourceId: Long = 731,
        source: String = "VoidLibrary",
        novelName: String = "Peak of the Void",
    ) = LnReaderBackupNovel(
        novelId = novelId,
        novelUrl = novelUrl,
        sourceUrl = "https://voidlibrary.example/$novelUrl",
        sourceId = sourceId,
        source = source,
        novelName = novelName,
        author = "Invented Author",
        artist = "Invented Artist",
        status = "Ongoing",
    )

    private fun chapter(
        chapterId: Long,
        chapterUrl: String,
        novelId: Long = 11,
        read: Int? = 0,
        bookmark: Int? = 0,
    ) = LnReaderBackupChapter(
        chapterId = chapterId,
        chapterUrl = chapterUrl,
        novelId = novelId,
        chapterName = "Chapter $chapterId",
        releaseDate = "2025-01-0$chapterId",
        bookmark = bookmark,
        read = read,
    )

    private fun historyRow(
        historyNovelId: Long = 11,
        historyChapterId: Long,
        historyTimeRead: String,
    ) = LnReaderBackupHistoryRow(historyNovelId, historyChapterId, historyTimeRead)

    @Test
    fun v1NumericSourceIdMintsTheDeterministicV1EraIdentity() {
        assertEquals("lnreader_731", BackupImportMapper.v1EraIdentity(731))
        assertEquals("lnreader_8", BackupImportMapper.v1EraIdentity(8))
        // Same rule registration applies: ECOSYSTEM_{sourceId}.
        assertEquals(
            com.chief.anansi.data.repo.SourceEcosystem.LNREADER.identity("731"),
            BackupImportMapper.v1EraIdentity(731),
        )
    }

    @Test
    fun planNovelMapsNovelChapterAndHistoryFields() {
        val plan = BackupImportMapper.planNovel(
            novel = novel(),
            chapters = listOf(
                chapter(1, "void-chapter-1/", read = 1),
                chapter(2, "void-chapter-2/"),
            ),
            historyRows = listOf(historyRow(historyChapterId = 1, historyTimeRead = "2026-01-03 21:15:00")),
            chapterUrlByBackupChapterId = mapOf(1L to "void-chapter-1/", 2L to "void-chapter-2/"),
        )
        assertEquals("lnreader_731", plan.identity)
        assertEquals(731L, plan.v1SourceId)
        assertEquals("VoidLibrary", plan.sourceDisplayName)
        assertEquals("synthetic-peak-of-the-void/", plan.sourceNovelId)
        assertEquals("Peak of the Void", plan.title)
        assertEquals(2, plan.chapters.size)
        assertEquals("void-chapter-1/", plan.chapters[0].chapterUrl)
        assertEquals("Chapter 1", plan.chapters[0].title)
        assertEquals("2025-01-01", plan.chapters[0].releaseDate)
        assertTrue(plan.chapters[0].isRead)
        assertTrue(!plan.chapters[1].isRead)
        assertEquals("void-chapter-1/", plan.history!!.chapterUrl)
        assertEquals(
            BackupImportMapper.parseHistoryTime("2026-01-03 21:15:00"),
            plan.history!!.readAtEpochMs,
        )
        assertTrue(plan.historySkips.isEmpty())
    }

    @Test
    fun planNovelHistoryPicksTheNewestParseableRow() {
        val plan = BackupImportMapper.planNovel(
            novel = novel(),
            chapters = listOf(chapter(1, "void-chapter-1/")),
            historyRows = listOf(
                historyRow(historyChapterId = 1, historyTimeRead = "2026-01-03 21:15:00"),
                historyRow(historyChapterId = 1, historyTimeRead = "2026-02-04 08:00:00"),
            ),
            chapterUrlByBackupChapterId = mapOf(1L to "void-chapter-1/"),
        )
        assertEquals(
            BackupImportMapper.parseHistoryTime("2026-02-04 08:00:00"),
            plan.history!!.readAtEpochMs,
        )
    }

    @Test
    fun planNovelReportsUnmappedChaptersAndBadTimestampsAsSkips() {
        val plan = BackupImportMapper.planNovel(
            novel = novel(),
            chapters = listOf(chapter(1, "void-chapter-1/")),
            historyRows = listOf(
                historyRow(historyChapterId = 99, historyTimeRead = "2026-01-03 21:15:00"),
                historyRow(historyChapterId = 1, historyTimeRead = "not a timestamp"),
            ),
            chapterUrlByBackupChapterId = mapOf(1L to "void-chapter-1/"),
        )
        assertNull(plan.history)
        assertEquals(2, plan.historySkips.size)
    }

    @Test
    fun historyTimeParsesTheV1FormatAndRejectsGarbage() {
        val expected = java.time.LocalDateTime.of(2026, 10, 11, 20, 30, 38)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, BackupImportMapper.parseHistoryTime("2026-10-11 20:30:38"))
        assertNull(BackupImportMapper.parseHistoryTime("2026-10-11T20:30:38"))
        assertNull(BackupImportMapper.parseHistoryTime(""))
    }

    @Test
    fun freshImportFillsEverythingAndInsertsAllChapters() {
        val plan = BackupImportMapper.planNovel(
            novel = novel(),
            chapters = listOf(chapter(1, "void-chapter-1/", read = 1), chapter(2, "void-chapter-2/")),
            historyRows = emptyList(),
            chapterUrlByBackupChapterId = emptyMap(),
        )
        val outcome = BackupImportMapper.mergeNovel(plan, BackupImportMapper.ExistingNovelState())
        assertEquals("Peak of the Void", outcome.title)
        assertEquals(2, outcome.chaptersToInsert.size)
        assertTrue(outcome.readUpgrades.isEmpty())
        assertNull(outcome.history)
    }

    @Test
    fun mergeFillsOnlyEmptyMetadataFields() {
        val plan = BackupImportMapper.planNovel(
            novel = novel(),
            chapters = emptyList(),
            historyRows = emptyList(),
            chapterUrlByBackupChapterId = emptyMap(),
        )
        val outcome = BackupImportMapper.mergeNovel(
            plan,
            BackupImportMapper.ExistingNovelState(
                title = "Stored Title",
                author = null,
                artist = "",
                status = "Hiatus",
            ),
        )
        assertNull(outcome.title)
        assertEquals("Invented Author", outcome.author)
        assertEquals("Invented Artist", outcome.artist)
        assertNull(outcome.status)
    }

    @Test
    fun readMarksUpgradeFalseToTrueAndNeverDowngrade() {
        val plan = BackupImportMapper.planNovel(
            novel = novel(),
            chapters = listOf(
                chapter(1, "was-unread/", read = 1),
                chapter(2, "already-read/", read = 0),
                chapter(3, "still-unread/", read = 0),
            ),
            historyRows = emptyList(),
            chapterUrlByBackupChapterId = emptyMap(),
        )
        val outcome = BackupImportMapper.mergeNovel(
            plan,
            BackupImportMapper.ExistingNovelState(
                chapterReadByUrl = mapOf(
                    "was-unread/" to false,
                    "already-read/" to true,
                    "still-unread/" to false,
                ),
            ),
        )
        assertEquals(listOf("was-unread/"), outcome.readUpgrades.map { it.chapterUrl })
        assertTrue(outcome.chaptersToInsert.isEmpty())
    }

    @Test
    fun missingChaptersInsertAndExistingOnesStay() {
        val plan = BackupImportMapper.planNovel(
            novel = novel(),
            chapters = listOf(
                chapter(1, "stored/"),
                chapter(2, "new-arrival/"),
            ),
            historyRows = emptyList(),
            chapterUrlByBackupChapterId = emptyMap(),
        )
        val outcome = BackupImportMapper.mergeNovel(
            plan,
            BackupImportMapper.ExistingNovelState(
                chapterReadByUrl = mapOf("stored/" to false),
            ),
        )
        assertEquals(listOf("new-arrival/"), outcome.chaptersToInsert.map { it.chapterUrl })
    }

    @Test
    fun historyAppliesOnlyWhenNewerThanStored() {
        val plan = BackupImportMapper.planNovel(
            novel = novel(),
            chapters = listOf(chapter(1, "void-chapter-1/")),
            historyRows = listOf(historyRow(historyChapterId = 1, historyTimeRead = "2026-01-03 21:15:00")),
            chapterUrlByBackupChapterId = mapOf(1L to "void-chapter-1/"),
        )
        val newer = BackupImportMapper.parseHistoryTime("2026-01-03 21:15:00")!!

        // Nothing stored yet: the imported history applies.
        val fresh = BackupImportMapper.mergeNovel(plan, BackupImportMapper.ExistingNovelState())
        assertEquals(newer, fresh.history!!.readAtEpochMs)

        // Stored history is older: the import wins.
        val older = BackupImportMapper.mergeNovel(
            plan,
            BackupImportMapper.ExistingNovelState(historyUpdatedAtEpochMs = newer - 1),
        )
        assertEquals(newer, older.history!!.readAtEpochMs)

        // Stored history is newer: the import never moves it backwards.
        val stored = BackupImportMapper.mergeNovel(
            plan,
            BackupImportMapper.ExistingNovelState(historyUpdatedAtEpochMs = newer + 1),
        )
        assertNull(stored.history)

        // Same timestamp: the stored row stays (no pointless rewrite).
        val equal = BackupImportMapper.mergeNovel(
            plan,
            BackupImportMapper.ExistingNovelState(historyUpdatedAtEpochMs = newer),
        )
        assertNull(equal.history)
    }

    @Test
    fun skipCountsCoverCategoriesAndBookmarkedChapters() {
        val parsed = LnReaderBackupParser.parse(
            javaClass.getResourceAsStream("/v1backup/fork-backup-fixture.json")!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
        val counts = BackupImportMapper.skipCounts(parsed)
        // Both categories of the fixture (storage is the Categories backlog
        // row) and the one bookmark-only chapter (the Downloads manager row).
        assertEquals(2, counts.categoriesSkipped)
        assertEquals(1, counts.bookmarksSkipped)
    }
}
