package com.chief.anansi.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A registered content source. `identity` is the stable cross-phase string
 * `ECOSYSTEM_{sourceId}` (e.g. `lnreader_13`, `opds_1`, `local_1`) and
 * `numericId` is minted once from the ecosystem's reserved range at
 * registration. Both are unique and are never recomputed outside
 * [com.chief.anansi.data.repo.SourceRegistryRepository].
 *
 * The registration columns (schema v2, the repo phase): `generation` is the
 * plugin API generation inside the ecosystem ("v3" / "v1era"), `repoUrl` is
 * the repository manifest URL that registered the source, `distUrl` is the
 * resolved plugin dist location the adapters load at runtime, and `lang` /
 * `iconUrl` are the display metadata the Browse list shows. `enabled`
 * excludes a source from Browse catalogs and search; `pinned` keeps it at
 * the top of the Browse list.
 */
@Entity(
    tableName = "sources",
    indices = [
        Index(value = ["identity"], unique = true),
        Index(value = ["numericId"], unique = true),
        Index(value = ["repoUrl"]),
    ],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ecosystem: String,
    val sourceId: String,
    val identity: String,
    val numericId: Long,
    val displayName: String,
    // The declared defaults match MIGRATION_1_2's ADD COLUMN literals; Room
    // verifies them against the migrated database.
    @ColumnInfo(defaultValue = "") val generation: String = "",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "") val repoUrl: String = "",
    @ColumnInfo(defaultValue = "") val distUrl: String = "",
    @ColumnInfo(defaultValue = "") val lang: String = "",
    @ColumnInfo(defaultValue = "") val iconUrl: String = "",
)
