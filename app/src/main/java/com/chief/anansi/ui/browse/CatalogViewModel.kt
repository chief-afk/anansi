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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogUiState(
    val sourceName: String = "",
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val showLatest: Boolean = false,
    val novels: List<SourceNovelItem> = emptyList(),
    val coverHeaders: Map<String, String> = emptyMap(),
    val page: Int = 1,
    val endReached: Boolean = false,
    val filters: List<CatalogFilter> = emptyList(),
    val appliedFilterValues: FilterValues = emptyMap(),
    val failed: Boolean = false,
    val failureKind: String = "",
    val failureReason: String = "",
    val sourceMissing: Boolean = false,
)

/**
 * One source's catalog: Popular/Latest listings, the source's
 * own filter schema in a sheet, and explicit load-more. Every call loads
 * the plugin fresh through [SourceSessions]; the error box renders the
 * [com.chief.anansi.plugin.error.SourceError] the adapter boundary produced
 * and reported into the persistent registry, never a local reclassification
 *(the shared error taxonomy).
 */
class CatalogViewModel(
    private val sourceRegistry: SourceRegistryRepository,
    private val sessions: SourceSessions,
    private val identity: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state

    private var entity: com.chief.anansi.data.entity.SourceEntity? = null

    /** The listing reset state of the failed call, so retry repeats it. */
    private var failedReset: Boolean = true

    init {
        viewModelScope.launch {
            val loaded = sourceRegistry.findByIdentity(identity)
            if (loaded == null) {
                _state.update { it.copy(sourceMissing = true, loading = false) }
                return@launch
            }
            entity = loaded
            _state.update { it.copy(sourceName = loaded.displayName) }
            load(reset = true)
        }
    }

    fun showLatest(showLatest: Boolean) {
        if (_state.value.showLatest == showLatest) return
        _state.update { it.copy(showLatest = showLatest) }
        load(reset = true)
    }

    fun loadMore() = load(reset = false)

    fun applyFilters(values: FilterValues) {
        _state.update { it.copy(appliedFilterValues = values) }
        load(reset = true)
    }

    fun retry() = load(reset = failedReset)

    private fun load(reset: Boolean) {
        val sourceEntity = entity ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(loading = reset, loadingMore = !reset, failed = false)
            }
            try {
                val source = sessions.load(sourceEntity)
                if (reset) {
                    _state.update {
                        it.copy(
                            filters = parseFilterSchema(source.filters),
                            coverHeaders = coverHeaders(source),
                        )
                    }
                }
                val page = if (reset) 1 else _state.value.page + 1
                val items = source.popularNovels(
                    page = page,
                    showLatestNovels = _state.value.showLatest,
                    filterValuesJson = filterValuesJson(_state.value.appliedFilterValues),
                )
                _state.update { current ->
                    current.copy(
                        novels = if (reset) items else (current.novels + items).distinctBy { it.path },
                        page = page,
                        endReached = items.isEmpty(),
                        loading = false,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedReset = reset
                _state.update { current ->
                    current.copy(
                        loading = false,
                        loadingMore = false,
                        failed = true,
                        failureKind = failureKindOf(e),
                        failureReason = e.message ?: "unknown failure",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(identity: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as AnansiApplication).container
                CatalogViewModel(
                    sourceRegistry = container.sourceRegistryRepository,
                    sessions = container.sourceSessions,
                    identity = identity,
                )
            }
        }
    }
}
