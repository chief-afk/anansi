package com.chief.anansi.plugin.lnreader.v1era

import com.chief.anansi.plugin.JsPlugin
import com.chief.anansi.plugin.JsPluginCallException
import com.chief.anansi.plugin.Source
import com.chief.anansi.plugin.SourceChapterItem
import com.chief.anansi.plugin.SourceNovel
import com.chief.anansi.plugin.SourceNovelItem
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.lnreader.v3.classifyCallFailure
import com.chief.anansi.plugin.lnreader.v3.normalizeStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The parts of the runtime inspection payload the v1-era adapter consumes. */
internal data class V1EraInspection(
    /** Contract members the module exposes as functions. */
    val members: Set<String>,
    /** The v1-era filters array schema, passthrough. */
    val filters: JsonElement?,
    val name: String?,
    val lang: String?,
    val iconUrl: String?,
)

/**
 * The LNReader v1-era adapter: maps the module contract onto the
 * novel-native [Source] seam. Shape deviations from the v1-era contract
 * (the popularNovels envelope, the two-argument parseChapter returning a
 * SourceChapter object, the one-argument searchNovels with a bare-array
 * result) fail loudly instead of being silently normalized. Chapter HTML is
 * normalized to plain text inside the loader's module wrapper, so the
 * reader and TTS consume plain text only. Filter user values persist in the
 * plugin's storage scope and ride the popularNovels options argument. All
 * errors are produced here, once, as [SourceError] variants recorded into
 * [registry] under the plugin id.
 *
 * Call-failure classification and status normalization reuse the v3
 * adapter's module-wide helpers: one policy for both LNReader generations.
 */
class V1EraSource internal constructor(
    private val plugin: JsPlugin,
    private val registry: SourceErrorRegistry,
    private val inspected: V1EraInspection,
    override val id: Long,
    override val pluginId: String,
    override val name: String,
    override val lang: String,
    override val iconUrl: String?,
) : Source {

    override val ecosystem: String = "lnreader"

    /** The generation verdict the repository probe reads for foreign repos. */
    override val generation: String = "v1era"

    override val filters: JsonElement? = inspected.filters

    /** The v1-era contract has no settings concept; the schema is absent. */
    override val settings: JsonElement? = null

    override suspend fun popularNovels(
        page: Int,
        showLatestNovels: Boolean,
        filterValuesJson: String?,
    ): List<SourceNovelItem> = guarded {
        if (filterValuesJson != null) {
            saveFilterValues(filterValuesJson)
        }
        val options = buildJsonObject {
            put("showLatestNovels", showLatestNovels)
            // The filters key is always explicit so the host's call shim
            // never injects the raw v1-era schema array where the values
            // document belongs.
            put(
                "filters",
                savedFilterValuesJson()?.let { Json.parseToJsonElement(it) } ?: JsonNull,
            )
        }
        parseNovelItems(call("popularNovels", args(JsonPrimitive(page), options)))
    }

    override suspend fun searchNovels(
        query: String,
        page: Int,
        filterValuesJson: String?,
    ): List<SourceNovelItem> = guarded {
        if (filterValuesJson != null) {
            saveFilterValues(filterValuesJson)
        }
        // lnreader-v1 calls v1-era searchNovels with the search term only;
        // the page has no place in this generation's call convention.
        parseSearchItems(call("searchNovels", args(JsonPrimitive(query))))
    }

    override suspend fun novelDetails(path: String): SourceNovel = guarded {
        parseSourceNovel(path, call("parseNovelAndChapters", args(JsonPrimitive(path))))
    }

    override suspend fun chapterText(novelPath: String, chapterPath: String): String = guarded {
        val result = call(
            "parseChapter",
            args(JsonPrimitive(novelPath), JsonPrimitive(chapterPath)),
        )
        val chapter = parseJson(result) as? JsonObject
            ?: throw parseFailure(
                "Plugin \"$pluginId\" parseChapter did not return a SourceChapter object " +
                    "(v1-era shape deviation)",
            )
        val text = (chapter["chapterText"] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }?.content
            ?: throw parseFailure(
                "Plugin \"$pluginId\" parseChapter returned no string chapterText " +
                    "(v1-era shape deviation)",
            )
        if (text.isBlank()) {
            throw parseFailure("Plugin \"$pluginId\" returned an empty chapter for \"$chapterPath\"")
        }
        text
    }

    /** Persists user filter values into the plugin's storage scope. */
    suspend fun saveFilterValues(valuesJson: String) {
        val values = try {
            Json.parseToJsonElement(valuesJson)
        } catch (e: Exception) {
            throw parseFailure("Plugin \"$pluginId\" filter values are not valid JSON: ${e.message}")
        }
        if (values !is JsonObject) {
            throw parseFailure("Plugin \"$pluginId\" filter values must be a JSON object")
        }
        call("__anansiSaveFilterValues", args(JsonPrimitive(valuesJson)))
    }

    /** The stored filter values document, or null when nothing was saved. */
    suspend fun savedFilterValuesJson(): String? =
        (Json.parseToJsonElement(call("__anansiReadFilterValues", args())) as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }?.content

    suspend fun close() {
        plugin.close()
    }

    /**
     * Unwraps the `{ novels }` envelope into novel items; a bare array or
     * any other shape is a loud parse failure, never silently normalized.
     */
    private fun parseNovelItems(resultJson: String): List<SourceNovelItem> {
        val envelope = parseJson(resultJson) as? JsonObject
            ?: throw parseFailure(
                "Plugin \"$pluginId\" popularNovels did not return the { novels } envelope " +
                    "(v1-era shape deviation)",
            )
        val array = envelope["novels"] as? JsonArray
            ?: throw parseFailure(
                "Plugin \"$pluginId\" popularNovels envelope is missing a novels list " +
                    "(v1-era shape deviation)",
            )
        return array.map { element -> novelItem(element) }
    }

    /** Requires the bare-array search result this generation returns. */
    private fun parseSearchItems(resultJson: String): List<SourceNovelItem> {
        val array = parseJson(resultJson) as? JsonArray
            ?: throw parseFailure(
                "Plugin \"$pluginId\" searchNovels did not return a list (v1-era shape deviation)",
            )
        return array.map { element -> novelItem(element) }
    }

    private fun novelItem(element: JsonElement): SourceNovelItem {
        val item = element as? JsonObject
            ?: throw parseFailure("Plugin \"$pluginId\" novel item is not an object")
        return SourceNovelItem(
            name = optionalString(item, "novelName")
                ?: throw parseFailure("Plugin \"$pluginId\" novel item is missing \"novelName\""),
            path = optionalString(item, "novelUrl")
                ?: throw parseFailure("Plugin \"$pluginId\" novel item is missing \"novelUrl\""),
            cover = optionalString(item, "novelCover"),
        )
    }

    private fun parseSourceNovel(path: String, resultJson: String): SourceNovel {
        val novel = parseJson(resultJson) as? JsonObject
            ?: throw parseFailure("Plugin \"$pluginId\" parseNovelAndChapters did not return an object")
        val chapters = when (val raw = novel["chapters"]) {
            null, is JsonNull -> JsonArray(emptyList())
            is JsonArray -> raw
            else -> throw parseFailure("Plugin \"$pluginId\" chapters is not a list")
        }
        return SourceNovel(
            path = path,
            name = optionalString(novel, "novelName"),
            cover = optionalString(novel, "novelCover"),
            genres = optionalString(novel, "genre"),
            summary = optionalString(novel, "summary"),
            author = optionalString(novel, "author"),
            status = normalizeStatus(novel["status"]),
            chapters = chapters.map { element ->
                val chapter = element as? JsonObject
                    ?: throw parseFailure("Plugin \"$pluginId\" chapter is not an object")
                SourceChapterItem(
                    name = optionalString(chapter, "chapterName")
                        ?: throw parseFailure(
                            "Plugin \"$pluginId\" chapter is missing \"chapterName\"",
                        ),
                    path = optionalString(chapter, "chapterUrl")
                        ?: throw parseFailure(
                            "Plugin \"$pluginId\" chapter is missing \"chapterUrl\"",
                        ),
                    releaseTime = optionalString(chapter, "releaseDate"),
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

    private suspend fun call(method: String, argsJson: String): String = try {
        plugin.call(method, argsJson)
    } catch (e: SourcePluginException) {
        throw e
    } catch (e: JsPluginCallException) {
        throw SourcePluginException(classifyCallFailure(e), e)
    }

    private fun parseJson(resultJson: String): JsonElement =
        try {
            Json.parseToJsonElement(resultJson)
        } catch (e: Exception) {
            throw parseFailure("Plugin \"$pluginId\" returned malformed JSON: ${e.message}")
        }

    private fun optionalString(obj: JsonObject, field: String): String? = when (val value = obj[field]) {
        null, is JsonNull -> null
        is JsonPrimitive -> value.content
        else -> null
    }

    private fun parseFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Parse(reason), null)

    private fun args(vararg elements: JsonElement): String = JsonArray(elements.toList()).toString()
}
