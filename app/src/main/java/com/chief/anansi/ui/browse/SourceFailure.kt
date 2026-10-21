package com.chief.anansi.ui.browse

import com.chief.anansi.plugin.error.SourcePluginException

/**
 * The kind label for a failed source call: the authoritative SourceError
 * variant name when the failure crossed the plugin boundary, otherwise the
 * failure's own class name. Display-only; nothing is reclassified.
 */
internal fun failureKindOf(e: Exception): String = when (e) {
    is SourcePluginException -> e.error::class.simpleName.orEmpty()
    else -> e::class.java.simpleName
}
