package com.chief.anansi.data.repo

/**
 * The source ecosystems and their reserved numeric-id ranges. This is the
 * single place ranges are declared: fixed, non-overlapping, per-ecosystem
 * (LNReader covers both the v3 and v1-era generations of its plugins).
 * Only [SourceRegistryRepository] uses the ranges to mint ids.
 */
enum class SourceEcosystem(
    val key: String,
    val numericIdMin: Long,
    val numericIdMax: Long,
) {
    LNREADER("lnreader", 1, 999_999),
    OPDS("opds", 1_000_000, 1_999_999),
    LOCAL("local", 2_000_000, 2_999_999),
    ;

    /**
     * The stable `ECOSYSTEM_{sourceId}` identity string for a plugin id.
     * Deterministic by construction, so a v1 backup import can compute the
     * identity a novel will have once its plugin's repository is added,
     * before any registration happens.
     */
    fun identity(sourceId: String): String = "${key}_$sourceId"
}
