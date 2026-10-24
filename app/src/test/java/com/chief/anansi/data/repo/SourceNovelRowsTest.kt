package com.chief.anansi.data.repo

import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.plugin.SourceChapterItem
import com.chief.anansi.plugin.SourceNovel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure row-creation mapper tests: the unshelved novel row, the chapter
 * rows' path keying and source order, and the duplicate-path collapse the
 * (novelId, chapterUrl) unique index depends on. The collapse is also the
 * first-occurrence-wins, order-stable dedup contract the search and details
 * lists apply with the same distinctBy shape.
 */
class SourceNovelRowsTest {

    private fun source(sourceId: Long = 42) = SourceEntity(
        id = sourceId,
        ecosystem = "lnreader",
        sourceId = "13",
        identity = "lnreader_13",
        numericId = 13,
        displayName = "Fixture Source",
    )

    private fun chapter(path: String, name: String = "Chapter") =
        SourceChapterItem(name = name, path = path)

    @Test
    fun novelRowIsUnshelvedAndKeyedByPath() {
        val (novel, chapters) = sourceNovelRows(
            source = source(),
            novelPath = "shadow-slave-novel/",
            details = SourceNovel(
                path = "shadow-slave-novel/",
                name = "Shadow Slave",
                author = "Guiltythree",
                chapters = listOf(chapter("shadow-slave/chapter-1")),
            ),
            now = 1_700_000_000_000,
        )

        assertEquals(42, novel.sourceId)
        assertEquals("shadow-slave-novel/", novel.sourceNovelId)
        assertEquals("shadow-slave-novel/", novel.url)
        assertEquals("Shadow Slave", novel.title)
        assertEquals("Guiltythree", novel.author)
        assertFalse("lazy reader rows never shelve the novel", novel.inLibrary)
        assertEquals(1_700_000_000_000, novel.addedAt)
        assertEquals(1, chapters.size)
    }

    @Test
    fun missingNameFallsBackToNovelPath() {
        val (novel, _) = sourceNovelRows(
            source = source(),
            novelPath = "unnamed-novel/",
            details = SourceNovel(path = "unnamed-novel/", chapters = emptyList()),
            now = 0,
        )
        assertEquals("unnamed-novel/", novel.title)
    }

    @Test
    fun chapterRowsKeyOnPathInSourceOrder() {
        val (_, chapters) = sourceNovelRows(
            source = source(),
            novelPath = "ordered-novel/",
            details = SourceNovel(
                path = "ordered-novel/",
                name = "Ordered",
                chapters = listOf(
                    chapter("ordered/chapter-2", "Second"),
                    chapter("ordered/chapter-1", "First"),
                    chapter("ordered/chapter-3", "Third"),
                ),
            ),
            now = 0,
        )

        assertEquals(listOf("Second", "First", "Third"), chapters.map { it.title })
        assertEquals(
            listOf("ordered/chapter-2", "ordered/chapter-1", "ordered/chapter-3"),
            chapters.map { it.chapterUrl },
        )
        assertEquals(
            listOf("ordered/chapter-2", "ordered/chapter-1", "ordered/chapter-3"),
            chapters.map { it.sourceChapterId },
        )
        assertEquals(chapters.map { it.sortOrder }, chapters.indices.toList())
        assertTrue(chapters.all { !it.isRead })
    }

    @Test
    fun duplicatePathsCollapseToFirstOccurrenceInOrder() {
        val (_, chapters) = sourceNovelRows(
            source = source(),
            novelPath = "dup-novel/",
            details = SourceNovel(
                path = "dup-novel/",
                name = "Dup",
                chapters = listOf(
                    chapter("dup/chapter-1", "First"),
                    chapter("dup/chapter-2", "Second"),
                    chapter("dup/chapter-1", "Impostor"),
                    chapter("dup/chapter-3", "Third"),
                    chapter("dup/chapter-2", "Duplicate Second"),
                ),
            ),
            now = 0,
        )

        assertEquals(listOf("First", "Second", "Third"), chapters.map { it.title })
        assertEquals(
            listOf("dup/chapter-1", "dup/chapter-2", "dup/chapter-3"),
            chapters.map { it.chapterUrl },
        )
        assertEquals(chapters.map { it.sortOrder }, chapters.indices.toList())
    }
}
