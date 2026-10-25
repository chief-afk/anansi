package com.chief.anansi.plugin.error

import com.chief.anansi.data.dao.SourceErrorDao
import com.chief.anansi.data.entity.SourceErrorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The [SourceErrorRegistry] implementation with persistence: reports stay
 * in memory for the adapters' synchronous interface and are mirrored to the
 * `source_errors` table (last error per source survives restarts). Reads
 * for the management screen observe the same table, so the registry stays
 * the single error source of truth.
 *
 * [preload] seeds the memory map from disk at startup; rows already in
 * memory (a report racing the seed) are never overwritten, and rows older
 * than the staleness window are dropped from memory and the table so a
 * recorded error never outlives its usefulness.
 */
class PersistentSourceErrorRegistry(
    private val store: SourceErrorDao,
    private val scope: CoroutineScope,
) : SourceErrorRegistry {

    private val latestBySource = HashMap<String, SourceError>()

    /**
     * The single persisted-write lane: [report] and [clear] enqueue while
     * holding the memory lock, one consumer coroutine applies them in
     * enqueue order, so persistence order equals memory order and a clear
     * can never reach disk after the report that replaced it.
     */
    private val writes = Channel<Write>(Channel.UNLIMITED)

    private sealed interface Write {
        data class Upsert(val row: SourceErrorEntity) : Write
        data class Delete(val sourceId: String) : Write
    }

    init {
        scope.launch {
            for (write in writes) {
                when (write) {
                    is Write.Upsert -> store.upsert(write.row)
                    is Write.Delete -> store.delete(write.sourceId)
                }
            }
        }
    }

    fun preload() {
        scope.launch {
            val rows = store.all()
            val now = System.currentTimeMillis()
            val (fresh, stale) = rows.partition { !isStaleErrorRow(it.updatedAt, now) }
            if (stale.isNotEmpty()) {
                store.deleteAll(stale.map { it.sourceId })
            }
            synchronized(this@PersistentSourceErrorRegistry) {
                fresh.forEach { row ->
                    latestBySource.putIfAbsent(row.sourceId, row.toError())
                }
            }
        }
    }

    @Synchronized
    override fun report(sourceId: String, error: SourceError) {
        latestBySource[sourceId] = error
        writes.trySend(Write.Upsert(error.toEntity(sourceId)))
    }

    @Synchronized
    override fun latest(sourceId: String): SourceError? = latestBySource[sourceId]

    @Synchronized
    override fun clear(sourceId: String) {
        if (latestBySource.remove(sourceId) == null) return
        writes.trySend(Write.Delete(sourceId))
    }

    private fun SourceError.toEntity(sourceId: String) = SourceErrorEntity(
        sourceId = sourceId,
        kind = kind.name,
        reason = reason,
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * A recorded row older than the staleness window dies at preload: error
 * rows are diagnostic state, not history, and a months-old failure says
 * nothing about the source's current health.
 */
internal fun isStaleErrorRow(updatedAt: Long, now: Long): Boolean =
    now - updatedAt > STALENESS_WINDOW_MILLIS

private const val STALENESS_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000

/**
 * The stored-kind round trip: an unknown stored kind (a future install
 * downgraded, a corrupted row) falls back to Parse instead of failing the
 * load, and the mapping `when` is exhaustive over [SourceErrorKind], so the
 * compiler pins every variant's stored representation.
 */
internal fun SourceErrorEntity.toError(): SourceError =
    when (runCatching { SourceErrorKind.valueOf(kind) }.getOrDefault(SourceErrorKind.Parse)) {
        SourceErrorKind.Network -> SourceError.Network(reason)
        SourceErrorKind.Parse -> SourceError.Parse(reason)
        SourceErrorKind.Incompatible -> SourceError.Incompatible(reason)
        SourceErrorKind.RequiresJs -> SourceError.RequiresJs(reason)
        SourceErrorKind.UnsupportedFeature -> SourceError.UnsupportedFeature(reason)
        SourceErrorKind.SourceMissing -> SourceError.SourceMissing(reason)
        SourceErrorKind.Blocked -> SourceError.Blocked(reason)
    }
