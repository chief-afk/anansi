package com.chief.anansi.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.plugin.SourceNovelItem
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.runtime.SourceRuntimeLimits
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

enum class SearchGroupStatus { Pending, Searching, Done, Failed }

data class SearchGroupUi(
    val identity: String,
    val sourceName: String,
    val status: SearchGroupStatus = SearchGroupStatus.Pending,
    val novels: List<SourceNovelItem> = emptyList(),
    val coverHeaders: Map<String, String> = emptyMap(),
    val failureKind: String = "",
    val failureReason: String = "",
)

data class SearchUiState(
    val query: String = "",
    val scopedSourceName: String = "",
    val groups: List<SearchGroupUi> = emptyList(),
    val hasSearched: Boolean = false,
)

/** One source's page-1 search answer: items plus the cover headers they load with. */
data class SearchPageResult(
    val items: List<SourceNovelItem>,
    val coverHeaders: Map<String, String> = emptyMap(),
)

/**
 * Global search: one query fanned out across every enabled
 * source with explicit per-source groups so a dead or picky
 * source is visible instead of silently missing. A non-empty scoped
 * identity restricts the fan-out to that one source (the catalog screen's
 * appbar lens). Failure groups render the registry's SourceError and retry
 * re-runs that source alone.
 *
 * Scheduling: every search re-reads the enabled source set, so sources
 * enabled after the screen opened are searched too. The fan-out runs as
 * children of one job under a [Semaphore] bound; each group carries a
 * wall-clock timeout surfaced as a retryable failure. State writes carry
 * the search's generation, so a stale group can never overwrite a newer
 * search's results and a mid-search clear cannot leave the progress
 * spinner stuck.
 */
class SearchViewModel(
    private val scopedIdentity: String?,
    private val runSourceSearch: suspend (SourceEntity, String) -> SearchPageResult,
    private val targets: suspend (String?) -> List<SourceEntity>,
    scope: CoroutineScope? = null,
    private val searchTimeoutMillis: Long = SourceRuntimeLimits.SEARCH_SOURCE_TIMEOUT_MILLIS,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val fanOutScope: CoroutineScope = scope ?: viewModelScope

    /** Increments on every search and clear; stamped state writes die with theirs. */
    private var generation = 0L
    private var searchJob: Job? = null
    private val sourceLimiter = Semaphore(SourceRuntimeLimits.SEARCH_CONCURRENCY)

    init {
        // The pre-search snapshot only picks the empty-state body and the
        // header title; every search re-snapshots instead of trusting it.
        fanOutScope.launch {
            val found = targets(scopedIdentity)
            if (generation != 0L) return@launch
            _state.update {
                it.copy(
                    scopedSourceName = found.singleOrNull()?.displayName.orEmpty(),
                    groups = found.map { entity ->
                        SearchGroupUi(identity = entity.identity, sourceName = entity.displayName)
                    },
                )
            }
        }
    }

    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        val currentGeneration = ++generation
        searchJob = fanOutScope.launch {
            val found = targets(scopedIdentity)
            if (currentGeneration != generation) return@launch
            if (found.isEmpty()) return@launch
            _state.update { current ->
                current.copy(
                    query = query,
                    hasSearched = true,
                    scopedSourceName = found.singleOrNull()?.displayName.orEmpty(),
                    groups = found.map { entity ->
                        SearchGroupUi(
                            identity = entity.identity,
                            sourceName = entity.displayName,
                            status = SearchGroupStatus.Searching,
                        )
                    },
                )
            }
            found.forEach { entity ->
                launch { runGroup(entity, query, currentGeneration) }
            }
        }
    }

    fun retryGroup(groupIdentity: String) {
        val query = _state.value.query
        if (query.isEmpty()) return
        val currentGeneration = generation
        updateGroup(groupIdentity) { it.copy(status = SearchGroupStatus.Searching) }
        launchGroupWork {
            val entity = targets(scopedIdentity).find { it.identity == groupIdentity }
            if (currentGeneration != generation) return@launchGroupWork
            if (entity == null) {
                val missing =
                    IllegalStateException("Source \"$groupIdentity\" is no longer registered")
                updateGroup(groupIdentity) {
                    it.copy(
                        status = SearchGroupStatus.Failed,
                        failureKind = failureKindOf(missing),
                        failureReason = missing.message ?: "unknown failure",
                    )
                }
                return@launchGroupWork
            }
            runGroup(entity, query, currentGeneration)
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        searchJob = null
        generation++
        _state.update {
            it.copy(
                query = "",
                hasSearched = false,
                groups = it.groups.map { group ->
                    group.copy(
                        status = SearchGroupStatus.Pending,
                        novels = emptyList(),
                        failureKind = "",
                        failureReason = "",
                    )
                },
            )
        }
    }

    private suspend fun runGroup(entity: SourceEntity, query: String, currentGeneration: Long) {
        try {
            val result = sourceLimiter.withPermit {
                withTimeout(searchTimeoutMillis) {
                    runSourceSearch(entity, query)
                }
            }
            if (currentGeneration != generation) return
            updateGroup(entity.identity) {
                it.copy(
                    status = SearchGroupStatus.Done,
                    // Duplicate paths would collide in the LazyColumn key.
                    novels = result.items.distinctBy { it.path },
                    coverHeaders = result.coverHeaders,
                )
            }
        } catch (e: CancellationException) {
            // An outer search-job cancellation owns this failure; only a
            // still-active group missed its own per-source wall-clock deadline.
            coroutineContext.ensureActive()
            if (currentGeneration != generation) return
            val timeout = timeoutFailure(entity)
            updateGroup(entity.identity) {
                it.copy(
                    status = SearchGroupStatus.Failed,
                    failureKind = failureKindOf(timeout),
                    failureReason = timeout.message ?: "unknown failure",
                )
            }
        } catch (e: Exception) {
            if (currentGeneration != generation) return
            updateGroup(entity.identity) {
                it.copy(
                    status = SearchGroupStatus.Failed,
                    failureKind = failureKindOf(e),
                    failureReason = e.message ?: "unknown failure",
                )
            }
        }
    }

    /** The retryable failure a per-source wall-clock timeout lands as. */
    private fun timeoutFailure(entity: SourceEntity) = SourcePluginException(
        SourceError.Network(
            "${entity.displayName} did not answer within " +
                "${SourceRuntimeLimits.SEARCH_SOURCE_TIMEOUT_MILLIS / 1000} s",
        ),
    )

    /**
     * Runs work as a child of the live fan-out job, so a later search's
     * cancel reaches it; retries of a settled search get a fresh carrier.
     */
    private fun launchGroupWork(block: suspend CoroutineScope.() -> Unit) {
        val live = searchJob
        val parent = if (live != null && live.isActive) {
            live
        } else {
            fanOutScope.launch { }.also { searchJob = it }
        }
        CoroutineScope(fanOutScope.coroutineContext + parent).launch(block = block)
    }

    private fun updateGroup(groupIdentity: String, change: (SearchGroupUi) -> SearchGroupUi) {
        _state.update { current ->
            current.copy(
                groups = current.groups.map { group ->
                    if (group.identity == groupIdentity) change(group) else group
                },
            )
        }
    }

    companion object {
        fun factory(scopedIdentity: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as AnansiApplication).container
                SearchViewModel(
                    scopedIdentity = scopedIdentity,
                    runSourceSearch = { entity, query ->
                        val source = container.sourceSessions.load(entity)
                        SearchPageResult(
                            items = source.searchNovels(query, page = 1, filterValuesJson = null),
                            coverHeaders = coverHeaders(source),
                        )
                    },
                    targets = { scoped ->
                        val all = container.sourceRegistryRepository.observeAll().first()
                        if (scoped == null) {
                            all.filter { it.enabled }
                        } else {
                            all.filter { it.identity == scoped }
                        }
                    },
                )
            }
        }
    }
}
