package com.chief.anansi.plugin.lnreader.v3

import com.chief.anansi.plugin.JsPluginCallException
import com.chief.anansi.plugin.JsSourceRuntime
import com.chief.anansi.plugin.PluginIds
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Loads a v3 plugin dist into the shared JS runtime and adapts it to a
 * [com.chief.anansi.plugin.Source].
 *
 * Every dist is loaded wrapped by [ADAPTER_WRAPPER], the adapter's own
 * postlude that runs inside the dist's module after its plugin object
 * exists: stored filter/settings values are re-applied from the plugin's
 * storage scope into the plugin's filter objects (so every catalog call
 * rides on live, user-valued filters), the default image-request
 * User-Agent is injected when the plugin does not set one, and chapter
 * HTML is normalized to plain text at this boundary.
 *
 * The generation check replaces upstream's silent try/catch anti-pattern:
 * a dist is a v3 plugin only when parseNovel is present; a dist exposing
 * the v1-era parseNovelAndChapters instead is reported as incompatible
 * with that reason, never silently swallowed.
 */
object V3PluginLoader {

    /**
     * Validates the manifest item's id (untrusted input), loads the dist
     * through the runtime, runs the generation check, and returns the
     * adapted source. Every failure is reported into [registry] under the
     * plugin id before being rethrown as the authoritative
     * [SourcePluginException].
     */
    suspend fun load(
        runtime: JsSourceRuntime,
        registry: SourceErrorRegistry,
        sourceId: Long,
        item: V3ManifestItem,
        dist: String,
    ): V3Source {
        val pluginId = try {
            PluginIds.validate(item.id)
        } catch (e: SourcePluginException) {
            registry.report(item.id, e.error)
            throw e
        }
        return failThrough(pluginId, registry) {
            val plugin = runtime.load(pluginId, dist + wrapper())
            val inspected = try {
                parseInspection(plugin.inspect())
            } catch (e: JsPluginCallException) {
                throw SourcePluginException(classifyCallFailure(e), e)
            } catch (e: Exception) {
                throw SourcePluginException(
                    SourceError.Parse("Plugin \"$pluginId\" inspection failed: ${e.message}"),
                    e,
                )
            }
            checkGeneration(pluginId, inspected)
            V3Source(plugin, registry, inspected, item, sourceId)
        }
    }

    private fun checkGeneration(pluginId: String, inspected: V3Inspection) {
        // Generation discriminator: parseNovel is v3-only; parseNovelAndChapters marks v1-era code.
        if ("parseNovel" !in inspected.members) {
            if ("parseNovelAndChapters" in inspected.members) {
                throw incompatible(
                    pluginId,
                    "v1-era plugin loaded by the v3 adapter: plugin \"$pluginId\" " +
                        "exposes parseNovelAndChapters but not parseNovel",
                )
            }
            throw incompatible(pluginId, "Plugin \"$pluginId\" is not a v3 plugin: parseNovel is missing")
        }
        if ("parseChapter" !in inspected.members) {
            throw incompatible(pluginId, "Plugin \"$pluginId\" is not a v3 plugin: parseChapter is missing")
        }
        if ("searchNovels" !in inspected.members) {
            throw incompatible(pluginId, "Plugin \"$pluginId\" is not a v3 plugin: searchNovels is missing")
        }
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

    private fun parseInspection(inspectionJson: String): V3Inspection {
        val obj = Json.parseToJsonElement(inspectionJson).jsonObject
        return V3Inspection(
            members = membersOf(obj["members"]),
            filters = obj["filters"]?.takeUnless { it is JsonNull },
            pluginSettings = obj["pluginSettings"]?.takeUnless { it is JsonNull },
            imageRequestInit = obj["imageRequestInit"]?.takeUnless { it is JsonNull }
                ?.let(::parseImageRequestInit),
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

    private fun parseImageRequestInit(element: JsonElement): V3ImageRequestInit? {
        val obj = element as? JsonObject ?: return null
        val headers = (obj["headers"] as? JsonObject)
            ?.entries
            ?.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { name to it.content }
            }
            ?.toMap()
            .orEmpty()
        return V3ImageRequestInit(
            method = (obj["method"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
            headers = headers,
            body = (obj["body"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        )
    }

    private fun wrapper(): String =
        ADAPTER_WRAPPER.replace("__DEFAULT_IMAGE_USER_AGENT__", DEFAULT_IMAGE_USER_AGENT)

    /**
     * The adapter postlude appended to every dist. It stays a plain Kotlin
     * raw string (no template literals in the JS, so no `$` interpolation
     * surprises); the image-request User-Agent default is substituted in
     * as __DEFAULT_IMAGE_USER_AGENT__.
     */
    private const val ADAPTER_WRAPPER = """
;
(function () {
  "use strict";
  var plugin = exports.default;
  if (!plugin || typeof plugin !== "object") return;
  var store = require("@libs/storage").storage;
  var FILTER_KEY = "anansi.filterValues";
  var SETTINGS_KEY = "anansi.pluginSettings";

  function applyValues(target, values) {
    if (!target || !values || typeof values !== "object") return;
    Object.keys(values).forEach(function (key) {
      var entry = target[key];
      if (entry && typeof entry === "object" && "value" in entry) {
        entry.value = values[key];
      }
    });
  }

  applyValues(plugin.filters, store.get(FILTER_KEY));
  applyValues(plugin.pluginSettings, store.get(SETTINGS_KEY));

  plugin.__anansiSaveFilterValues = function (valuesJson) {
    var values = JSON.parse(valuesJson);
    store.set(FILTER_KEY, values);
    applyValues(plugin.filters, values);
    return true;
  };
  plugin.__anansiSaveSettingsValues = function (valuesJson) {
    var values = JSON.parse(valuesJson);
    store.set(SETTINGS_KEY, values);
    applyValues(plugin.pluginSettings, values);
    return true;
  };
  plugin.__anansiReadFilterValues = function () {
    var values = store.get(FILTER_KEY);
    return values == null ? null : JSON.stringify(values);
  };
  plugin.__anansiReadSettingsValues = function () {
    var values = store.get(SETTINGS_KEY);
    return values == null ? null : JSON.stringify(values);
  };

  var imageRequestInit = plugin.imageRequestInit || { headers: {} };
  if (
    imageRequestInit.headers &&
    imageRequestInit.headers["User-Agent"] == null
  ) {
    imageRequestInit.headers = Object.assign(
      { "User-Agent": "__DEFAULT_IMAGE_USER_AGENT__" },
      imageRequestInit.headers
    );
  }
  plugin.imageRequestInit = imageRequestInit;

  if (typeof plugin.parseChapter === "function") {
    var parseChapter = plugin.parseChapter;
    var cheerio = require("cheerio");
    plugin.parseChapter = function (chapterPath) {
      return Promise.resolve(parseChapter.call(plugin, chapterPath)).then(
        function (body) {
          if (typeof body !== "string" || !/<[a-zA-Z!/][^>]*>/.test(body)) {
            return body;
          }
          var dom = cheerio.load(body);
          dom("br").replaceWith("\n");
          dom("p, div, h1, h2, h3, h4, h5, h6, li").after("\n\n");
          return String(dom("body").text())
            .replace(/\n{3,}/g, "\n\n")
            .trim();
        }
      );
    };
  }
})();
"""
}
