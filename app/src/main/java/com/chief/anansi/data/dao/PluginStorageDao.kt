package com.chief.anansi.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.chief.anansi.data.entity.PluginStorageEntity

@Dao
interface PluginStorageDao {

    @Upsert
    suspend fun save(entity: PluginStorageEntity)

    @Query("SELECT * FROM plugin_storage WHERE pluginId = :pluginId")
    suspend fun findByPluginId(pluginId: String): PluginStorageEntity?
}
