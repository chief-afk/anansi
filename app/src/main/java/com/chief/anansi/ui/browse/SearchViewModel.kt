package com.chief.anansi.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.SourceNovelItem
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val searching: Boolean = false,
    val hasSearched: Boolean = false,
)

/**
 * Global search: one query fanned out across every enabled
 * source in parallel, with explicit per-source groups so a dead or picky
 * source is visible instead of silently missing. A non-empty scoped
 * identity restricts the fan-out to that one source (the catalog screen's
 * appbar lens). Failure groups render the registry's SourceError and retry
 * re-runs that source alone.
 */
class SearchViewModel(
    private val sourceRegistry: SourceRegistryRepository,
    private val sessions: SourceSessions,
    scopedIdentity: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val pendingGroups = AtomicInteger(0)

    init {
        viewModelScope.launch {
            val all = sourceRegistry.observeAll().first()
            val targets = if (scopedIdentity == null) {
                all.filter { it.enabled }
            } else {
                all.filter { it.identity == scopedIdentity }
            }
            _state.update {
                it.copy(
                    scopedSourceName = targets.singleOrNull()?.displayName.orEmpty(),
                    groups = targets.map { entity ->
                        SearchGroupUi(identity = entity.identity, sourceName = entity.displayName)
                    },
                )
            }
        }
    }

    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        val groups = _state.value.groups
        if (groups.isEmpty()) return
        _state.update { current ->
            current.copy(
                query = query,
                hasSearched = true,
                groups = groups.map { group ->
                    group.copy(
                        status = SearchGroupStatus.Searching,
                        novels = emptyList(),
                        failureKind = "",
                        failureReason = "",
                    )
                },
            )
        }
        groups.forEach { group ->
            viewModelScope.launch { runGroup(group.identity, query) }
        }
    }

    fun retryGroup(groupIdentity: String) {
        val query = _state.value.query
        if (query.isEmpty()) return
        updateGroup(groupIdentity) { it.copy(status = SearchGroupStatus.Searching) }
        viewModelScope.launch { runGroup(groupIdentity, query) }
    }

    fun clearQuery() {
        _state.update {
            it.copy(
                query = "",
                hasSearched = false,
                searching = false,
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
        pendingGroups.set(0)
    }

    private suspend fun runGroup(groupIdentity: String, query: String) {
        pendingGroups.incrementAndGet()
        _state.update { it.copy(searching = true) }
        try {
            val entity = sourceRegistry.findByIdentity(groupIdentity)
                ?: throw IllegalStateException("Source \"$groupIdentity\" is no longer registered")
            val source = sessions.load(entity)
            val items = source.searchNovels(query, page = 1, filterValuesJson = null)
            updateGroup(groupIdentity) {
                it.copy(
                    status = SearchGroupStatus.Done,
                    // Duplicate paths would collide in the LazyColumn key.
                    novels = items.distinctBy { it.path },
                    coverHeaders = coverHeaders(source),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateGroup(groupIdentity) {
                it.copy(
                    status = SearchGroupStatus.Failed,
                    failureKind = failureKindOf(e),
                    failureReason = e.message ?: "unknown failure",
                )
            }
        } finally {
            if (pendingGroups.decrementAndGet() == 0) {
                _state.update { it.copy(searching = false) }
            }
        }
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
                    sourceRegistry = container.sourceRegistryRepository,
                    sessions = container.sourceSessions,
                    scopedIdentity = scopedIdentity,
                )
            }
        }
    }
}
