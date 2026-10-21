package com.chief.anansi.plugin

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException

/**
 * Safe-identifier gate for plugin ids, which are untrusted input from
 * foreign repositories. The rule is the LNReader plugin-id rule: letters
 * and digits, with single dot, dash, or underscore separators (no slashes,
 * no leading or trailing separators, no empty segments). Every boundary
 * that turns an id into a file path, storage key, or engine slot name
 * validates through this one validator, so the adapter load and the repo
 * import can never diverge.
 */
object PluginIds {

    private val SAFE_ID = Regex("[a-z0-9]+([._-][a-z0-9]+)*", RegexOption.IGNORE_CASE)

    fun isValid(pluginId: String): Boolean = pluginId.isNotEmpty() && SAFE_ID.matches(pluginId)

    /**
     * Returns the id when safe; otherwise fails loudly as incompatible
     * with the reason, before the id reaches any path or storage key.
     */
    fun validate(pluginId: String): String =
        if (isValid(pluginId)) {
            pluginId
        } else {
            throw SourcePluginException(
                SourceError.Incompatible(
                    "Plugin id \"$pluginId\" is not a safe plugin id " +
                        "(allowed: letters and digits with single dot, dash, or underscore separators)",
                ),
            )
        }
}
