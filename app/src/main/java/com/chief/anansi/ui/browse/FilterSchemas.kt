package com.chief.anansi.ui.browse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One renderable filter field, parsed from whichever passthrough grammar the
 * source exposes: the v3 FilterTypes record (`filters` object, string types)
 * or the v1-era filters array (numeric inputType). The schema itself is never
 * translated back to the plugin; this model only drives rendering, and user
 * values leave as a plain `{ key: value }` document under the schema's own
 * keys.
 */
sealed class CatalogFilter {
    abstract val key: String
    abstract val label: String

    data class Text(
        override val key: String,
        override val label: String,
        val value: String,
    ) : CatalogFilter()

    data class Picker(
        override val key: String,
        override val label: String,
        val options: List<FilterOption>,
        val selected: String?,
    ) : CatalogFilter()

    data class Toggle(
        override val key: String,
        override val label: String,
        val checked: Boolean,
    ) : CatalogFilter()

    data class Checks(
        override val key: String,
        override val label: String,
        val options: List<FilterOption>,
        val selected: Set<String>,
    ) : CatalogFilter()
}

data class FilterOption(val label: String, val value: String)

/** One user-entered filter value, keyed by the schema's own filter key. */
typealias FilterValues = Map<String, JsonElement>

/**
 * Parses the passthrough filter schema into renderable fields. Entries that
 * do not match their declared shape are skipped; a skipped field simply
 * carries no user value and the plugin keeps its own default.
 */
fun parseFilterSchema(filters: JsonElement?): List<CatalogFilter> = when (filters) {
    null, is JsonNull -> emptyList()
    is JsonObject -> filters.mapNotNull { (key, entry) -> v3Field(key, entry) }
    is JsonArray -> filters.mapNotNull(::v1EraField)
    else -> emptyList()
}

/** Serializes the user-entered values, or null when nothing was entered. */
fun filterValuesJson(values: FilterValues): String? =
    if (values.isEmpty()) null else JsonObject(values).toString()

private fun v3Field(key: String, entry: JsonElement): CatalogFilter? {
    val obj = entry as? JsonObject ?: return null
    val label = (obj["label"] as? JsonPrimitive)?.content ?: key
    val options = optionsOf(obj["options"])
    return when ((obj["type"] as? JsonPrimitive)?.content) {
        "Text" -> CatalogFilter.Text(
            key = key,
            label = label,
            value = (obj["value"] as? JsonPrimitive)?.content.orEmpty(),
        )

        "Picker" -> CatalogFilter.Picker(
            key = key,
            label = label,
            options = options,
            selected = (obj["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        )

        "Switch" -> CatalogFilter.Toggle(
            key = key,
            label = label,
            checked = (obj["value"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )

        "Checkbox", "XCheckbox" -> CatalogFilter.Checks(
            key = key,
            label = label,
            options = options,
            selected = (obj["value"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                ?.toSet()
                .orEmpty(),
        )

        else -> null
    }
}

private fun v1EraField(entry: JsonElement): CatalogFilter? {
    val obj = entry as? JsonObject ?: return null
    val key = (obj["key"] as? JsonPrimitive)?.content ?: return null
    val label = (obj["label"] as? JsonPrimitive)?.content ?: key
    val options = optionsOf(obj["values"])
    // FilterInputs: TextInput 0, Picker 1, Checkbox 2 (v1-era numeric enum).
    return when ((obj["inputType"] as? JsonPrimitive)?.content?.toIntOrNull()) {
        0 -> CatalogFilter.Text(key = key, label = label, value = "")
        1 -> CatalogFilter.Picker(key = key, label = label, options = options, selected = null)
        2 -> CatalogFilter.Checks(key = key, label = label, options = options, selected = emptySet())
        else -> null
    }
}

private fun optionsOf(element: JsonElement?): List<FilterOption> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { option ->
        val obj = option as? JsonObject ?: return@mapNotNull null
        val label = (obj["label"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            ?: return@mapNotNull null
        val value = (obj["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            ?: return@mapNotNull null
        FilterOption(label, value)
    }
}
