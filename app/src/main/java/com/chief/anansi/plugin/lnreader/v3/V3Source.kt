package com.chief.anansi.plugin.lnreader.v3

import com.chief.anansi.plugin.JsPlugin
import com.chief.anansi.plugin.JsPluginCallException
import com.chief.anansi.plugin.Source
import com.chief.anansi.plugin.SourceChapterItem
import com.chief.anansi.plugin.SourceNovel
import com.chief.anansi.plugin.SourceNovelItem
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Default User-Agent injected into image-request headers when the plugin
 * does not set one. Same browser-equivalent string the fetch bridge uses.
 */
internal const val DEFAULT_IMAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/113.0.0.0 Mobile Safari/537.36"

/**
 * Image-request metadata a cover loader needs (method, headers, body).
 * Headers carry the plugin's own values with the default User-Agent
 * injected when absent, so cover loading can attach them.
 */
data class V3ImageRequestInit(
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** The parts of the runtime inspection payload the adapter consumes. */
internal data class V3Inspection(
    /** Contract members the dist exposes as functions. */
    val members: Set<String>,
    val filters: JsonElement?,
    val pluginSettings: JsonElement?,
    val imageRequestInit: V3ImageRequestInit?,
)

/**
 * The LNReader v3 adapter: maps the plugin contract (popularNovels,
 * parseNovel, parseChapter, searchNovels) onto the novel-native [Source]
 * seam. Chapter HTML is normalized to plain text inside the adapter's
 * dist wrapper, so the reader and TTS consume plain text only. Filter and
 * settings schemas pass through as the plugin's own JSON; user values are
 * persisted in the plugin's storage scope and re-applied into the plugin's
 * filter objects at load. All errors are produced here, once, as
 * [SourceError] variants recorded into [registry] under the plugin id.
 */
class V3Source internal constructor(
    private val plugin: JsPlugin,
    private val registry: SourceErrorRegistry,
    private val inspected: V3Inspection,
    manifestItem: V3ManifestItem,
    override val id: Long,
) : Source {

    override val pluginId: String = manifestItem.id
    override val name: String = manifestItem.name
    override val lang: String = manifestItem.lang
    override val iconUrl: String = manifestItem.iconUrl
    override val ecosystem: String = "lnreader"
    override val generation: String = "v3"

    override val filters: JsonElement? = inspected.filters
    override val settings: JsonElement? = inspected.pluginSettings

    val imageRequestInit: V3ImageRequestInit = inspected.imageRequestInit
        ?: V3ImageRequestInit(headers = mapOf("User-Agent" to DEFAULT_IMAGE_USER_AGENT))

    override suspend fun popularNovels(
        page: Int,
        showLatestNovels: Boolean,
        filterValuesJson: String?,
    ): List<SourceNovelItem> = guarded {
        if (filterValuesJson != null) {
            saveFilterValues(filterValuesJson)
        }
        val result = call(
            "popularNovels",
            args(
                JsonPrimitive(page),
                buildJsonObject { put("showLatestNovels", showLatestNovels) },
            ),
        )
        parseNovelItems(result)
    }

    override suspend fun searchNovels(
        query: String,
        page: Int,
        filterValuesJson: String?,
    ): List<SourceNovelItem> = guarded {
        if (filterValuesJson != null) {
            saveFilterValues(filterValuesJson)
        }
        val result = call(
            "searchNovels",
            args(JsonPrimitive(query), JsonPrimitive(page)),
        )
        parseNovelItems(result)
    }

    override suspend fun novelDetails(path: String): SourceNovel = guarded {
        parseSourceNovel(path, call("parseNovel", args(JsonPrimitive(path))))
    }

    override suspend fun chapterText(novelPath: String, chapterPath: String): String = guarded {
        // The v3 contract needs only the chapter path; novelPath is ignored.
        val result = call("parseChapter", args(JsonPrimitive(chapterPath)))
        val text = (Json.parseToJsonElement(result) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            ?: throw parseFailure("Plugin \"$pluginId\" parseChapter did not return a string")
        if (text.isBlank()) {
            throw parseFailure("Plugin \"$pluginId\" returned an empty chapter for \"$chapterPath\"")
        }
        text
    }

    /** Persists user filter values into the plugin's storage scope. */
    suspend fun saveFilterValues(valuesJson: String) {
        saveValues("__anansiSaveFilterValues", valuesJson, "filter values")
    }

    /** Persists user settings values into the plugin's storage scope. */
    suspend fun saveSettingsValues(valuesJson: String) {
        saveValues("__anansiSaveSettingsValues", valuesJson, "settings values")
    }

    /** The stored filter values document, or null when nothing was saved. */
    suspend fun savedFilterValuesJson(): String? = readValues("__anansiReadFilterValues")

    /** The stored settings values document, or null when nothing was saved. */
    suspend fun savedSettingsValuesJson(): String? = readValues("__anansiReadSettingsValues")

    suspend fun close() {
        plugin.close()
    }

    private suspend fun saveValues(member: String, valuesJson: String, what: String) = guarded {
        val values = try {
            Json.parseToJsonElement(valuesJson)
        } catch (e: Exception) {
            throw parseFailure("Plugin \"$pluginId\" $what are not valid JSON: ${e.message}")
        }
        if (values !is JsonObject) {
            throw parseFailure("Plugin \"$pluginId\" $what must be a JSON object")
        }
        call(member, args(JsonPrimitive(valuesJson)))
    }

    private suspend fun readValues(member: String): String? = guarded {
        (Json.parseToJsonElement(call(member, args())) as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }?.content
    }

    private suspend fun call(method: String, argsJson: String): String = try {
        plugin.call(method, argsJson)
    } catch (e: SourcePluginException) {
        throw e
    } catch (e: JsPluginCallException) {
        throw SourcePluginException(classifyCallFailure(e), e)
    }

    private fun parseNovelItems(resultJson: String): List<SourceNovelItem> {
        val array = parseJson(resultJson) as? JsonArray
            ?: throw parseFailure("Plugin \"$pluginId\" catalog did not return a list")
        return array.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw parseFailure("Plugin \"$pluginId\" catalog item $index is not an object")
            SourceNovelItem(
                name = requiredString(item, "name", index),
                path = requiredString(item, "path", index),
                cover = optionalString(item, "cover"),
            )
        }
    }

    private fun parseSourceNovel(path: String, resultJson: String): SourceNovel {
        val novel = parseJson(resultJson) as? JsonObject
            ?: throw parseFailure("Plugin \"$pluginId\" parseNovel did not return an object")
        val chapters = when (val raw = novel["chapters"]) {
            null, is JsonNull -> JsonArray(emptyList())
            is JsonArray -> raw
            else -> throw parseFailure("Plugin \"$pluginId\" chapters is not a list")
        }
        val name = optionalString(novel, "name")
        // An all-empty result is a plugin failure, not a nameless novel with
        // no chapters; it fails loudly like an empty chapter does.
        if (name.isNullOrBlank() && chapters.isEmpty()) {
            throw parseFailure("Plugin \"$pluginId\" parseNovel returned no name and no chapters")
        }
        return SourceNovel(
            path = path,
            name = name,
            cover = optionalString(novel, "cover"),
            genres = textish(novel, "genres"),
            summary = textish(novel, "summary"),
            author = textish(novel, "author"),
            artist = textish(novel, "artist"),
            status = normalizeStatus(novel["status"]),
            totalPages = (novel["totalPages"] as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()?.toInt(),
            chapters = chapters.mapIndexed { index, element ->
                val chapter = element as? JsonObject
                    ?: throw parseFailure("Plugin \"$pluginId\" chapter $index is not an object")
                SourceChapterItem(
                    name = requiredString(chapter, "name", index),
                    path = requiredString(chapter, "path", index),
                    chapterNumber = (chapter["chapterNumber"] as? JsonPrimitive)
                        ?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull(),
                    releaseTime = optionalString(chapter, "releaseTime"),
                    scanlator = scanlator(chapter["scanlator"]),
                    page = optionalString(chapter, "page"),
                )
            },
        )
    }

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block().also { registry.clear(pluginId) }
        } catch (e: SourcePluginException) {
            registry.report(pluginId, e.error)
            throw e
        } catch (e: JsPluginCallException) {
            val error = classifyCallFailure(e)
            registry.report(pluginId, error)
            throw SourcePluginException(error, e)
        }

    private fun parseJson(resultJson: String): JsonElement =
        try {
            Json.parseToJsonElement(resultJson)
        } catch (e: Exception) {
            throw parseFailure("Plugin \"$pluginId\" returned malformed JSON: ${e.message}")
        }

    private fun requiredString(obj: JsonObject, field: String, index: Int): String =
        optionalString(obj, field)
            ?: throw parseFailure("Plugin \"$pluginId\" item $index is missing \"$field\"")

    private fun optionalString(obj: JsonObject, field: String): String? = when (val value = obj[field]) {
        null, is JsonNull -> null
        is JsonPrimitive -> value.content
        else -> null
    }

    private fun textish(obj: JsonObject, field: String): String? = when (val value = obj[field]) {
        null, is JsonNull -> null
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            .joinToString(", ")
            .ifEmpty { null }
        is JsonPrimitive -> value.content
        else -> null
    }

    private fun scanlator(value: JsonElement?): String? = when (value) {
        null, is JsonNull -> null
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            .joinToString(", ")
            .ifEmpty { null }
        is JsonPrimitive -> value.content
        else -> null
    }

    private fun parseFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Parse(reason), null)

    private fun args(vararg elements: JsonElement): String = JsonArray(elements.toList()).toString()
}

/**
 * Adapter-boundary classification of an unclassified plugin-call failure:
 * transport failures the fetch bridge reported stay [SourceError.Network];
 * everything else means the site answered but its content could not be
 * interpreted, so it is [SourceError.Parse]. The visible reason is kept.
 */
internal fun classifyCallFailure(e: JsPluginCallException): SourceError {
    val message = e.message ?: "unknown plugin failure"
    return if (message.startsWith("Fetch failed for ") || message.startsWith("Unsupported fetch URL: ")) {
        SourceError.Network(message)
    } else {
        SourceError.Parse(message)
    }
}

/** The plugin status vocabulary v3 statuses normalize onto. */
private val STATUS_VOCABULARY = listOf(
    "Unknown",
    "Ongoing",
    "Completed",
    "Licensed",
    "Publishing Finished",
    "Cancelled",
    "On Hiatus",
)

/**
 * Statuses normalize onto the plugin status vocabulary: recognized values
 * (case-insensitively) keep their canonical spelling, and raw numeric or
 * unrecognized string statuses map to Unknown rather than being dropped.
 * An absent status stays absent.
 */
internal fun normalizeStatus(status: JsonElement?): String? {
    if (status == null || status is JsonNull) return null
    val primitive = status as? JsonPrimitive ?: return STATUS_VOCABULARY[0]
    if (!primitive.isString) return STATUS_VOCABULARY[0]
    val text = primitive.content.trim()
    return STATUS_VOCABULARY.firstOrNull { it.equals(text, ignoreCase = true) } ?: STATUS_VOCABULARY[0]
}
