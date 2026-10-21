package com.chief.anansi.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The last recorded [com.chief.anansi.plugin.error.SourceError] for a
 * source, keyed by the same registry key the adapters report under (the
 * plugin id). Persistence lands with the source-management phase so the
 * management screen's error lines survive restarts; the taxonomy itself
 * stays the plugin layer's: `kind` is the variant name, never reclassified
 * on read.
 */
@Entity(tableName = "source_errors")
data class SourceErrorEntity(
    @PrimaryKey val sourceId: String,
    val kind: String,
    val reason: String,
    val updatedAt: Long,
)
