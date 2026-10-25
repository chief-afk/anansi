package com.chief.anansi.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure splitter tests: the Arabic question mark and the ellipsis end
 * sentences with offsets pinned into the original string, and the
 * ASCII/CJK terminator sets plus the blank-line paragraph model keep
 * their existing splits and offsets.
 */
class SentenceSplitterTest {

    @Test
    fun arabicQuestionMarkEndsEachSentenceAtTheMark() {
        val text = "من أنت؟ أنا أحب القراءة؟ كيف حالك؟"
        val sentences = SentenceSplitter.layout(text).sentences

        assertEquals(3, sentences.size)
        assertEquals("من أنت؟", sentences[0].text)
        assertEquals(0, sentences[0].start)
        assertEquals(7, sentences[0].end)
        assertEquals("أنا أحب القراءة؟", sentences[1].text)
        assertEquals(8, sentences[1].start)
        assertEquals(24, sentences[1].end)
        assertEquals("كيف حالك؟", sentences[2].text)
        assertEquals(25, sentences[2].start)
        assertEquals(34, sentences[2].end)
    }

    @Test
    fun ellipsisEndsASentenceAndAdjacentProseStartsANewOne() {
        val text = "He waited… then spoke"
        val sentences = SentenceSplitter.layout(text).sentences

        assertEquals(2, sentences.size)
        assertEquals("He waited…", sentences[0].text)
        assertEquals(0, sentences[0].start)
        assertEquals(10, sentences[0].end)
        assertEquals("then spoke", sentences[1].text)
        assertEquals(11, sentences[1].start)
        assertEquals(21, sentences[1].end)
    }

    @Test
    fun asciiCjkSetsAndParagraphBreaksKeepTheirSplitsAndOffsets() {
        val text = "First one. Second! Third? Fourth。Fifth！Sixth？\n\nNew paragraph here."
        val layout = SentenceSplitter.layout(text)

        val sentences = layout.sentences
        assertEquals(
            listOf(
                "First one.",
                "Second!",
                "Third?",
                "Fourth。",
                "Fifth！",
                "Sixth？",
                "New paragraph here.",
            ),
            sentences.map { it.text },
        )
        assertEquals(listOf(0, 11, 19, 26, 33, 39, 47), sentences.map { it.start })
        assertEquals(listOf(10, 18, 25, 33, 39, 45, 66), sentences.map { it.end })

        assertEquals(2, layout.paragraphs.size)
        assertEquals("First one. Second! Third? Fourth。Fifth！Sixth？", layout.paragraphs[0].text)
        assertEquals(0, layout.paragraphs[0].start)
        assertEquals(45, layout.paragraphs[0].end)
        assertEquals("New paragraph here.", layout.paragraphs[1].text)
        assertEquals(47, layout.paragraphs[1].start)
        assertEquals(66, layout.paragraphs[1].end)
    }
}
