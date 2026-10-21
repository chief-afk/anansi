package com.chief.anansi.plugin.repo

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.lnreader.v3.V3Manifest
import com.chief.anansi.plugin.lnreader.v3.V3ManifestItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The supported plugin API generations inside the lnreader ecosystem. These
 * are the labels the adapters' load checks produce; the normalizer stores
 * them, it never invents new ones.
 */
object SourceGenerations {
    const val V3 = "v3"
    const val V1ERA = "v1era"
    val ALL = setOf(V3, V1ERA)
}

/** The lnreader ecosystem key; the only ecosystem with repo manifests in v0.1. */
const val ECOSYSTEM_LNREADER = "lnreader"

/** One normalized repository entry, ready for probing and registration. */
data class RepoEntry(
    val index: Int,
    val pluginId: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val iconUrl: String,
    val customCSS: String?,
    /** Absolute dist URL, resolved against the manifest URL. */
    val distUrl: String,
    val ecosystem: String,
    /** Non-null only for Anansi manifests whose entries declare a generation. */
    val declaredGeneration: String?,
)

/** One malformed or unsupported entry, kept beside the valid ones. */
data class RepoEntryError(val index: Int, val pluginId: String?, val reason: String)

enum class RepoManifestKind { ANANSI, LNREADER_FOREIGN }

/**
 * A normalized repository manifest: the entries that may proceed to probe
 * and registration plus the per-entry failures, and the manifest kind with
 * its declared API version (Anansi manifests only).
 */
data class NormalizedRepoManifest(
    val kind: RepoManifestKind,
    val apiVersion: String?,
    val entries: List<RepoEntry>,
    val entryErrors: List<RepoEntryError>,
)

/**
 * The repository manifest normalizer with the `repositoryType`
 * discriminator: a JSON object declaring `repositoryType: "anansi"` carries
 * explicit format fields (`format: 1`, `apiVersion`, and a `plugins` array
 * whose entries declare their ecosystem and may override the generation);
 * anything arriving as the LNReader plugins.min.json array shape is the
 * known foreign format and its entries carry no generation. Unknown
 * repository types fail with a visible reason.
 */
object RepoManifests {

    private val json = Json { ignoreUnknownKeys = true }

    private val REQUIRED_ENTRY_FIELDS =
        listOf("id", "name", "site", "lang", "version", "url", "iconUrl")

    fun normalize(manifestJson: String, manifestUrl: String): NormalizedRepoManifest {
        val root = try {
            json.parseToJsonElement(manifestJson)
        } catch (e: Exception) {
            throw manifestFailure("Repository manifest is not valid JSON: ${e.message}")
        }
        return when (root) {
            is JsonArray -> normalizeForeign(root, manifestUrl)
            is JsonObject -> normalizeAnansi(root, manifestUrl)
            else -> throw manifestFailure(
                "Repository manifest is neither an Anansi repository manifest nor an LNReader plugin listing",
            )
        }
    }

    /**
     * Foreign format: the LNReader plugins.min.json array. Entry validation
     * and the all-entries-failed hard error are the adapter parser's own
     * gate (one manifest-item validation path); entries leave without a
     * declared generation and are probed at add time.
     */
    private fun normalizeForeign(root: JsonArray, manifestUrl: String): NormalizedRepoManifest {
        val listing = V3Manifest.parse(root.toString())
        return NormalizedRepoManifest(
            kind = RepoManifestKind.LNREADER_FOREIGN,
            apiVersion = null,
            entries = listing.items.mapIndexed { index, item -> entryFromItem(item, index, manifestUrl) },
            entryErrors = listing.entryErrors.map { RepoEntryError(it.index, null, it.reason) },
        )
    }

    private fun normalizeAnansi(root: JsonObject, manifestUrl: String): NormalizedRepoManifest {
        val repositoryType = (root["repositoryType"] as? JsonPrimitive)?.takeIf { it.isString }
        if (repositoryType == null) {
            throw manifestFailure(
                "Repository manifest is not an Anansi repository manifest (repositoryType is missing) " +
                    "and not an LNReader plugin listing (expected a JSON array)",
            )
        }
        if (repositoryType.content != "anansi") {
            throw manifestFailure(
                "Repository manifest declares unsupported repositoryType \"${repositoryType.content}\"",
            )
        }
        val format = (root["format"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (format != 1) {
            val declared = (root["format"] as? JsonPrimitive)?.content ?: "missing"
            throw manifestFailure("Repository manifest format $declared is not supported (expected format 1)")
        }
        val apiVersion = (root["apiVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (apiVersion == null || apiVersion !in SourceGenerations.ALL) {
            throw manifestFailure(
                "Repository manifest declares unsupported apiVersion " +
                    "\"${apiVersion ?: "missing"}\" (supported: v3, v1era)",
            )
        }
        val plugins = root["plugins"] as? JsonArray
            ?: throw manifestFailure("Repository manifest is missing its \"plugins\" array")

        val entries = ArrayList<RepoEntry>(plugins.size)
        val entryErrors = ArrayList<RepoEntryError>()
        plugins.forEachIndexed { index, element ->
            try {
                entries += parseAnansiEntry(element, index, apiVersion, manifestUrl)
            } catch (e: RepoEntryException) {
                entryErrors += RepoEntryError(index, e.pluginId, e.reason)
            }
        }
        return NormalizedRepoManifest(
            kind = RepoManifestKind.ANANSI,
            apiVersion = apiVersion,
            entries = entries,
            entryErrors = entryErrors,
        )
    }

    private fun parseAnansiEntry(
        element: JsonElement,
        index: Int,
        apiVersion: String,
        manifestUrl: String,
    ): RepoEntry {
        if (element !is JsonObject) {
            throw RepoEntryException(null, "Repository manifest item \"$index\" is malformed")
        }
        for (field in REQUIRED_ENTRY_FIELDS) {
            val value = element[field]
            when {
                value == null || (value is JsonPrimitive && value.isString && value.content.isBlank()) ->
                    throw RepoEntryException(
                        pluginIdOf(element),
                        "Repository manifest item \"$index\" is missing \"$field\"",
                    )
                value !is JsonPrimitive || !value.isString ->
                    throw RepoEntryException(
                        pluginIdOf(element),
                        "Repository manifest item \"$index\" field \"$field\" is not a string",
                    )
            }
        }
        val ecosystem = (element["ecosystem"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RepoEntryException(
                pluginIdOf(element),
                "Repository manifest item \"$index\" is missing \"ecosystem\"",
            )
        if (ecosystem != ECOSYSTEM_LNREADER) {
            throw RepoEntryException(
                pluginIdOf(element),
                "Repository manifest item \"$index\" declares unsupported ecosystem \"$ecosystem\"",
            )
        }
        val generation = (element["generation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (generation != null && generation !in SourceGenerations.ALL) {
            throw RepoEntryException(
                pluginIdOf(element),
                "Repository manifest item \"$index\" declares unsupported generation \"$generation\"",
            )
        }
        return entryFromItem(
            item = V3ManifestItem(
                id = element.getValue("id").jsonPrimitive.content,
                name = element.getValue("name").jsonPrimitive.content,
                site = element.getValue("site").jsonPrimitive.content,
                lang = element.getValue("lang").jsonPrimitive.content,
                version = element.getValue("version").jsonPrimitive.content,
                url = element.getValue("url").jsonPrimitive.content,
                iconUrl = element.getValue("iconUrl").jsonPrimitive.content,
                customCSS = (element["customCSS"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            ),
            index = index,
            manifestUrl = manifestUrl,
            ecosystem = ecosystem,
            declaredGeneration = generation ?: apiVersion,
        )
    }

    private fun entryFromItem(
        item: V3ManifestItem,
        index: Int,
        manifestUrl: String,
        ecosystem: String = ECOSYSTEM_LNREADER,
        declaredGeneration: String? = null,
    ): RepoEntry = RepoEntry(
        index = index,
        pluginId = item.id,
        name = item.name,
        site = item.site,
        lang = item.lang,
        version = item.version,
        iconUrl = item.iconUrl,
        customCSS = item.customCSS,
        distUrl = V3Manifest.resolveDistUrl(item.url, manifestUrl),
        ecosystem = ecosystem,
        declaredGeneration = declaredGeneration,
    )

    private fun pluginIdOf(element: JsonObject): String? =
        (element["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun manifestFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Parse(reason))

    private class RepoEntryException(val pluginId: String?, val reason: String) : RuntimeException(reason)
}
