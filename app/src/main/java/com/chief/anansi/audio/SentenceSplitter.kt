package com.chief.anansi.audio

/**
 * The sentence layer: a deterministic split of the chapter body
 * into sentences with character offsets into the original string. The
 * sentence index is the one anchor shared by the inline highlight, the
 * mini-bar, the drawer seek, the notification progress, and resume, so the
 * same splitter output must be reachable by both the service (which speaks)
 * and the reader (which highlights): both run it over the same cached text
 * and get identical indexes.
 *
 * Boundaries are sentence terminators (. ! ? plus the CJK set) and paragraph
 * breaks (\n\n, matching the reader's paragraph model); a chapter without
 * terminators or breaks is one sentence.
 */
data class Sentence(
    val text: String,
    /** Offset of the sentence's first character in the chapter text. */
    val start: Int,
    /** Offset past the sentence's last character (exclusive). */
    val end: Int,
)

/** A paragraph block with its offsets in the chapter text. */
data class ParagraphRange(
    val start: Int,
    val end: Int,
    val text: String,
)

/** The paragraph and sentence layout of one chapter body. */
data class ChapterLayout(
    val paragraphs: List<ParagraphRange>,
    val sentences: List<Sentence>,
)

object SentenceSplitter {

    /**
     * Splits the chapter body. Paragraphs mirror the reader's
     * `splitParagraphs` (split on blank lines, trim, drop empties) so
     * paragraph indexes line up with the rendered paragraphs; if the counts
     * ever drift, callers must fall back to no highlight, never to a
     * misplaced one.
     */
    fun layout(text: String): ChapterLayout {
        val paragraphs = buildList {
            var index = 0
            while (index < text.length) {
                val breakAt = text.indexOf("\n\n", index).let { if (it == -1) text.length else it }
                val block = text.substring(index, breakAt)
                val start = index + (block.length - block.trimStart().length)
                val trimmed = block.trim()
                if (trimmed.isNotEmpty()) {
                    add(ParagraphRange(start = start, end = start + trimmed.length, text = trimmed))
                }
                index = breakAt + 2
            }
        }
        return ChapterLayout(paragraphs = paragraphs, sentences = splitSentences(text))
    }

    private fun splitSentences(text: String): List<Sentence> {
        val sentences = mutableListOf<Sentence>()
        var start = -1
        var index = 0
        fun closeSentence(upto: Int) {
            if (start >= 0) {
                val chunk = text.substring(start, upto)
                val trimmed = chunk.trim()
                if (trimmed.isNotEmpty()) {
                    val head = chunk.length - chunk.trimStart().length
                    sentences.add(
                        Sentence(
                            text = trimmed,
                            start = start + head,
                            end = start + head + trimmed.length,
                        ),
                    )
                }
            }
        }
        while (index < text.length) {
            if (start == -1 && !text[index].isWhitespace()) {
                start = index
            }
            if (start >= 0) {
                val char = text[index]
                if (char in TERMINATORS) {
                    closeSentence(index + 1)
                    start = -1
                } else if (char == '\n' && index + 1 < text.length && text[index + 1] == '\n') {
                    closeSentence(index)
                    start = -1
                }
            }
            index++
        }
        closeSentence(text.length)
        return sentences
    }

    private val TERMINATORS = charArrayOf('.', '!', '?', '。', '！', '？')
}
