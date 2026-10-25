package com.chief.anansi.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The persisted per-plugin storage document, keyed by plugin id: the host
 * shim's `{storage, local, session}` JSON blob stored wholesale in
 * [document]. Plugin filter values and settings land here through the
 * runtime's write-through store, so process death loses none of them.
 */
@Entity(tableName = "plugin_storage")
data class PluginStorageEntity(
    @PrimaryKey val pluginId: String,
    val document: String,
    val updatedAt: Long,
)
