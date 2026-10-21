package com.chief.anansi.plugin.opds

import com.chief.anansi.plugin.Source
import com.chief.anansi.plugin.SourceChapterItem
import com.chief.anansi.plugin.SourceNovel
import com.chief.anansi.plugin.SourceNovelItem
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/**
 * The generation label of the OPDS 1.x adapter inside the opds ecosystem;
 * the Browse footer shows it beside the ecosystem, the way plugin sources
 * show v3 / v1era.
 */
const val GENERATION_OPDS_1 = "opds1"

/**
 * The OPDS 1.x adapter behind the novel-native [Source] seam (spec
 * Requirements 3-6): the catalog's feeds are the catalog, entries are
 * novels, acquisition-feed entries are chapters, and acquisition documents
 * are chapter text. Zero runtime involvement: no JS engine, no plugin
 * host, only feed parsing and HTTP. Every failure is produced once here at
 * the adapter boundary into the per-source registry and thrown as a loud
 * [SourcePluginException]; the UI renders reasons without
 * reclassification(the shared error taxonomy).
 *
 * Path scheme inside the Source seam:
 * - A novel path references one feed entry: `<feedUrl>#<entryId>`, so
 *   details can read metadata from the entry itself and chapters from the
 *   entry's acquisition feed.
 * - A chapter path is the acquisition document's absolute URL, fetched and
 *   rendered directly; an entry without acquisition links keeps the
 *   reference form so opening it in the reader fails loudly naming the
 *   section, instead of the chapter silently disappearing.
 */
class OpdsSource(
    /** The registered catalog's root feed URL (the source row's distUrl). */
    private val catalogUrl: String,
    private val http: OpdsHttp,
    private val registry: SourceErrorRegistry,
    /** The stable per-catalog identity the registry minted (`opds_{sourceId}`). */
    private val catalogId: String,
    override val id: Long,
    override val name: String,
    override val lang: String,
) : Source {

    override val pluginId: String = catalogId
    override val iconUrl: String? = null
    override val ecosystem: String = "opds"
    override val generation: String = GENERATION_OPDS_1

    override val filters: JsonElement? = null
    override val settings: JsonElement? = null

    /**
     * The catalog listing: the root feed's entries for page 1, and each
     * later page one `rel="next"` hop deeper, so Browse's explicit
     * load-more walks the feed's own pagination. OPDS
     * has no popular/latest split; both listing chips show the catalog.
     */
    override suspend fun popularNovels(
        page: Int,
        showLatestNovels: Boolean,
        filterValuesJson: String?,
    ): List<SourceNovelItem> = guarded {
        val catalogPage = feedPageAt(catalogUrl, hops = page - 1)
            // Past the feed's last `rel="next"` page there is nothing left;
            // an empty page ends Browse's load-more.
            ?: return@guarded emptyList()
        catalogPage.feed.entries.map { entry ->
            SourceNovelItem(
                name = entry.title,
                path = entryPath(catalogPage.url, entry.id),
                cover = entry.imageHref,
            )
        }
    }

    /**
     * Search through the catalog's OpenSearch description: resolve the
     * feed's `rel="search"` link, take the first Atom result template,
     * substitute the query, and parse the result feed. A catalog without a
     * search link fails with the loud unsupported-feature error instead of
     * an empty result.
     */
    override suspend fun searchNovels(
        query: String,
        page: Int,
        filterValuesJson: String?,
    ): List<SourceNovelItem> = guarded {
        val root = fetchFeed(catalogUrl)
        val searchLink = root.feed.link(OpdsRels.SEARCH)
            ?: throw SourcePluginException(
                SourceError.UnsupportedFeature(
                    "\"$name\" does not publish an OpenSearch description, " +
                        "so it cannot be searched from Anansi",
                ),
                null,
            )
        val descriptionUrl = searchLink.href
        val description = http.fetchText(descriptionUrl, "OpenSearch description")
        val template = OpdsOpenSearch.atomUrlTemplate(description.text)
            ?: throw SourcePluginException(
                SourceError.UnsupportedFeature(
                    "The OpenSearch description at $descriptionUrl " +
                        "has no Atom (application/atom+xml) result template",
                ),
                null,
            )
        // OpenSearch templates may be relative; they resolve against the
        // description document's own URL, and absolute templates win as-is.
        val searchUrl = java.net.URI(descriptionUrl)
            .resolve(OpdsOpenSearch.substitute(template, query, page))
            .toString()
        val resultPage = feedPageAt(searchUrl, hops = page - 1)
            ?: return@guarded emptyList()
        resultPage.feed.entries.map { entry ->
            SourceNovelItem(
                name = entry.title,
                path = entryPath(resultPage.url, entry.id),
                cover = entry.imageHref,
            )
        }
    }

    /**
     * Novel details from the referenced entry: the
     * entry's own title, authors, summary, categories, and cover; the
     * chapter list resolves from the entry's acquisition feed when it has
     * one, whose entries map 1:1 to chapters; an entry with only direct
     * acquisition links collapses to a single chapter titled by the entry.
     */
    override suspend fun novelDetails(path: String): SourceNovel = guarded {
        val (feedUrl, entryId) = splitEntryPath(path, "novel")
        val entryPage = fetchFeed(feedUrl)
        val entry = entryPage.feed.entryById(entryId)
            ?: throw parseFailure("Catalog entry \"$entryId\" is no longer in its feed at $feedUrl")

        val subsection = entry.subsectionHref
        val chapters: List<SourceChapterItem> = if (subsection != null) {
            val acquisition = fetchFeed(subsection)
            acquisition.feed.entries.map { chapterEntry ->
                // Each chapter reads its own acquisition document; an entry
                // without acquisition links keeps the reference form so the
                // reader can fail loudly naming the section.
                SourceChapterItem(
                    name = chapterEntry.title,
                    path = chapterEntry.textAcquisition?.href
                        ?: entryPath(acquisition.url, chapterEntry.id),
                    releaseTime = chapterEntry.updated,
                )
            }
        } else {
            val acquisition = entry.textAcquisition
                ?: throw parseFailure(
                    "Catalog entry \"${entry.title}\" has neither an acquisition feed " +
                        "nor acquisition links, so it cannot be read",
                )
            listOf(
                SourceChapterItem(
                    name = entry.title,
                    path = acquisition.href,
                    releaseTime = entry.updated,
                ),
            )
        }

        SourceNovel(
            path = path,
            name = entry.title,
            cover = entry.imageHref,
            genres = entry.categories.joinToString(", ").ifEmpty { null },
            summary = entry.summary,
            author = entry.authors.joinToString(", ").ifEmpty { null },
            artist = null,
            status = null,
            chapters = chapters,
        )
    }

    /**
     * Chapter text from the acquisition document:
     * `text/plain` renders as-is, `text/html` and `application/xhtml+xml`
     * go through the paragraph-preserving extractor, and every other media
     * type fails loudly with the type named in the reason.
     */
    override suspend fun chapterText(novelPath: String, chapterPath: String): String = guarded {
        val documentUrl = resolveChapterDocumentUrl(chapterPath)
        val response = http.fetchText(documentUrl, "Chapter")
        val text = when (response.mediaType) {
            "text/plain" -> response.text
            "text/html", "application/xhtml+xml" -> OpdsHtmlText.extract(response.text)
            // A missing header is sniffed the way a browser would: markup
            // opens with a tag, everything else is treated as plain text.
            null -> if (response.text.trimStart().startsWith("<")) {
                OpdsHtmlText.extract(response.text)
            } else {
                response.text
            }

            else -> throw SourcePluginException(
                SourceError.UnsupportedFeature(
                    "This chapter is published as \"${response.mediaType}\", which Anansi " +
                        "cannot read as text (supported: text/plain, text/html, " +
                        "application/xhtml+xml)",
                ),
                null,
            )
        }
        if (text.isBlank()) {
            throw parseFailure("The chapter at $documentUrl has no readable text")
        }
        text
    }

    /** The acquisition document URL for a chapter path, reference form resolved. */
    private suspend fun resolveChapterDocumentUrl(chapterPath: String): String {
        if (!chapterPath.contains('#')) return chapterPath
        val (feedUrl, entryId) = splitEntryPath(chapterPath, "chapter")
        val entryPage = fetchFeed(feedUrl)
        val entry = entryPage.feed.entryById(entryId)
            ?: throw parseFailure("Catalog entry \"$entryId\" is no longer in its feed at $feedUrl")
        return acquisitionDocumentUrl(entry)
    }

    /**
     * The document one entry reads as: its own text acquisition link, or,
     * when the entry only links a subsection (catalogs that indirect each
     * publication through its own feed), the first acquisition link found
     * in that subsection. Everything else fails loudly naming the section,
     * never silently disappearing.
     */
    private suspend fun acquisitionDocumentUrl(entry: OpdsEntry): String {
        entry.textAcquisition?.let { return it.href }
        val subsection = entry.subsectionHref
            ?: throw SourcePluginException(
                SourceError.UnsupportedFeature(
                    "\"${entry.title}\" is a catalog section, not a readable chapter; " +
                        "open its entries from the catalog listing",
                ),
                null,
            )
        val subsectionPage = fetchFeed(subsection)
        val link = subsectionPage.feed.entries.firstNotNullOfOrNull { it.textAcquisition }
            ?: throw SourcePluginException(
                SourceError.UnsupportedFeature(
                    "\"${entry.title}\" links no readable text in its publication feed at $subsection",
                ),
                null,
            )
        return link.href
    }

    /** One feed with the URL it was served from (pages walk `rel="next"`). */
    private data class FeedPage(val url: String, val feed: OpdsFeed)

    /** Null once the feed's `rel="next"` chain runs out before [hops] steps. */
    private suspend fun feedPageAt(startUrl: String, hops: Int): FeedPage? {
        var page = fetchFeed(startUrl)
        repeat(hops) {
            val next = page.feed.link(OpdsRels.NEXT)?.href ?: return null
            page = fetchFeed(next)
        }
        return page
    }

    private suspend fun fetchFeed(url: String): FeedPage {
        val response = http.fetchText(url, "OPDS catalog feed")
        return FeedPage(url, OpdsFeedParser.parse(response.text, url))
    }

    private fun entryPath(feedUrl: String, entryId: String): String = "$feedUrl#$entryId"

    private fun splitEntryPath(path: String, what: String): Pair<String, String> {
        val separator = path.lastIndexOf('#')
        if (separator <= 0) {
            throw parseFailure(
                "Catalog $what path \"$path\" does not reference a feed entry " +
                    "(expected <feedUrl>#<entryId>)",
            )
        }
        val feedUrl = path.substring(0, separator)
        val entryId = path.substring(separator + 1)
        if (feedUrl.isBlank() || entryId.isBlank()) {
            throw parseFailure("Catalog $what path \"$path\" is malformed")
        }
        return feedUrl to entryId
    }

    /** The registry boundary: success clears, failure reports, both once. */
    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block().also { registry.clear(pluginId) }
        } catch (e: SourcePluginException) {
            registry.report(pluginId, e.error)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = SourceError.Parse("OPDS catalog \"$name\" failed: ${e.message}")
            registry.report(pluginId, error)
            throw SourcePluginException(error, e)
        }

    private fun parseFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Parse(reason), null)
}
