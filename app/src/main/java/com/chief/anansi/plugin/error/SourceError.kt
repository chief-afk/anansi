package com.chief.anansi.plugin.error

/**
 * The single-authority error taxonomy for source operations (Technical
 * Decision 6). Every variant carries a visible human-readable reason.
 * Errors are produced once, at the adapter/compat boundary, and recorded
 * into a per-source registry; UI surfaces display them and never
 * reclassify.
 */
sealed class SourceError(val reason: String) {

    /** The site or repository could not be reached or answered unusably. */
    class Network(reason: String) : SourceError(reason)

    /** The site answered, but its content could not be interpreted. */
    class Parse(reason: String) : SourceError(reason)

    /** The source is not a plugin this app can run (wrong contract or generation). */
    class Incompatible(reason: String) : SourceError(reason)

    /** The source needs JavaScript beyond what the bundled host supports. */
    class RequiresJs(reason: String) : SourceError(reason)

    /** The source uses a feature this host deliberately does not provide. */
    class UnsupportedFeature(reason: String) : SourceError(reason)
}

/**
 * Thrown by the plugin host at the compat boundary when a plugin dist or
 * one of its host-contract interactions violates the supported contract.
 * Carries the authoritative [SourceError] so the adapter can record it
 * without reclassifying.
 */
class SourcePluginException(
    val error: SourceError,
    cause: Throwable? = null,
) : RuntimeException(error.reason, cause)

/**
 * Per-source error registry. Persistence and UI display arrive with the
 * source-management phase; this phase ships the interface and the
 * in-memory implementation.
 */
interface SourceErrorRegistry {

    /** Records the latest error for a source, replacing any previous one. */
    fun report(sourceId: String, error: SourceError)

    /** The latest recorded error for a source, or null when it is error-free. */
    fun latest(sourceId: String): SourceError?

    /** Clears the recorded error for a source (e.g. after a successful run). */
    fun clear(sourceId: String)
}

class InMemorySourceErrorRegistry : SourceErrorRegistry {

    private val latestBySource = HashMap<String, SourceError>()

    @Synchronized
    override fun report(sourceId: String, error: SourceError) {
        latestBySource[sourceId] = error
    }

    @Synchronized
    override fun latest(sourceId: String): SourceError? = latestBySource[sourceId]

    @Synchronized
    override fun clear(sourceId: String) {
        latestBySource.remove(sourceId)
    }
}
