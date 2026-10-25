package com.chief.anansi.data.repo

import com.chief.anansi.data.dao.PluginStorageDao
import com.chief.anansi.data.entity.PluginStorageEntity
import com.chief.anansi.plugin.runtime.PluginStorageStore

/**
 * Room-backed [PluginStorageStore]: one row per plugin holding the whole
 * storage document JSON, stamped with `updatedAt` at save. Filter values
 * and settings a plugin writes through its storage scope survive process
 * death and are preloaded into the runtime at slot load.
 */
class RoomPluginStorageStore(private val dao: PluginStorageDao) : PluginStorageStore {

    override suspend fun load(pluginId: String): String? =
        dao.findByPluginId(pluginId)?.document

    override suspend fun save(pluginId: String, document: String) {
        dao.save(
            PluginStorageEntity(
                pluginId = pluginId,
                document = document,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
