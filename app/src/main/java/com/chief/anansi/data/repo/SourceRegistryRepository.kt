package com.chief.anansi.data.repo

import androidx.room.withTransaction
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.dao.SourceDao
import com.chief.anansi.data.entity.SourceEntity

/**
 * Single authority for source identity: it mints the stable
 * `ECOSYSTEM_{sourceId}` identity string and the numeric id from the
 * ecosystem's reserved range, exactly once per source. No other code derives
 * or recomputes a source identity; UIs and other tables reference the
 * persisted values.
 */
class SourceRegistryRepository(
    private val database: AnansiDatabase,
) {
    private val sourceDao: SourceDao = database.sourceDao()

    suspend fun registerSource(
        ecosystem: SourceEcosystem,
        sourceId: String,
        displayName: String,
    ): SourceEntity {
        val identity = "${ecosystem.key}_$sourceId"
        sourceDao.findByIdentity(identity)?.let { return it }
        return database.withTransaction {
            // Re-check inside the transaction: registration is serialized on
            // the single write connection, so the minted id is unique.
            sourceDao.findByIdentity(identity)?.let { return@withTransaction it }
            val nextNumericId = (sourceDao.maxNumericIdInRange(
                ecosystem.numericIdMin,
                ecosystem.numericIdMax,
            ) ?: (ecosystem.numericIdMin - 1)) + 1
            require(nextNumericId <= ecosystem.numericIdMax) {
                "Numeric id range exhausted for ecosystem ${ecosystem.key}"
            }
            val source = SourceEntity(
                ecosystem = ecosystem.key,
                sourceId = sourceId,
                identity = identity,
                numericId = nextNumericId,
                displayName = displayName,
            )
            val id = sourceDao.insert(source)
            source.copy(id = id)
        }
    }

    suspend fun findByIdentity(identity: String): SourceEntity? =
        sourceDao.findByIdentity(identity)
}
