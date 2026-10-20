package com.chief.anansi.data.repo

/**
 * The source ecosystems and their reserved numeric-id ranges. This is the
 * single place ranges are declared: fixed, non-overlapping, per-ecosystem
 * (LNReader covers both the v3 and v1-era generations of its plugins).
 * Only [SourceRegistryRepository] uses them to mint ids.
 */
enum class SourceEcosystem(
    val key: String,
    val numericIdMin: Long,
    val numericIdMax: Long,
) {
    LNREADER("lnreader", 1, 999_999),
    OPDS("opds", 1_000_000, 1_999_999),
    LOCAL("local", 2_000_000, 2_999_999),
}
