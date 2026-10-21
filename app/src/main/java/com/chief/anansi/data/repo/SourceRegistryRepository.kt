package com.chief.anansi.data.repo

import androidx.room.withTransaction
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.dao.SourceDao
import com.chief.anansi.data.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Single authority for source identity: it mints the stable
 * `ECOSYSTEM_{sourceId}` identity string and the numeric id from the
 * ecosystem's reserved range, exactly once per source. No other code derives
 * or recomputes a source identity; UIs and other tables reference the
 * persisted values.
 *
 * The repo-registration methods (schema v2) extend the same minting path for
 * repository-driven registration: re-adding a repo updates its sources in
 * place (identity, numeric id, enabled, and pinned state are preserved), and
 * a repo never overwrites a source registered from a different repo.
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
        val identity = ecosystem.identity(sourceId)
        sourceDao.findByIdentity(identity)?.let { return it }
        return database.withTransaction {
            // Re-check inside the transaction: registration is serialized on
            // the single write connection, so the minted id is unique.
            sourceDao.findByIdentity(identity)?.let { return@withTransaction it }
            mintAndInsert(ecosystem, sourceId, identity, displayName)
        }
    }

    /**
     * Registers the repo-less placeholder row a v1 backup novel needs when
     * its plugin is not installed: disabled, no repository, no dist. The
     * novel's source foreign key is valid from the moment it lands, and a
     * later repository carrying the same plugin id claims the row through
     * the upsert path, which re-links the novel automatically.
     */
    suspend fun registerNeedsSource(
        ecosystem: SourceEcosystem,
        sourceId: String,
        displayName: String,
    ): SourceEntity {
        val identity = ecosystem.identity(sourceId)
        sourceDao.findByIdentity(identity)?.let { return it }
        return database.withTransaction {
            sourceDao.findByIdentity(identity)?.let { return@withTransaction it }
            mintAndInsert(ecosystem, sourceId, identity, displayName, enabled = false)
        }
    }

    /** One source row of a repository manifest, ready to register or update. */
    data class RepoSourceUpsert(
        val ecosystem: String,
        val sourceId: String,
        val displayName: String,
        val generation: String,
        val distUrl: String,
        val lang: String,
        val iconUrl: String,
    )

    data class RepoUpsertOutcome(
        val created: Int,
        val updated: Int,
        /** Plugin ids already registered from a different repository. */
        val conflicts: List<String>,
    )

    /**
     * Registers or updates the sources of one repository. Existing rows of
     * this repository are updated in place; rows carrying the same plugin id
     * from another repository are never overwritten (reported as conflicts).
     */
    suspend fun upsertRepoSources(
        repoUrl: String,
        sources: List<RepoSourceUpsert>,
    ): RepoUpsertOutcome {
        var created = 0
        var updated = 0
        val conflicts = ArrayList<String>()
        database.withTransaction {
            for (source in sources) {
                val ecosystem = ecosystemByKey(source.ecosystem)
                val identity = ecosystem.identity(source.sourceId)
                val existing = sourceDao.findByIdentity(identity)
                if (existing == null) {
                    mintAndInsert(
                        ecosystem,
                        source.sourceId,
                        identity,
                        source.displayName,
                        generation = source.generation,
                        repoUrl = repoUrl,
                        distUrl = source.distUrl,
                        lang = source.lang,
                        iconUrl = source.iconUrl,
                    )
                    created++
                } else if (existing.repoUrl.isEmpty()) {
                    // A repo-less placeholder row (a v1 backup import's
                    // needs-source source): the arriving repository claims
                    // it in place, which re-links the novels already stored
                    // under its identity.
                    sourceDao.update(
                        existing.copy(
                            displayName = source.displayName,
                            generation = source.generation,
                            repoUrl = repoUrl,
                            distUrl = source.distUrl,
                            lang = source.lang,
                            iconUrl = source.iconUrl,
                            enabled = true,
                        ),
                    )
                    updated++
                } else if (existing.repoUrl != repoUrl) {
                    conflicts += source.sourceId
                } else {
                    sourceDao.update(
                        existing.copy(
                            displayName = source.displayName,
                            generation = source.generation,
                            distUrl = source.distUrl,
                            lang = source.lang,
                            iconUrl = source.iconUrl,
                        ),
                    )
                    updated++
                }
            }
        }
        return RepoUpsertOutcome(created, updated, conflicts)
    }

    /**
     * Removes this repository's registered sources whose plugin id is absent
     * from [keepSourceIds]; returns the removed plugin ids.
     */
    suspend fun pruneRepoSources(
        repoUrl: String,
        keepSourceIds: Set<String>,
    ): List<String> = database.withTransaction {
        val stale = sourceDao.findByRepoUrl(repoUrl).filter { it.sourceId !in keepSourceIds }
        stale.forEach { sourceDao.delete(it) }
        stale.map { it.sourceId }
    }

    /** Removes every source registered from [repoUrl]; returns their plugin ids. */
    suspend fun removeRepo(repoUrl: String): List<String> = database.withTransaction {
        val rows = sourceDao.findByRepoUrl(repoUrl)
        rows.forEach { sourceDao.delete(it) }
        rows.map { it.sourceId }
    }

    suspend fun removeSource(identity: String) {
        sourceDao.findByIdentity(identity)?.let { sourceDao.delete(it) }
    }

    suspend fun setPinned(identity: String, pinned: Boolean) {
        sourceDao.setPinned(identity, pinned)
    }

    suspend fun setEnabled(identity: String, enabled: Boolean) {
        sourceDao.setEnabled(identity, enabled)
    }

    suspend fun findByIdentity(identity: String): SourceEntity? =
        sourceDao.findByIdentity(identity)

    fun observeByIdentity(identity: String): Flow<SourceEntity?> =
        sourceDao.observeByIdentity(identity)

    fun observeAll(): Flow<List<SourceEntity>> = sourceDao.observeAll()

    private suspend fun mintAndInsert(
        ecosystem: SourceEcosystem,
        sourceId: String,
        identity: String,
        displayName: String,
        generation: String = "",
        enabled: Boolean = true,
        repoUrl: String = "",
        distUrl: String = "",
        lang: String = "",
        iconUrl: String = "",
    ): SourceEntity {
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
            generation = generation,
            enabled = enabled,
            repoUrl = repoUrl,
            distUrl = distUrl,
            lang = lang,
            iconUrl = iconUrl,
        )
        val id = sourceDao.insert(source)
        return source.copy(id = id)
    }

    private fun ecosystemByKey(key: String): SourceEcosystem =
        SourceEcosystem.entries.firstOrNull { it.key == key }
            ?: throw IllegalArgumentException("Unknown source ecosystem \"$key\"")
}
