package com.chief.anansi.plugin.opds

import java.io.StringReader
import java.net.URLEncoder
import org.xmlpull.v1.XmlPullParser

/**
 * The OpenSearch side of catalog search: resolve the
 * feed's `rel="search"` link, read the description document, and take the
 * first `Url` template whose type is `application/atom+xml`. Substitution
 * fills `searchTerms` (required) and the optional core parameters a catalog
 * may include; a template without `searchTerms` cannot carry a query and
 * fails loudly.
 */
object OpdsOpenSearch {

    private const val ATOM_RESULT_TYPE = "application/atom+xml"

    /** The first Atom result template of the description, or null. */
    fun atomUrlTemplate(descriptionXml: String): String? {
        val parser = OpdsXml.newParser(StringReader(descriptionXml))
        return try {
            var template: String? = null
            // Every start tag in document order is a candidate; no subtree
            // skipping, because the walk starts on the description's own
            // root element and `Url` elements are shallow.
            loop@ while (template == null) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        // The media type compares without parameters: real
                        // catalogs type their search endpoint
                        // "application/atom+xml;profile=opds-catalog;...",
                        // which is still an Atom result type.
                        if (parser.name.equals("Url", ignoreCase = true) &&
                            parser.getAttributeValue(null, "type")
                                ?.trim()?.lowercase()?.substringBefore(';') == ATOM_RESULT_TYPE
                        ) {
                            template = parser.getAttributeValue(null, "template")
                                ?.trim()?.ifEmpty { null }
                        }
                    }

                    XmlPullParser.END_DOCUMENT -> break@loop
                }
            }
            template
        } catch (e: org.xmlpull.v1.XmlPullParserException) {
            throw xmlFailure("OpenSearch description", e)
        } catch (e: java.io.IOException) {
            throw parseFailure("OpenSearch description could not be read: ${e.message}")
        }
    }

    /**
     * Fills the template for one query and page. Core OpenSearch parameters
     * substitute with catalog-neutral values (`startIndex`/`startPage` from
     * the page number, `count` pinned to 20); a template with no
     * `searchTerms` placeholder is rejected loudly because the query could
     * never reach the catalog.
     */
    fun substitute(template: String, query: String, page: Int): String {
        if (!template.contains("{searchTerms}")) {
            throw parseFailure(
                "The catalog's OpenSearch description has no {searchTerms} parameter, " +
                    "so it cannot be searched",
            )
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        return template
            .replace("{searchTerms}", encoded)
            .replace("{searchTerms?}", encoded)
            .replace("{count}", "20")
            .replace("{startIndex}", ((page - 1) * 20 + 1).toString())
            .replace("{startPage}", page.toString())
            .replace("{startIndex?}", ((page - 1) * 20 + 1).toString())
            .replace("{startPage?}", page.toString())
            .replace("{count?}", "20")
    }
}
