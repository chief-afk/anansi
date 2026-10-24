package com.chief.anansi.plugin.repo

import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.JsSourceRuntime
import com.chief.anansi.plugin.PluginIds
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient

/**
 * The add-repo flow: validate the URL, fetch the manifest, normalize it,
 * probe foreign entries (declared-generation entries skip the probe),
 * register or update the sources through the identity minting path, and
 * prune entries the manifest no longer lists. Per-entry failures are
 * reported into the error registry and returned with their reasons; a repo
 * whose entries all fail is a hard error. Prune and removal convert a
 * source row to the repo-less needs-source placeholder state instead of
 * deleting it, so the novels shelved under it stay in the library; the
 * plugin ids' recorded errors are cleared either way.
 */
class RepoRegistrationService(
    client: OkHttpClient,
    runtime: JsSourceRuntime,
    private val errorRegistry: SourceErrorRegistry,
    private val sourceRegistry: SourceRegistryRepository,
) {
    private val http = RepoHttp(client)
    private val probe = GenerationProbe(runtime)

    /** One entry that failed validation or probing, with its visible reason. */
    data class EntryFailure(val pluginId: String, val reason: String)

    data class RepoAddReport(
        val repoUrl: String,
        val created: Int,
        val updated: Int,
        val removed: Int,
        val failures: List<EntryFailure>,
        val generationCounts: Map<String, Int>,
        val apiVersion: String?,
    )

    suspend fun addOrUpdateRepo(rawUrl: String): RepoAddReport {
        val repoUrl = RepoUrls.validate(rawUrl)
        val manifestText = http.getText(repoUrl, "Repository manifest")
        val normalized = RepoManifests.normalize(manifestText, repoUrl)
        if (normalized.entries.isEmpty()) {
            throw manifestFailure(
                normalized.entryErrors.firstOrNull()?.reason
                    ?: "Repository manifest lists no plugins",
            )
        }

        // Dist fetches run concurrently (bounded); the engine serializes the
        // probe evaluations behind its own dispatcher.
        val outcomes = coroutineScope {
            val gate = Semaphore(DIST_FETCH_CONCURRENCY)
            normalized.entries.map { entry ->
                async { gate.withPermit { prepareEntry(entry) } }
            }.awaitAll()
        }
        // Retained malformed/unsupported entries from the normalizer report
        // beside the probe failures, so nothing silently disappears.
        val manifestEntryFailures = normalized.entryErrors.map { error ->
            val pluginId = error.pluginId ?: "entry ${error.index + 1}"
            error.pluginId?.let { errorRegistry.report(it, SourceError.Parse(error.reason)) }
            EntryFailure(pluginId, error.reason)
        }
        val failures = manifestEntryFailures + outcomes.mapNotNull { it.failure }
        val ready = outcomes.mapNotNull { it.prepared }

        val outcome = sourceRegistry.upsertRepoSources(
            repoUrl = repoUrl,
            sources = ready.map {
                SourceRegistryRepository.RepoSourceUpsert(
                    ecosystem = it.entry.ecosystem,
                    sourceId = it.entry.pluginId,
                    displayName = it.entry.name,
                    generation = it.generation,
                    distUrl = it.entry.distUrl,
                    lang = it.entry.lang,
                    iconUrl = it.entry.iconUrl,
                )
            },
        )
        outcome.conflicts.forEach { pluginId ->
            errorRegistry.report(
                pluginId,
                SourceError.Incompatible(
                    "Plugin \"$pluginId\" is already registered from another repository; " +
                        "a repository never overwrites another repository's source",
                ),
            )
        }

        // Sources of this repo the manifest no longer lists convert to the
        // needs-source placeholder state (their novels stay); entries still
        // listed stay registered even when their probe failed this time
        // (the previously registered version keeps working, with the failure
        // recorded in the error registry).
        val keepSourceIds = normalized.entries
            .map { it.pluginId }
            .filter { PluginIds.isValid(it) }
            .toSet()
        val removed = sourceRegistry.pruneRepoSources(repoUrl, keepSourceIds)
        removed.forEach { errorRegistry.clear(it) }

        if (outcome.created + outcome.updated == 0) {
            throw manifestFailure(
                "Repository registered no sources: ${failures.size} of " +
                    "${normalized.entries.size} entries failed. ${failures.firstOrNull()?.reason ?: ""}",
            )
        }

        val generationCounts = ready.groupingBy { it.generation }.eachCount()
        return RepoAddReport(
            repoUrl = repoUrl,
            created = outcome.created,
            updated = outcome.updated,
            removed = removed.size,
            failures = failures + outcome.conflicts.map { conflictFailures(it) },
            generationCounts = generationCounts,
            apiVersion = normalized.apiVersion,
        )
    }

    /**
     * Removes the repository: its sources convert to the needs-source
     * placeholder state (their novels stay) and their errors are cleared.
     */
    suspend fun removeRepo(repoUrl: String) {
        val removed = sourceRegistry.removeRepo(repoUrl)
        removed.forEach { errorRegistry.clear(it) }
    }

    /** Converts a single source to the needs-source placeholder and clears its recorded error. */
    suspend fun removeSource(identity: String) {
        val existing = sourceRegistry.findByIdentity(identity)
        sourceRegistry.removeSource(identity)
        existing?.let { errorRegistry.clear(it.sourceId) }
    }

    /** One manifest entry that passed validation and probing, ready to register. */
    private data class PreparedEntry(val entry: RepoEntry, val generation: String)

    private data class EntryOutcome(
        val prepared: PreparedEntry?,
        val failure: EntryFailure?,
    ) {
        companion object {
            fun ready(entry: RepoEntry, generation: String) =
                EntryOutcome(PreparedEntry(entry, generation), null)

            fun failed(entry: RepoEntry, reason: String) =
                EntryOutcome(null, EntryFailure(entry.pluginId, reason))
        }
    }

    private suspend fun prepareEntry(entry: RepoEntry): EntryOutcome {
        val pluginId = try {
            PluginIds.validate(entry.pluginId)
        } catch (e: SourcePluginException) {
            return EntryOutcome.failed(entry, e.error.reason)
        }
        val generation = entry.declaredGeneration
        if (generation != null) {
            return EntryOutcome.ready(entry, generation)
        }
        val dist = try {
            http.getText(entry.distUrl, "Plugin \"$pluginId\" dist")
        } catch (e: SourcePluginException) {
            errorRegistry.report(pluginId, e.error)
            return EntryOutcome.failed(entry, e.error.reason)
        }
        return try {
            EntryOutcome.ready(entry, probe.probe(pluginId, dist))
        } catch (e: SourcePluginException) {
            errorRegistry.report(pluginId, e.error)
            EntryOutcome.failed(entry, e.error.reason)
        }
    }

    private fun conflictFailures(pluginId: String): EntryFailure =
        EntryFailure(
            pluginId,
            "Plugin \"$pluginId\" is already registered from another repository; " +
                "a repository never overwrites another repository's source",
        )

    private fun manifestFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Parse(reason))

    private companion object {
        const val DIST_FETCH_CONCURRENCY = 8
    }
}
