package com.chief.anansi.plugin.runtime

/**
 * The one owner of the plugin-engine, fetch, and search runtime constants.
 * Values validated by the phase profiling evidence
 * (`_docs/v011-review-fixes/3-engine-fetch-runtime/profiling.md`); injectable
 * copies ride constructor parameters that default to these.
 */
object SourceRuntimeLimits {
    /** Live engine slots kept before least-recently-used eviction. */
    const val SLOT_LRU_MAX_ENTRIES = 8

    /** Per-slot QuickJS heap limit; a plugin cannot OOM-kill the app. */
    const val SLOT_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024

    /** Wall-clock deadline for one plugin call, including its fetches. */
    const val CALL_DEADLINE_MILLIS = 60_000L

    /** Wall-clock deadline for one source inside a global search. */
    const val SEARCH_SOURCE_TIMEOUT_MILLIS = 45_000L

    /** Sources whose search calls may be in flight at once in a fan-out. */
    const val SEARCH_CONCURRENCY = 6

    /** Quiet time after the last keystroke before search auto-runs. */
    const val SEARCH_DEBOUNCE_MILLIS = 400L

    /** Shortest query the auto-run accepts; the IME action is not throttled. */
    const val SEARCH_MIN_QUERY_LENGTH = 2

    /** Cached plugin dist texts kept before least-recently-used eviction. */
    const val DIST_CACHE_MAX_ENTRIES = 24

    /** Whole-call HTTP budget, so a single fetch stays inside the deadline. */
    const val HTTP_CALL_TIMEOUT_MILLIS = 30_000L

    /** Response body (and gunzipped output) byte cap per fetch. */
    const val MAX_RESPONSE_BYTES = 20L * 1024 * 1024
}
