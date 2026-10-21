package com.chief.anansi.plugin.lnreader.v1era

import com.chief.anansi.plugin.JsPluginCallException
import com.chief.anansi.plugin.JsSourceRuntime
import com.chief.anansi.plugin.PluginIds
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.lnreader.v3.classifyCallFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Loads a v1-era LNReader module (the pre-dist source line: popularNovels,
 * parseNovelAndChapters, parseChapter, searchNovels) into the shared JS
 * runtime and adapts it to a [com.chief.anansi.plugin.Source].
 *
 * There is no manifest for this generation: display metadata arrives from
 * the caller (the registration side), with the module's own fields as the
 * fallback. The in-code numeric sourceId is never used for identity; the
 * [sourceId] argument is the identity minted at registration, using the
 * scheme, so the id-scheme chaos cannot occur.
 *
 * The generation check replaces upstream's silent try/catch anti-pattern:
 * parseNovelAndChapters is the v1-era discriminator (v3 exposes parseNovel
 * instead), and the verdict is the loaded source's generation label, which
 * The same probe reuses for foreign repos.
 */
object V1EraPluginLoader {

    /**
     * Validates the caller-supplied plugin id (untrusted input), loads the
     * module through the runtime, runs the generation check, and returns
     * the adapted source. Every failure is reported into [registry] under
     * the plugin id before being rethrown as the authoritative
     * [SourcePluginException].
     */
    suspend fun load(
        runtime: JsSourceRuntime,
        registry: SourceErrorRegistry,
        sourceId: Long,
        pluginId: String,
        dist: String,
        name: String? = null,
        lang: String? = null,
        iconUrl: String? = null,
    ): V1EraSource {
        val safeId = try {
            PluginIds.validate(pluginId)
        } catch (e: SourcePluginException) {
            registry.report(pluginId, e.error)
            throw e
        }
        return failThrough(safeId, registry) {
            val plugin = runtime.load(safeId, dist + ADAPTER_WRAPPER)
            val inspected = try {
                parseInspection(plugin.inspect())
            } catch (e: JsPluginCallException) {
                throw SourcePluginException(classifyCallFailure(e), e)
            } catch (e: Exception) {
                throw SourcePluginException(
                    SourceError.Parse("Plugin \"$safeId\" inspection failed: ${e.message}"),
                    e,
                )
            }
            checkGeneration(safeId, inspected)
            V1EraSource(
                plugin = plugin,
                registry = registry,
                inspected = inspected,
                id = sourceId,
                pluginId = safeId,
                name = name ?: inspected.name ?: safeId,
                lang = lang ?: inspected.lang.orEmpty(),
                iconUrl = iconUrl ?: inspected.iconUrl,
            )
        }
    }

    private fun checkGeneration(pluginId: String, inspected: V1EraInspection) {
        // Generation discriminator: parseNovelAndChapters is v1-era only;
        // parseNovel marks v3 code loaded by the wrong adapter.
        if ("parseNovelAndChapters" in inspected.members) {
            if ("parseChapter" !in inspected.members) {
                throw incompatible(
                    pluginId,
                    "Plugin \"$pluginId\" is not a v1-era plugin: parseChapter is missing",
                )
            }
            if ("searchNovels" !in inspected.members) {
                throw incompatible(
                    pluginId,
                    "Plugin \"$pluginId\" is not a v1-era plugin: searchNovels is missing",
                )
            }
            return
        }
        if ("parseNovel" in inspected.members) {
            throw incompatible(
                pluginId,
                "v3 plugin loaded by the v1-era adapter: plugin \"$pluginId\" " +
                    "exposes parseNovel but not parseNovelAndChapters",
            )
        }
        throw incompatible(
            pluginId,
            "Plugin \"$pluginId\" is not a v1-era plugin: parseNovelAndChapters is missing",
        )
    }

    private fun incompatible(pluginId: String, reason: String): SourcePluginException =
        SourcePluginException(SourceError.Incompatible(reason))

    private suspend fun <T> failThrough(
        pluginId: String,
        registry: SourceErrorRegistry,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: SourcePluginException) {
        registry.report(pluginId, e.error)
        throw e
    }

    private fun parseInspection(inspectionJson: String): V1EraInspection {
        val obj = Json.parseToJsonElement(inspectionJson).jsonObject
        return V1EraInspection(
            members = membersOf(obj["members"]),
            filters = obj["filters"]?.takeUnless { it is JsonNull },
            name = (obj["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
            lang = (obj["lang"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
            iconUrl = (obj["iconUrl"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        )
    }

    private fun membersOf(element: JsonElement?): Set<String> {
        val members = element as? JsonObject ?: return emptySet()
        return members.entries
            .mapNotNull { (name, value) ->
                if ((value as? JsonPrimitive)?.content == "true") name else null
            }
            .toSet()
    }

    /**
     * The adapter postlude appended to every v1-era module. Unlike the v3
     * wrapper it does not re-apply values into filter objects (the v1-era
     * filters array is a schema; user values ride the popularNovels options
     * argument, which the source builds from the stored document per call).
     * It hoists module-level exports into the exports.default shape the
     * host expects, adds the storage-backed filter-values members, and
     * wraps parseChapter to normalize HTML chapter bodies to plain text
     * inside the SourceChapter object.
     */
    private const val ADAPTER_WRAPPER = """
;
(function () {
  "use strict";
  if (!exports.default || typeof exports.default !== "object") {
    var mod = module.exports;
    if (mod && typeof mod === "object" && typeof mod.popularNovels === "function") {
      exports.default = mod;
    }
  }
  var plugin = exports.default;
  if (!plugin || typeof plugin !== "object") return;
  var store = require("@libs/storage").storage;
  var FILTER_KEY = "anansi.filterValues";

  plugin.__anansiSaveFilterValues = function (valuesJson) {
    store.set(FILTER_KEY, JSON.parse(valuesJson));
    return true;
  };
  plugin.__anansiReadFilterValues = function () {
    var values = store.get(FILTER_KEY);
    return values == null ? null : JSON.stringify(values);
  };

  if (typeof plugin.parseChapter === "function") {
    var parseChapter = plugin.parseChapter;
    var cheerio = require("cheerio");
    plugin.parseChapter = function (novelUrl, chapterUrl) {
      return Promise.resolve(parseChapter.call(plugin, novelUrl, chapterUrl)).then(
        function (chapter) {
          if (chapter == null || typeof chapter !== "object") return chapter;
          var text = chapter.chapterText;
          if (typeof text !== "string" || !/<[a-zA-Z!/][^>]*>/.test(text)) {
            return chapter;
          }
          var dom = cheerio.load(text);
          dom("br").replaceWith("\n");
          dom("p, div, h1, h2, h3, h4, h5, h6, li").after("\n\n");
          chapter.chapterText = String(dom("body").text())
            .replace(/\n{3,}/g, "\n\n")
            .trim();
          return chapter;
        }
      );
    };
  }
})();
"""
}
