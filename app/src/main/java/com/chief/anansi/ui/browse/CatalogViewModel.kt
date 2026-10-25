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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * One catalog page's fetch result, ready to write into state. Public only
 * because it rides the public [CatalogPages] seam's signature; nothing
 * outside this package and its tests should touch it.
 */
data class CatalogPage(
    val filters: List<CatalogFilter>,
    val coverHeaders: Map<String, String>,
    val items: List<SourceNovelItem>,
    val page: Int,
    val endReached: Boolean,
)

/**
 * The source IO behind the catalog's single-flight gate: one reset-or-append
 * page load that throws on failure. The production implementation wraps
 * `sessions.load` + `popularNovels` + `parseFilterSchema`; tests gate fakes.
 */
fun interface CatalogPages {
    suspend fun load(
        entity: SourceEntity,
        reset: Boolean,
        showLatest: Boolean,
        filterValues: FilterValues,
    ): CatalogPage
}

/** The production [CatalogPages]; the page counter advances only on success. */
internal class SourceCatalogPages(private val sessions: SourceSessions) : CatalogPages {
    private var lastPage = 0

    override suspend fun load(
        entity: SourceEntity,
        reset: Boolean,
        showLatest: Boolean,
        filterValues: FilterValues,
    ): CatalogPage {
        val source = sessions.load(entity)
        val page = if (reset) 1 else lastPage + 1
        val items = source.popularNovels(
            page = page,
            showLatestNovels = showLatest,
            filterValuesJson = filterValuesJson(filterValues),
        )
        lastPage = page
        return CatalogPage(
            filters = parseFilterSchema(source.filters),
            coverHeaders = coverHeaders(source),
            items = items,
            page = page,
            endReached = items.isEmpty(),
        )
    }
}

/**
 * One source's catalog: Popular/Latest listings, the source's
 * own filter schema in a sheet, and explicit load-more. Loads are
 * single-flight: a load-more while a page fetch is running is ignored, and a
 * reset (listing switch, filter apply) replaces the in-flight fetch so the
 * old page's outcome never lands into the new listing. Every call loads
 * the plugin fresh through [SourceSessions]; the error box renders the
 * [com.chief.anansi.plugin.error.SourceError] the adapter boundary produced
 * and reported into the persistent registry, never a local reclassification
 *(the shared error taxonomy).
 */
class CatalogViewModel(
    private val identity: String,
    private val findEntity: suspend (String) -> SourceEntity?,
    private val pages: CatalogPages,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val jobScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state

    private var entity: SourceEntity? = null

    /** The listing reset state of the failed call, so retry repeats it. */
    private var failedReset: Boolean = true

    private var loadJob: Job? = null

    init {
        jobScope.launch {
            val loaded = findEntity(identity)
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
        val inFlight = loadJob
        if (inFlight != null && inFlight.isActive) {
            if (!reset) return
            inFlight.cancel()
        }
        val sourceEntity = entity ?: return
        loadJob = jobScope.launch {
            _state.update {
                it.copy(loading = reset, loadingMore = !reset, failed = false)
            }
            try {
                val result = pages.load(
                    entity = sourceEntity,
                    reset = reset,
                    showLatest = _state.value.showLatest,
                    filterValues = _state.value.appliedFilterValues,
                )
                _state.update { current ->
                    current.copy(
                        filters = if (reset) result.filters else current.filters,
                        coverHeaders = if (reset) result.coverHeaders else current.coverHeaders,
                        novels = if (reset) {
                            result.items
                        } else {
                            (current.novels + result.items).distinctBy { it.path }
                        },
                        page = result.page,
                        endReached = result.endReached,
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
                    identity = identity,
                    findEntity = { container.sourceRegistryRepository.findByIdentity(it) },
                    pages = SourceCatalogPages(container.sourceSessions),
                )
            }
        }
    }
}
