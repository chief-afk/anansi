package com.chief.anansi.plugin

/**
 * A narrow JS host behind which the engine lives. Per-plugin isolation and
 * an explicit lifecycle: load, inspect, call, close. All results cross the
 * seam as JSON strings so the engine choice (quickjs-kt now, headless
 * WebView fallback) never leaks to callers.
 */
interface JsSourceRuntime {

    /**
     * Evaluates a plugin dist in a fresh, isolated engine slot and returns
     * a handle to it.
     *
     * @throws SourcePluginException when the dist is empty, fails to
     *   evaluate, or the resulting object lacks the plugin contract
     *   members; the carried [com.chief.anansi.plugin.error.SourceError]
     *   states the visible reason.
     */
    suspend fun load(pluginId: String, dist: String): JsPlugin
}

/**
 * One loaded plugin inside its own engine slot. Not thread-shared state:
 * every method is suspend and the underlying engine serializes work.
 */
interface JsPlugin {

    val pluginId: String

    /**
     * Identity, exposed contract members, and the passthrough
     * filter/settings schema as a JSON object string.
     */
    suspend fun inspect(): String

    /**
     * Invokes a plugin member with positional JSON-array arguments and
     * returns the JSON-encoded result. Awaits plugin promises to
     * settlement.
     *
     * @throws SourcePluginException for host-contract violations (for
     *   example unsupported features the host rejects loudly).
     * @throws JsPluginCallException when the plugin call itself fails with
     *   an unclassified JS-side error; the message is the JS error's
     *   visible reason.
     */
    suspend fun call(method: String, argsJson: String): String

    /** Shuts the plugin's engine slot down; the handle is unusable after. */
    suspend fun close()
}

/** A plugin call failed with an unclassified JS-side error. */
class JsPluginCallException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
