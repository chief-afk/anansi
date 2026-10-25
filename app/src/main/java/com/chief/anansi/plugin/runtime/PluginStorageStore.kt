package com.chief.anansi.plugin.runtime

/**
 * Persistence seam for the per-plugin storage documents the JS host keeps
 * in its storage scopes. [com.chief.anansi.data.repo.RoomPluginStorageStore]
 * persists them across process death; [None] keeps the memory-only behavior
 * (runtime tests and any context without a database).
 */
interface PluginStorageStore {

    /** Returns the plugin's stored document JSON, or null when never saved. */
    suspend fun load(pluginId: String): String?

    /** Persists the plugin's whole document JSON, replacing any prior one. */
    suspend fun save(pluginId: String, document: String)

    /** Memory-only default: documents live in the runtime's cache only. */
    object None : PluginStorageStore {
        override suspend fun load(pluginId: String): String? = null
        override suspend fun save(pluginId: String, document: String) {}
    }
}
