package com.chief.anansi.plugin.local

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import java.io.File

/**
 * TXT handling: BOM detection for UTF-8, UTF-16LE, and
 * UTF-16BE with UTF-8 replacement decoding as the default, and the concrete
 * chapter-split regex covering English "Chapter N" (arabic or roman) and CJK
 * "第...章" heading conventions. Zero heading matches means the whole file is
 * one chapter titled from the filename without extension. Chapter text is
 * re-derived from the stored copy by re-running the same deterministic split.
 */
object TxtImporter {

    /**
     * A heading line: "Chapter 12", "Chapter IV: ...", "第十三章 ...", where
     * the heading text (arabic digits or roman numeral letters) starts the
     * title; case-insensitive.
     */
    private val HEADING = Regex(
        """^\s*(chapter\s+[0-9ivxl]+\b.*|第[0-9零一二三四五六七八九十百千两]+章.*)""",
        RegexOption.IGNORE_CASE,
    )

    data class TxtChapter(val title: String, val body: String)

    /**
     * Decodes the stored copy: a BOM wins (`EF BB BF` UTF-8, `FF FE`
     * UTF-16LE, `FE FF` UTF-16BE); with no BOM the bytes decode as UTF-8
     * with malformed-input replacement. A zero-byte file fails loudly.
     */
    fun decode(file: File): String {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) {
            throw SourcePluginException(
                SourceError.Parse("The TXT file \"${file.name}\" is empty (0 bytes)"),
                null,
            )
        }
        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Charsets.UTF_16LE to 2

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Charsets.UTF_16BE to 2

            else -> Charsets.UTF_8 to 0
        }
        // String(byte[], charset) replaces malformed input, so undecodable
        // sequences surface as replacement characters rather than a crash.
        return String(bytes, offset, bytes.size - offset, charset)
    }

    /**
     * Splits the decoded text at heading lines: each heading line becomes
     * its chapter's title and the lines up to the next heading its body.
     * Text before the first heading is kept, prepended to the first
     * chapter. Zero heading matches yields one chapter titled
     * [fallbackTitle] (the filename without extension).
     */
    fun split(text: String, fallbackTitle: String): List<TxtChapter> {
        val chapters = ArrayList<TxtChapter>()
        var currentTitle: String? = null
        val pending = ArrayList<String>()
        var preamble: String? = null

        fun flushSegment() {
            val title = currentTitle
            val body = bodyOf(pending)
            pending.clear()
            if (title == null) {
                // Text before the first heading (or a heading-less file).
                preamble = body.ifEmpty { null }
            } else {
                val carried = preamble
                preamble = null
                chapters += TxtChapter(
                    title = title,
                    body = if (carried != null) {
                        listOf(carried, body).filter { it.isNotEmpty() }.joinToString("\n\n")
                    } else {
                        body
                    },
                )
            }
        }

        for (line in text.lines()) {
            if (HEADING.containsMatchIn(line)) {
                flushSegment()
                currentTitle = line.trim()
            } else {
                pending += line
            }
        }
        flushSegment()

        if (chapters.isEmpty()) {
            return listOf(TxtChapter(title = fallbackTitle, body = preamble.orEmpty()))
        }
        return chapters
    }

    /** One chapter's text, re-derived from the stored copy. */
    fun chapterText(file: File, index: Int, fallbackTitle: String): String {
        val chapters = split(decode(file), fallbackTitle)
        val chapter = chapters.getOrNull(index) ?: throw SourcePluginException(
            SourceError.Parse(
                "The TXT file \"${file.name}\" has no chapter ${index + 1} in its split",
            ),
            null,
        )
        return chapter.body
    }

    /** Lines to reader text: every non-blank line is one paragraph. */
    private fun bodyOf(lines: List<String>): String =
        lines.map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
}
