package com.chief.anansi.plugin.repo

import com.chief.anansi.plugin.JsPluginCallException
import com.chief.anansi.plugin.JsSourceRuntime
import com.chief.anansi.plugin.PluginIds
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.lnreader.v3.classifyCallFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Generation probe for foreign repository entries: loads a dist into the
 * shared runtime in probe mode (load, inspect, close; nothing is kept) and
 * discriminates by the parse-member rule the LNReader adapters enforce at
 * load: parseNovel marks v3, parseNovelAndChapters marks v1-era. The
 * adapters' own checks stay the authoritative enforcement when the source
 * is later loaded for real; this probe only routes registration.
 */
class GenerationProbe(private val runtime: JsSourceRuntime) {

    /** Returns [SourceGenerations.V3] or [SourceGenerations.V1ERA]. */
    suspend fun probe(pluginId: String, dist: String): String {
        val safeId = PluginIds.validate(pluginId)
        val plugin = runtime.load(safeId, dist)
        val members = try {
            val inspection = try {
                plugin.inspect()
            } catch (e: JsPluginCallException) {
                throw SourcePluginException(classifyCallFailure(e), e)
            } catch (e: SourcePluginException) {
                throw e
            } catch (e: Exception) {
                throw SourcePluginException(
                    SourceError.Parse("Plugin \"$safeId\" inspection failed: ${e.message}"),
                    e,
                )
            }
            membersOf(inspection)
        } finally {
            runCatching { plugin.close() }
        }
        return when {
            "parseNovel" in members -> SourceGenerations.V3
            "parseNovelAndChapters" in members -> SourceGenerations.V1ERA
            else -> throw SourcePluginException(
                SourceError.Incompatible(
                    "Plugin \"$safeId\" is neither a v3 nor a v1-era LNReader plugin: " +
                        "parseNovel and parseNovelAndChapters are both missing",
                ),
            )
        }
    }

    private fun membersOf(inspectionJson: String): Set<String> {
        val members = Json.parseToJsonElement(inspectionJson).jsonObject["members"]
        val obj = members as? JsonObject ?: return emptySet()
        return obj.entries
            .mapNotNull { (name, value) ->
                if ((value as? JsonPrimitive)?.content == "true") name else null
            }
            .toSet()
    }
}
