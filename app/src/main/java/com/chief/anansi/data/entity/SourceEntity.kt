package com.chief.anansi.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A registered content source. `identity` is the stable cross-phase string
 * `ECOSYSTEM_{sourceId}` (e.g. `lnreader_13`, `opds_1`, `local_1`) and
 * `numericId` is minted once from the ecosystem's reserved range at
 * registration. Both are unique and are never recomputed outside
 * [com.chief.anansi.data.repo.SourceRegistryRepository].
 */
@Entity(
    tableName = "sources",
    indices = [
        Index(value = ["identity"], unique = true),
        Index(value = ["numericId"], unique = true),
    ],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ecosystem: String,
    val sourceId: String,
    val identity: String,
    val numericId: Long,
    val displayName: String,
)
