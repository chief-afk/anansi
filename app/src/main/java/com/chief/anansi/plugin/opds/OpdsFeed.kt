package com.chief.anansi.plugin.opds

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import java.io.StringReader
import java.net.URI
import org.xmlpull.v1.XmlPullParser

/** The OPDS link relations a catalog's navigation and reading flow uses. */
object OpdsRels {
    const val ACQUISITION = "http://opds-spec.org/acquisition"
    const val IMAGE = "http://opds-spec.org/image"
    const val IMAGE_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    const val NEXT = "next"
    const val SEARCH = "search"

    fun isSubsection(rel: String): Boolean =
        rel == "subsection" || rel == "http://opds-spec.org/subsection"

    fun isSearch(rel: String): Boolean =
        rel == SEARCH || rel == "http://opds-spec.org/search"

    fun isAcquisition(rel: String): Boolean = rel.startsWith(ACQUISITION)
}

/** One `atom:link` with its href resolved against the feed's own URL. */
data class OpdsLink(
    val rel: String,
    val href: String,
    val type: String? = null,
    val title: String? = null,
)

/**
 * One `atom:entry` of a catalog feed. The same shape covers navigation
 * entries (subcatalog links) and publication entries (acquisition links),
 * because the catalog listing maps both onto novels.
 */
data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    /** `content` when present, else `summary`; markup is extracted to text. */
    val summary: String?,
    /** The raw `atom:updated` timestamp. */
    val updated: String?,
    val links: List<OpdsLink>,
    /** `atom:category` terms, the closest OPDS analog of novel genres. */
    val categories: List<String>,
) {
    fun link(rel: String): OpdsLink? = links.firstOrNull { it.rel == rel }

    val subsectionHref: String?
        get() = links.firstOrNull { OpdsRels.isSubsection(it.rel) }?.href

    /** The cover: the OPDS image relation, thumbnail preferred for grids. */
    val imageHref: String?
        get() = links.firstOrNull { it.rel == OpdsRels.IMAGE_THUMBNAIL }?.href
            ?: links.firstOrNull { it.rel == OpdsRels.IMAGE }?.href

    val acquisitionLinks: List<OpdsLink>
        get() = links.filter { OpdsRels.isAcquisition(it.rel) }

    /**
     * The acquisition link to read this entry as text: a type the reader
     * supports wins (plain text over HTML), then untyped links (the response
     * header decides), then any acquisition link so an unsupported type can
     * fail loudly naming itself instead of the entry vanishing.
     */
    val textAcquisition: OpdsLink?
        get() {
            val candidates = acquisitionLinks
            return candidates.firstOrNull { it.type?.lowercase()?.substringBefore(';') in TEXT_TYPES }
                ?: candidates.firstOrNull { it.type.isNullOrBlank() }
                ?: candidates.firstOrNull()
        }

    companion object {
        val TEXT_TYPES = setOf("text/plain", "text/html", "application/xhtml+xml")
    }
}

enum class OpdsFeedKind { NAVIGATION, ACQUISITION }

/** A parsed OPDS 1.x feed: its metadata, links, and entries. */
data class OpdsFeed(
    val title: String,
    val subtitle: String?,
    val language: String?,
    val links: List<OpdsLink>,
    val entries: List<OpdsEntry>,
) {
    val kind: OpdsFeedKind
        get() = if (entries.any { it.acquisitionLinks.isNotEmpty() }) {
            OpdsFeedKind.ACQUISITION
        } else {
            OpdsFeedKind.NAVIGATION
        }

    /** The first link with the relation; `search` matches bare and OPDS-qualified. */
    fun link(rel: String): OpdsLink? = links.firstOrNull { link ->
        link.rel == rel || (rel == OpdsRels.SEARCH && OpdsRels.isSearch(link.rel))
    }

    fun entryById(id: String): OpdsEntry? = entries.firstOrNull { it.id == id }
}

/**
 * The OPDS 1.x feed parser: platform [XmlPullParser]
 * through the hardened [OpdsXml.newParser] setup, Atom namespace elements by
 * local name, OPDS relations from plain `rel` attributes. A JSON payload
 * where a feed is expected fails loudly naming OPDS 2; a
 * non-feed XML root and malformed XML fail as loud parse errors.
 */
object OpdsFeedParser {

    private const val FEED = "feed"
    private const val ENTRY = "entry"

    fun parse(feedXml: String, feedUrl: String): OpdsFeed {
        val trimmed = feedXml.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            throw SourcePluginException(
                SourceError.UnsupportedFeature(
                    "This catalog publishes OPDS 2 (a JSON feed); " +
                        "Anansi reads OPDS 1.x Atom catalogs only",
                ),
                null,
            )
        }
        val parser = OpdsXml.newParser(StringReader(feedXml))
        return try {
            parseDocument(parser, feedUrl)
        } catch (e: org.xmlpull.v1.XmlPullParserException) {
            throw xmlFailure("Catalog feed $feedUrl", e)
        } catch (e: java.io.IOException) {
            throw parseFailure("Catalog feed $feedUrl could not be read: ${e.message}")
        }
    }

    private fun parseDocument(parser: XmlPullParser, feedUrl: String): OpdsFeed {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
        if (event == XmlPullParser.END_DOCUMENT || parser.name != FEED) {
            throw parseFailure(
                "The document at $feedUrl is not an OPDS 1.x feed " +
                    "(expected an Atom <feed> root element)",
            )
        }
        val language = parser.getAttributeValue(OpdsXml.XML_NS, "lang")

        var title: String? = null
        var subtitle: String? = null
        val links = ArrayList<OpdsLink>()
        val entries = ArrayList<OpdsEntry>()
        var entry: EntryBuilder? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (entry == null) {
                        when (parser.name) {
                            "title" -> title = title ?: readText(parser)
                            "subtitle" -> subtitle = subtitle ?: readText(parser)
                            "link" -> readLink(parser, feedUrl)?.let(links::add)
                            ENTRY -> entry = EntryBuilder()
                            else -> if (!parser.isEmptyElementTag) OpdsHtmlText.skipSubtree(parser)
                        }
                    } else {
                        when (parser.name) {
                            "title" -> entry.title = readText(parser)
                            "id" -> entry.id = readText(parser)
                            "updated" -> entry.updated = readText(parser)
                            "author" -> entry.authors.add(readAuthorName(parser))
                            "summary" -> entry.summary = summaryOrContent(parser)
                            "content" -> entry.summary = summaryOrContent(parser)
                            "link" -> readLink(parser, feedUrl)?.let(entry.links::add)
                            "category" -> entry.categories.add(
                                parser.getAttributeValue(null, "term").orEmpty(),
                            )
                            else -> if (!parser.isEmptyElementTag) OpdsHtmlText.skipSubtree(parser)
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    ENTRY -> {
                        entry?.let { entries.add(it.build(feedUrl)) }
                        entry = null
                    }
                    FEED -> {
                        val feedTitle = title?.trim().orEmpty()
                        return OpdsFeed(
                            title = feedTitle,
                            subtitle = subtitle?.trim()?.ifEmpty { null },
                            language = language,
                            links = links,
                            entries = entries,
                        )
                    }
                }

                XmlPullParser.END_DOCUMENT ->
                    throw parseFailure("Catalog feed $feedUrl ends unexpectedly")
            }
        }
    }

    /** Reads the current simple-text element, consuming through its end tag. */
    private fun readText(parser: XmlPullParser): String {
        val text = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_DOCUMENT ->
                    throw parseFailure("Catalog feed ends unexpectedly")
            }
        }
        return text.toString().trim()
    }

    /** Positioned on `atom:author`; returns the first `atom:name` inside it. */
    private fun readAuthorName(parser: XmlPullParser): String {
        var name: String? = null
        while (true) {
            when {
                parser.next() == XmlPullParser.START_TAG &&
                    parser.name == "name" && name == null -> name = readText(parser)

                parser.eventType == XmlPullParser.END_TAG && parser.name == "author" -> {
                    return name.orEmpty().trim()
                }

                parser.eventType == XmlPullParser.END_DOCUMENT ->
                    throw parseFailure("Catalog feed ends unexpectedly")
            }
        }
    }

    /**
     * `summary`/`content` are type-aware: `xhtml` carries child elements
     * (walked with paragraph preservation), `html` carries escaped markup,
     * plain text arrives as-is. All land as reader text.
     */
    private fun summaryOrContent(parser: XmlPullParser): String = when (
        parser.getAttributeValue(null, "type")?.lowercase()
    ) {
        "xhtml" -> OpdsHtmlText.extractSubtree(parser)
        "html" -> OpdsHtmlText.extract(readText(parser))
        else -> readText(parser)
    }

    /** Reads one `atom:link`'s attributes; the href resolves against the feed URL. */
    private fun readLink(parser: XmlPullParser, feedUrl: String): OpdsLink? {
        val href = parser.getAttributeValue(null, "href")
        val rel = parser.getAttributeValue(null, "rel").orEmpty()
        if (href.isNullOrEmpty()) {
            if (!parser.isEmptyElementTag) OpdsHtmlText.skipSubtree(parser)
            return null
        }
        val resolved = try {
            URI(feedUrl).resolve(href).toString()
        } catch (e: Exception) {
            href
        }
        if (!parser.isEmptyElementTag) OpdsHtmlText.skipSubtree(parser)
        return OpdsLink(
            rel = rel,
            href = resolved,
            type = parser.getAttributeValue(null, "type"),
            title = parser.getAttributeValue(null, "title"),
        )
    }

    /** Accumulates one entry's fields; `id` and `title` are structural. */
    private class EntryBuilder {
        var id: String = ""
        var title: String = ""
        var summary: String? = null
        var updated: String? = null
        val authors = ArrayList<String>()
        val links = ArrayList<OpdsLink>()
        val categories = ArrayList<String>()

        fun build(feedUrl: String): OpdsEntry {
            if (id.isBlank()) {
                throw parseFailure("An entry of feed $feedUrl is missing its atom:id")
            }
            if (title.isBlank()) {
                throw parseFailure("Catalog entry \"$id\" is missing its atom:title")
            }
            return OpdsEntry(
                id = id,
                title = title,
                authors = authors.filter { it.isNotBlank() },
                summary = summary?.trim()?.ifEmpty { null },
                updated = updated?.trim()?.ifEmpty { null },
                links = links,
                categories = categories.filter { it.isNotBlank() },
            )
        }
    }
}
