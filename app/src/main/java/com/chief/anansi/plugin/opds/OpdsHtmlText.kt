package com.chief.anansi.plugin.opds

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Minimal HTML/XHTML to reader-text extraction owned by this phase (Key
 * decision 5): a zero-dependency, paragraph-preserving pass. Standalone
 * chapter markup goes through a lenient tag tokenizer, because acquisition
 * `text/html` documents are ordinary browser HTML rather than well-formed
 * XML: void tags arrive unclosed (`<br>`, `<img>`), `p`/`li` may never
 * close, and attribute values carry stray `>` freely. Block elements open
 * paragraph breaks, `br` opens a line break, `script`/`style`/`head`/
 * `title` contents are dropped, and text runs collapse their whitespace.
 * Character references decode from a small named table plus the numeric
 * forms; a reference the table does not know stays verbatim in the reader
 * text, the way a browser renders it, instead of failing the chapter.
 * Inline feed content (`type="xhtml"`) keeps the strict [OpdsXml.newParser]
 * walk, because inside an Atom feed it is well-formed XML by construction.
 */
object OpdsHtmlText {

    private val PARAGRAPH_ELEMENTS = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "table", "tr", "blockquote", "pre", "dl", "dd", "dt", "hr",
    )

    private val SKIP_ELEMENTS = setOf("script", "style", "head", "title")

    private val XML_FIVE = setOf("amp", "lt", "gt", "quot", "apos")

    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "nbsp" to "\u00A0", "iexcl" to "\u00A1", "cent" to "\u00A2", "pound" to "\u00A3",
        "curren" to "\u00A4", "yen" to "\u00A5", "brvbar" to "\u00A6", "sect" to "\u00A7",
        "uml" to "\u00A8", "copy" to "\u00A9", "ordf" to "\u00AA", "laquo" to "\u00AB",
        "not" to "\u00AC", "shy" to "\u00AD", "reg" to "\u00AE", "macr" to "\u00AF",
        "deg" to "\u00B0", "plusmn" to "\u00B1", "sup2" to "\u00B2", "sup3" to "\u00B3",
        "acute" to "\u00B4", "micro" to "\u00B5", "para" to "\u00B6", "middot" to "\u00B7",
        "cedil" to "\u00B8", "sup1" to "\u00B9", "ordm" to "\u00BA", "raquo" to "\u00BB",
        "frac14" to "\u00BC", "frac12" to "\u00BD", "frac34" to "\u00BE", "iquest" to "\u00BF",
        "Agrave" to "\u00C0", "Aacute" to "\u00C1", "Acirc" to "\u00C2", "Atilde" to "\u00C3",
        "Auml" to "\u00C4", "Aring" to "\u00C5", "AElig" to "\u00C6", "Ccedil" to "\u00C7",
        "Egrave" to "\u00C8", "Eacute" to "\u00C9", "Ecirc" to "\u00EA", "Euml" to "\u00CB",
        "Igrave" to "\u00CC", "Iacute" to "\u00CD", "Icirc" to "\u00CE", "Iuml" to "\u00CF",
        "ETH" to "\u00D0", "Ntilde" to "\u00D1", "Ograve" to "\u00D2", "Oacute" to "\u00D3",
        "Ocirc" to "\u00D4", "Otilde" to "\u00D5", "Ouml" to "\u00D6", "times" to "\u00D7",
        "Oslash" to "\u00D8", "Ugrave" to "\u00D9", "Uacute" to "\u00DA", "Ucirc" to "\u00DB",
        "Uuml" to "\u00DC", "Yacute" to "\u00DD", "THORN" to "\u00DE", "szlig" to "\u00DF",
        "agrave" to "\u00E0", "aacute" to "\u00E1", "acirc" to "\u00E2", "atilde" to "\u00E3",
        "auml" to "\u00E4", "aring" to "\u00E5", "aelig" to "\u00E6", "ccedil" to "\u00E7",
        "egrave" to "\u00E8", "eacute" to "\u00E9", "ecirc" to "\u00EA", "euml" to "\u00EB",
        "igrave" to "\u00EC", "iacute" to "\u00ED", "icirc" to "\u00EE", "iuml" to "\u00EF",
        "eth" to "\u00F0", "ntilde" to "\u00F1", "ograve" to "\u00F2", "oacute" to "\u00F3",
        "ocirc" to "\u00F4", "otilde" to "\u00F5", "ouml" to "\u00F6", "divide" to "\u00F7",
        "oslash" to "\u00F8", "ugrave" to "\u00F9", "uacute" to "\u00FA", "ucirc" to "\u00FB",
        "uuml" to "\u00FC", "yacute" to "\u00FD", "thorn" to "\u00FE", "yuml" to "\u00FF",
        "ndash" to "\u2013", "mdash" to "\u2014", "lsquo" to "\u2018", "rsquo" to "\u2019",
        "sbquo" to "\u201A", "ldquo" to "\u201C", "rdquo" to "\u201D", "bdquo" to "\u201E",
        "dagger" to "\u2020", "Dagger" to "\u2021", "bull" to "\u2022", "hellip" to "\u2026",
        "permil" to "\u2030", "prime" to "\u2032", "Prime" to "\u2033", "lsaquo" to "\u2039",
        "rsaquo" to "\u203A", "oline" to "\u203E", "frasl" to "\u2044", "euro" to "\u20AC",
        "trade" to "\u2122", "alpha" to "\u03B1", "beta" to "\u03B2", "gamma" to "\u03B3",
        "delta" to "\u03B4", "epsilon" to "\u03B5", "zeta" to "\u03B6", "eta" to "\u03B7",
        "theta" to "\u03B8", "iota" to "\u03B9", "kappa" to "\u03BA", "lambda" to "\u03BB",
        "mu" to "\u03BC", "nu" to "\u03BD", "xi" to "\u03BE", "omicron" to "\u03BF",
        "pi" to "\u03C0", "rho" to "\u03C1", "sigma" to "\u03C3", "tau" to "\u03C4",
        "upsilon" to "\u03C5", "phi" to "\u03C6", "chi" to "\u03C7", "psi" to "\u03C8",
        "omega" to "\u03C9", "larr" to "\u2190", "uarr" to "\u2191", "rarr" to "\u2192",
        "darr" to "\u2193", "harr" to "\u2194", "minus" to "\u2212",
        "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009", "zwnj" to "\u200C",
        "zwj" to "\u200D", "lrm" to "\u200E", "rlm" to "\u200F",
    )

    private val WHITESPACE = Regex("\\s+")

    private val REFERENCES = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);")

    /**
     * Extracts reader text from an HTML (or generic XML) markup string.
     * Lenient by design: acquisition documents are real-world HTML.
     */
    fun extract(markup: String): String {
        val text = StringBuilder()
        var pendingParagraph = false
        var pendingLine = false
        var skipElement: String? = null
        var i = 0
        val length = markup.length
        while (i < length) {
            if (markup[i] != '<' || !opensTag(markup, i)) {
                val next = nextTagStart(markup, i + 1)
                if (skipElement == null) {
                    val collapsed = markup.substring(i, next).replace(WHITESPACE, " ")
                    if (collapsed.isNotEmpty()) {
                        if (text.isNotEmpty() && (pendingParagraph || pendingLine)) {
                            text.append(if (pendingParagraph) "\n\n" else "\n")
                        }
                        pendingParagraph = false
                        pendingLine = false
                        text.append(decodeReferences(collapsed))
                    }
                }
                i = next
                continue
            }
            when {
                markup.startsWith("<!--", i) ->
                    i = markup.indexOf("-->", i + 4).let { if (it < 0) length else it + 3 }

                markup[i + 1] == '!' || markup[i + 1] == '?' ->
                    i = markup.indexOf('>', i + 2).let { if (it < 0) length else it + 1 }

                else -> {
                    val end = tagEnd(markup, i)
                    val closing = i + 1 < length && markup[i + 1] == '/'
                    val name = tagName(markup, i + 1, end)
                    i = end + 1
                    val skipping = skipElement
                    when {
                        skipping != null -> if (closing && name == skipping) skipElement = null
                        closing -> Unit
                        name == "br" -> pendingLine = true
                        name in SKIP_ELEMENTS -> skipElement = name
                        name in PARAGRAPH_ELEMENTS -> pendingParagraph = true
                    }
                }
            }
        }
        return text.toString()
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Extracts reader text from the element the parser is positioned on,
     * consuming through its end tag. Used for `type="xhtml"` content, whose
     * payload lives as child elements of the current tag.
     */
    fun extractSubtree(parser: XmlPullParser): String {
        val text = try {
            walk(parser, untilEndOfDocument = false)
        } catch (e: XmlPullParserException) {
            throw xmlFailure("Catalog entry content", e)
        }
        return text.normalizeParagraphs()
    }

    /** Consumes the parser through the current element's end tag. */
    fun skipSubtree(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT ->
                    throw parseFailure("Catalog markup ends unexpectedly")
            }
        }
    }

    /**
     * The strict text walk for well-formed inline feed markup; break state
     * is carried between text runs so consecutive empty blocks collapse
     * into one break and the pass stays linear in the markup size.
     */
    private fun walk(parser: XmlPullParser, untilEndOfDocument: Boolean): StringBuilder {
        val text = StringBuilder()
        var pendingParagraph = false
        var pendingLine = false
        var skipDepth = -1
        // A subtree walk starts on the payload's own start tag and ends when
        // that tag closes; a document walk starts at START_DOCUMENT.
        var depth = if (untilEndOfDocument) 0 else 1
        while (untilEndOfDocument || depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val name = parser.name.lowercase()
                    when {
                        skipDepth < 0 && name in SKIP_ELEMENTS -> skipDepth = depth
                        skipDepth < 0 && name == "br" -> pendingLine = true
                        skipDepth < 0 && name in PARAGRAPH_ELEMENTS -> pendingParagraph = true
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (skipDepth == depth) skipDepth = -1
                    depth--
                }

                XmlPullParser.TEXT -> if (skipDepth < 0) {
                    val collapsed = parser.text.replace(WHITESPACE, " ")
                    if (collapsed.isNotEmpty()) {
                        if (text.isNotEmpty() && (pendingParagraph || pendingLine)) {
                            text.append(if (pendingParagraph) "\n\n" else "\n")
                        }
                        pendingParagraph = false
                        pendingLine = false
                        text.append(collapsed)
                    }
                }

                XmlPullParser.END_DOCUMENT -> {
                    if (!untilEndOfDocument) {
                        throw parseFailure("Catalog markup ends unexpectedly")
                    }
                    return text
                }
            }
        }
        return text
    }

    private fun StringBuilder.normalizeParagraphs(): String = toString()
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /** A '<' opens a tag when a letter, '/', '!', or '?' follows it. */
    private fun opensTag(markup: String, at: Int): Boolean {
        if (at + 1 >= markup.length) return false
        val next = markup[at + 1]
        return next.isLetter() || next == '/' || next == '!' || next == '?'
    }

    /** The next '<' from [from] that opens a tag; the end of input otherwise. */
    private fun nextTagStart(markup: String, from: Int): Int {
        var j = from
        while (j < markup.length) {
            if (markup[j] == '<' && opensTag(markup, j)) return j
            j++
        }
        return markup.length
    }

    /** The '>' closing the tag at [at], quoted attribute values skipped. */
    private fun tagEnd(markup: String, at: Int): Int {
        var j = at + 1
        var quote = '\u0000'
        while (j < markup.length) {
            val ch = markup[j]
            when {
                quote != '\u0000' -> if (ch == quote) quote = '\u0000'
                ch == '"' || ch == '\'' -> quote = ch
                ch == '>' -> return j
            }
            j++
        }
        return markup.length
    }

    /** The lowercased element name inside the tag spanning [start, end). */
    private fun tagName(markup: String, start: Int, end: Int): String =
        markup.substring(start, end)
            .trim()
            .trimStart('/')
            .substringBefore(' ')
            .substringBefore('\t')
            .substringBefore('\n')
            .substringBefore('/')
            .lowercase()

    /**
     * Decodes character references in one text run: numeric forms, the XML
     * five, and the named table. Unknown names stay verbatim.
     */
    private fun decodeReferences(run: String): String =
        if (!run.contains('&')) {
            run
        } else {
            REFERENCES.replace(run) { match -> decodeReference(match.groupValues[1]) ?: match.value }
        }

    private fun decodeReference(body: String): String? = when {
        body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toChars(16)
        body.startsWith("#") -> body.substring(1).toChars(10)
        body == "amp" -> "&"
        body == "lt" -> "<"
        body == "gt" -> ">"
        body == "quot" -> "\""
        body == "apos" -> "'"
        else -> NAMED_ENTITIES[body]
    }

    /** The character(s) of a numeric reference; null when it is not a scalar. */
    private fun String.toChars(radix: Int): String? = runCatching {
        String(Character.toChars(toInt(radix)))
    }.getOrNull()
}
