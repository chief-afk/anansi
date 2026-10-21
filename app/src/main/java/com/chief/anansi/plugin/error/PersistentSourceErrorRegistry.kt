package com.chief.anansi.plugin.error

import com.chief.anansi.data.dao.SourceErrorDao
import com.chief.anansi.data.entity.SourceErrorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The [SourceErrorRegistry] implementation with persistence: reports stay
 * in memory for the adapters' synchronous interface and are mirrored to the
 * `source_errors` table (last error per source survives restarts). Reads
 * for the management screen observe the same table, so the registry stays
 * the single error source of truth.
 *
 * [preload] seeds the memory map from disk at startup; rows already in
 * memory (a report racing the seed) are never overwritten.
 */
class PersistentSourceErrorRegistry(
    private val store: SourceErrorDao,
    private val scope: CoroutineScope,
) : SourceErrorRegistry {

    private val latestBySource = HashMap<String, SourceError>()
    private val writeMutex = Mutex()

    fun preload() {
        scope.launch {
            val rows = store.all()
            synchronized(this@PersistentSourceErrorRegistry) {
                rows.forEach { row ->
                    latestBySource.putIfAbsent(row.sourceId, row.toError())
                }
            }
        }
    }

    @Synchronized
    override fun report(sourceId: String, error: SourceError) {
        latestBySource[sourceId] = error
        val row = error.toEntity(sourceId)
        scope.launch {
            writeMutex.withLock { store.upsert(row) }
        }
    }

    @Synchronized
    override fun latest(sourceId: String): SourceError? = latestBySource[sourceId]

    @Synchronized
    override fun clear(sourceId: String) {
        if (latestBySource.remove(sourceId) == null) return
        scope.launch {
            writeMutex.withLock { store.delete(sourceId) }
        }
    }

    private fun SourceError.toEntity(sourceId: String) = SourceErrorEntity(
        sourceId = sourceId,
        kind = this::class.simpleName.orEmpty(),
        reason = reason,
        updatedAt = System.currentTimeMillis(),
    )

    private fun SourceErrorEntity.toError(): SourceError = when (kind) {
        "Network" -> SourceError.Network(reason)
        "Incompatible" -> SourceError.Incompatible(reason)
        "RequiresJs" -> SourceError.RequiresJs(reason)
        "UnsupportedFeature" -> SourceError.UnsupportedFeature(reason)
        else -> SourceError.Parse(reason)
    }
}
