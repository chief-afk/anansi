package com.chief.anansi.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Parser fixture tests: shape dispatch over the four
 * committed synthetic v1-format fixtures, field binding against the fork
 * fixture, unknown-key tolerance, per-novel isolation, and the loud
 * top-level failure cases with their named reasons.
 */
class LnReaderBackupParserTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/v1backup/$name")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    @Test
    fun forkFixtureDispatchesAsForkShape() {
        val parsed = LnReaderBackupParser.parse(fixture("fork-backup-fixture.json"))
        assertEquals("lnreader-fork-backup", parsed.file.format)
        assertEquals(1, parsed.file.version)
        assertEquals("2026-01-02T03:04:05.678Z", parsed.file.exportedAt)
        assertEquals(2, parsed.file.novels.size)
        assertEquals(2, parsed.file.categories.size)
        assertEquals(4, parsed.file.chapters.size)
        assertEquals(1, parsed.file.history.size)
        assertTrue(parsed.skippedNovels.isEmpty())
        assertTrue(parsed.skippedChapters.isEmpty())
        assertTrue(parsed.skippedHistory.isEmpty())
        assertEquals(0, parsed.skippedCategories)
    }

    @Test
    fun forkFixtureBindsNovelFieldsByName() {
        val novel = LnReaderBackupParser.parse(fixture("fork-backup-fixture.json"))
            .file.novels.first()
        assertEquals(11L, novel.novelId)
        assertEquals("synthetic-peak-of-the-void/", novel.novelUrl)
        assertEquals("https://voidlibrary.example/synthetic-peak-of-the-void", novel.sourceUrl)
        assertEquals(731L, novel.sourceId)
        assertEquals("VoidLibrary", novel.source)
        assertEquals("Peak of the Void", novel.novelName)
        assertEquals("https://voidlibrary.example/covers/peak-of-the-void.webp", novel.novelCover)
        assertEquals("A lone cultivator climbs a mountain that erases names.", novel.novelSummary)
        assertEquals("Invented Author", novel.author)
        assertNull(novel.artist)
        assertEquals("Ongoing", novel.status)
        assertEquals("Action, Fantasy", novel.genre)
        assertEquals(1, novel.followed)
        assertEquals(0, novel.unread)
        assertEquals("[3]", novel.categoryIds)
    }

    @Test
    fun forkFixtureBindsChapterVariants() {
        val chapters = LnReaderBackupParser.parse(fixture("fork-backup-fixture.json"))
            .file.chapters
        val readChapter = chapters.first { it.chapterId == 101L }
        assertEquals("void-chapter-1/", readChapter.chapterUrl)
        assertEquals("The Empty Gate", readChapter.chapterName)
        assertEquals("2025-01-01", readChapter.releaseDate)
        assertEquals(1, readChapter.read)
        assertEquals(0, readChapter.bookmark)

        // Bookmark-only chapter: bookmarked but not read.
        val bookmarkOnly = chapters.first { it.chapterId == 102L }
        assertEquals(1, bookmarkOnly.bookmark)
        assertEquals(0, bookmarkOnly.read)

        // Unread chapter whose url is an arbitrary plugin value.
        val separator = chapters.first { it.chapterId == 103L }
        assertEquals("#F4F4F4", separator.chapterUrl)
        assertEquals(0, separator.read)

        // Optional fields may be absent entirely.
        val minimal = chapters.first { it.chapterId == 104L }
        assertNull(minimal.releaseDate)
        assertEquals("Salt and Ink", minimal.chapterName)
    }

    @Test
    fun forkFixtureBindsCategoryAndHistoryFields() {
        val file = LnReaderBackupParser.parse(fixture("fork-backup-fixture.json")).file
        val category = file.categories.first { it.id == 3L }
        assertEquals("Reading now", category.name)
        assertEquals(1L, category.sort)
        assertNull(file.categories.first { it.id == 4L }.sort)
        val history = file.history.single()
        assertEquals(11L, history.historyNovelId)
        assertEquals(101L, history.historyChapterId)
        assertEquals("2026-01-03 21:15:00", history.historyTimeRead)
    }

    @Test
    fun unknownKeysAreIgnored() {
        // The fork fixture's first novel carries "someFutureField"; the
        // binding above already proves the ignoreUnknownKeys rule.
        val parsed = LnReaderBackupParser.parse(fixture("fork-backup-fixture.json"))
        assertEquals(0, parsed.skippedNovels.size)
    }

    @Test
    fun officialFixtureDispatchesAsBareArrayShape() {
        val parsed = LnReaderBackupParser.parse(fixture("official-backup-fixture.json"))
        assertEquals(1, parsed.file.novels.size)
        val novel = parsed.file.novels.single()
        assertEquals("Lantern Harbor", novel.novelName)
        assertEquals(755L, novel.sourceId)
        assertEquals("synthetic-lantern-harbor/", novel.novelUrl)
        assertTrue(parsed.file.chapters.isEmpty())
        assertTrue(parsed.file.history.isEmpty())
        assertTrue(parsed.file.categories.isEmpty())
    }

    @Test
    fun truncatedFileFailsLoudlyNamingTheJsonPosition() {
        try {
            LnReaderBackupParser.parse(fixture("malformed-truncated.json"))
            fail("expected LnReaderBackupFormatException")
        } catch (e: LnReaderBackupFormatException) {
            assertTrue(
                "unexpected reason: ${e.message}",
                e.message!!.startsWith("The backup file is not valid JSON"),
            )
        }
    }

    @Test
    fun objectWithoutNovelsArrayFailsLoudlyNamingWhatWasExpected() {
        try {
            LnReaderBackupParser.parse(fixture("malformed-wrong-root.json"))
            fail("expected LnReaderBackupFormatException")
        } catch (e: LnReaderBackupFormatException) {
            assertTrue(
                "unexpected reason: ${e.message}",
                e.message!!.contains("Not a recognizable LNReader backup file") &&
                    e.message!!.contains("\"novels\""),
            )
        }
    }

    @Test
    fun nonJsonRootFailsLoudly() {
        try {
            LnReaderBackupParser.parse("just some text")
            fail("expected LnReaderBackupFormatException")
        } catch (e: LnReaderBackupFormatException) {
            assertTrue(e.message!!.startsWith("The backup file is not valid JSON"))
        }
    }

    @Test
    fun scalarRootFailsLoudly() {
        try {
            LnReaderBackupParser.parse("42")
            fail("expected LnReaderBackupFormatException")
        } catch (e: LnReaderBackupFormatException) {
            assertTrue(e.message!!.contains("Not a recognizable LNReader backup file"))
        }
    }

    @Test
    fun novelMissingRequiredFieldIsSkippedAndRestContinues() {
        val text = """
            [
              {"novelId": 1, "novelName": "Missing Url", "sourceId": 731, "source": "X", "sourceUrl": "https://x.example/a"},
              {"novelId": 2, "novelUrl": "fine/", "novelName": "Fine Novel", "sourceId": 742, "source": "Y", "sourceUrl": "https://y.example/b"}
            ]
        """.trimIndent()
        val parsed = LnReaderBackupParser.parse(text)
        assertEquals(1, parsed.file.novels.size)
        assertEquals("Fine Novel", parsed.file.novels.single().novelName)
        assertEquals(1, parsed.skippedNovels.size)
        assertEquals("Missing Url", parsed.skippedNovels.single().label)
        assertNotNull(parsed.skippedNovels.single().reason)
    }

    @Test
    fun forkNovelMissingRequiredFieldIsSkippedAndRestContinues() {
        val text = """
            {
              "format": "lnreader-fork-backup",
              "version": 1,
              "novels": [
                {"novelId": 1, "novelName": "No Source Id", "novelUrl": "broken/", "source": "X", "sourceUrl": "https://x.example/a"},
                {"novelId": 2, "novelUrl": "fine/", "novelName": "Fine Novel", "sourceId": 742, "source": "Y", "sourceUrl": "https://y.example/b"}
              ]
            }
        """.trimIndent()
        val parsed = LnReaderBackupParser.parse(text)
        assertEquals(1, parsed.file.novels.size)
        assertEquals(1, parsed.skippedNovels.size)
        assertEquals("No Source Id", parsed.skippedNovels.single().label)
    }

    @Test
    fun malformedChapterAndHistoryItemsAreSkippedWithReasons() {
        val text = """
            {
              "format": "lnreader-fork-backup",
              "novels": [{"novelId": 1, "novelUrl": "fine/", "novelName": "Fine", "sourceId": 742, "source": "Y", "sourceUrl": "https://y.example/b"}],
              "chapters": [
                {"chapterId": 5, "novelId": 1, "chapterName": "No Url"},
                {"chapterId": 6, "chapterUrl": "ok/", "novelId": 1, "chapterName": "Ok"}
              ],
              "history": [{"historyNovelId": 1, "historyTimeRead": "2026-01-03 21:15:00"}]
            }
        """.trimIndent()
        val parsed = LnReaderBackupParser.parse(text)
        assertEquals(1, parsed.file.chapters.size)
        assertEquals(1, parsed.skippedChapters.size)
        assertEquals(0, parsed.file.history.size)
        assertEquals(1, parsed.skippedHistory.size)
    }
}
