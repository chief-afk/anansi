package com.chief.anansi.plugin.lnreader.v3

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * One v3 manifest entry (the plugins.min.json schema): the seven required
 * string fields, plus the optional customCSS. There is no apiVersion field
 * upstream; generation is determined when the dist loads, not here.
 */
data class V3ManifestItem(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String,
    val customCSS: String? = null,
)

/** One malformed manifest entry, kept beside the valid ones. */
data class V3ManifestEntryError(val index: Int, val reason: String)

/** A parsed manifest listing: the valid entries plus the per-entry failures. */
data class V3ManifestListing(
    val items: List<V3ManifestItem>,
    val entryErrors: List<V3ManifestEntryError>,
)

/**
 * Parser for the v3 plugin manifest. Entries validate individually with the
 * first concrete error retained, so one malformed entry cannot take down
 * the rest of a repository's listing; a manifest that is not an array, or
 * where every entry is malformed, is a hard error.
 */
object V3Manifest {

    private val json = Json { ignoreUnknownKeys = true }

    private val REQUIRED_FIELDS = listOf("id", "name", "site", "lang", "version", "url", "iconUrl")

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun parse(manifestJson: String): V3ManifestListing {
        val root = try {
            json.parseToJsonElement(manifestJson)
        } catch (e: Exception) {
            throw manifestFailure("Plugin manifest is not valid JSON: ${e.message}")
        }
        if (root !is JsonArray) {
            throw manifestFailure("Plugin manifest has an unexpected format")
        }
        val items = ArrayList<V3ManifestItem>(root.size)
        val entryErrors = ArrayList<V3ManifestEntryError>()
        root.forEachIndexed { index, element ->
            try {
                items += parseItem(element, index)
            } catch (e: V3ManifestEntryException) {
                entryErrors += V3ManifestEntryError(index, e.reason)
            }
        }
        if (root.isNotEmpty() && items.isEmpty()) {
            throw manifestFailure(entryErrors.first().reason)
        }
        return V3ManifestListing(items, entryErrors)
    }

    /**
     * Manifest dist urls resolve relative to the manifest's dist tree: the
     * `.dist/` directory holding the manifest maps to the `.js/src/plugins/`
     * tree that holds the dists. Absolute and protocol-relative urls are
     * used as-is.
     */
    fun resolveDistUrl(distUrl: String, manifestUrl: String): String {
        if (distUrl.isBlank()) {
            throw manifestFailure("Plugin dist url is missing")
        }
        if (distUrl.startsWith("//") || SCHEME.containsMatchIn(distUrl)) {
            return distUrl
        }
        val lastSlash = manifestUrl.lastIndexOf('/')
        val manifestDir = if (lastSlash < 0) "" else manifestUrl.substring(0, lastSlash + 1)
        val distBase = if (manifestDir.endsWith(".dist/")) {
            manifestDir.removeSuffix(".dist/") + ".js/src/plugins/"
        } else {
            manifestDir
        }
        return distBase + distUrl
    }

    private fun parseItem(element: JsonElement, index: Int): V3ManifestItem {
        if (element !is JsonObject) {
            throw V3ManifestEntryException("Plugin manifest item \"$index\" is malformed")
        }
        for (field in REQUIRED_FIELDS) {
            val value = element[field]
            when {
                value == null || (value is JsonPrimitive && value.isString && value.content.isBlank()) ->
                    throw V3ManifestEntryException("Plugin manifest item \"$index\" is missing \"$field\"")
                value !is JsonPrimitive || !value.isString ->
                    throw V3ManifestEntryException(
                        "Plugin manifest item \"$index\" field \"$field\" is not a string",
                    )
            }
        }
        return V3ManifestItem(
            id = element.getValue("id").jsonPrimitive.content,
            name = element.getValue("name").jsonPrimitive.content,
            site = element.getValue("site").jsonPrimitive.content,
            lang = element.getValue("lang").jsonPrimitive.content,
            version = element.getValue("version").jsonPrimitive.content,
            url = element.getValue("url").jsonPrimitive.content,
            iconUrl = element.getValue("iconUrl").jsonPrimitive.content,
            customCSS = (element["customCSS"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }

    private fun manifestFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Parse(reason))

    private class V3ManifestEntryException(val reason: String) : RuntimeException(reason)
}
