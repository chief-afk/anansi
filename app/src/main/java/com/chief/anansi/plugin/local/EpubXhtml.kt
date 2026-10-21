package com.chief.anansi.plugin.local

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import org.xmlpull.v1.XmlPullParser

/**
 * One spine XHTML document's text: paragraphs with
 * block-level breaks preserved, the document's first h1-h3 heading, and its
 * `title` element text. Runs on the platform `XmlPullParser`; a document
 * that does not parse fails loudly naming it - spine documents are never
 * silently skipped.
 */
internal object EpubXhtml {

    data class Parsed(
        val heading: String?,
        val title: String?,
        /** The document's paragraphs joined with blank lines (reader format). */
        val body: String,
    )

    fun parse(xhtml: String, label: String): Parsed = try {
        rawParse(xhtml)
    } catch (e: SourcePluginException) {
        throw e
    } catch (e: Exception) {
        throw SourcePluginException(
            SourceError.Parse("The document \"$label\" is unparseable: ${e.message ?: "malformed XHTML"}"),
            e,
        )
    }

    private fun rawParse(xhtml: String): Parsed {
        val parser = LocalXml.newParser(decodeUnknownReferences(xhtml))

        val paragraphs = ArrayList<String>()
        val buffer = StringBuilder()
        var title: String? = null
        var heading: String? = null
        var titleBuffer: StringBuilder? = null
        var headingBuffer: StringBuilder? = null
        var depth = 0
        // script/style subtrees never reach the body; while one is open,
        // [skipName] holds its name and [skipDepth] its open depth.
        var skipName: String? = null
        var skipDepth = 0

        fun flush() {
            val text = buffer.toString().replace(WHITESPACE, " ").trim()
            buffer.setLength(0)
            if (text.isNotEmpty()) paragraphs += text
        }

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break

                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    if (skipName == null && name in SKIP_ELEMENTS) {
                        skipDepth = depth + 1
                        skipName = name
                    }
                    depth++
                    if (skipName == null) {
                        when {
                            name == "title" -> titleBuffer = StringBuilder()
                            name in HEADINGS && heading == null -> headingBuffer = StringBuilder()
                            name in BLOCK_ELEMENTS || name == "br" -> flush()
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (skipName != null && depth == skipDepth) {
                        // Closes the element whose subtree is being skipped.
                        skipName = null
                        skipDepth = 0
                        depth--
                        continue
                    }
                    depth--
                    if (skipName != null) {
                        // Still inside a skipped subtree.
                    } else if (name == "title") {
                        if (title == null) {
                            title = titleBuffer?.toString()?.replace(WHITESPACE, " ")?.trim()
                        }
                        titleBuffer = null
                    } else if (name in HEADINGS && headingBuffer != null) {
                        if (heading == null) {
                            heading = headingBuffer.toString().replace(WHITESPACE, " ").trim()
                                .ifEmpty { null }
                        }
                        headingBuffer = null
                    } else if (name in BLOCK_ELEMENTS) {
                        flush()
                    }
                }

                XmlPullParser.TEXT -> {
                    // The title element's text feeds the title only; body
                    // text feeds the paragraphs and the first heading.
                    if (skipName == null) {
                        val text = parser.text
                        if (titleBuffer != null) {
                            titleBuffer.append(text)
                        } else {
                            buffer.append(text)
                            headingBuffer?.append(text)
                        }
                    }
                }
            }
        }
        flush()

        return Parsed(
            heading = heading,
            title = title,
            body = paragraphs.joinToString("\n\n"),
        )
    }

    private val BLOCK_ELEMENTS = setOf(
        "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote",
        "dd", "dt", "td", "th", "section", "article", "header", "footer",
        "aside", "nav", "figure", "figcaption", "main", "ul", "ol", "table",
        "tr", "pre", "body",
    )

    private val HEADINGS = setOf("h1", "h2", "h3")

    private val SKIP_ELEMENTS = setOf("script", "style")

    private val WHITESPACE = Regex("\\s+")

    private val REFERENCE = Regex("&([A-Za-z][A-Za-z0-9]*);")

    /**
     * EPUB content documents may carry HTML-era named entities their DTD
     * declares; the platform pull parser only knows the XML five. The common
     * typography set is decoded up front; anything outside this map is left
     * for the parser, which fails the document loudly instead of guessing.
     */
    private val NAMED_REFERENCES = mapOf(
        "nbsp" to "\u00A0",
        "shy" to "\u00AD",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "hellip" to "\u2026",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "lsaquo" to "\u2039",
        "rsaquo" to "\u203A",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "bull" to "\u2022",
        "middot" to "\u00B7",
        "dagger" to "\u2020",
        "Dagger" to "\u2021",
        "sect" to "\u00A7",
        "para" to "\u00B6",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "deg" to "\u00B0",
        "plusmn" to "\u00B1",
        "times" to "\u00D7",
        "divide" to "\u00F7",
        "micro" to "\u00B5",
        "frac12" to "\u00BD",
        "frac14" to "\u00BC",
        "frac34" to "\u00BE",
        "sup2" to "\u00B2",
        "sup3" to "\u00B3",
        "euro" to "\u20AC",
        "pound" to "\u00A3",
        "yen" to "\u00A5",
        "cent" to "\u00A2",
        "ensp" to "\u2002",
        "emsp" to "\u2003",
        "thinsp" to "\u2009",
        "zwnj" to "\u200C",
        "zwj" to "\u200D",
        "lrm" to "\u200E",
        "rlm" to "\u200F",
        "prime" to "\u2032",
        "Prime" to "\u2033",
        "oline" to "\u203E",
        "frasl" to "\u2044",
        "iexcl" to "\u00A1",
        "iquest" to "\u00BF",
        "ldquor" to "\u201E",
        "sbquo" to "\u201A",
        "bdquo" to "\u201E",
    )

    private fun decodeUnknownReferences(xhtml: String): String =
        REFERENCE.replace(xhtml) { match ->
            val name = match.groupValues[1]
            if (name in XML_FIVE || name !in NAMED_REFERENCES) {
                match.value
            } else {
                NAMED_REFERENCES.getValue(name)
            }
        }

    private val XML_FIVE = setOf("amp", "lt", "gt", "quot", "apos")
}
