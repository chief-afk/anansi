package com.chief.anansi.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.dao.SourceErrorDao
import com.chief.anansi.data.repo.SourceEcosystem
import com.chief.anansi.data.repo.SourceRegistryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A recorded error line for the management surfaces; display-only. */
data class SourceErrorDisplay(val kind: String, val reason: String, val updatedAt: Long)

data class BrowseSourceRow(
    val identity: String,
    val name: String,
    val lang: String,
    val ecosystem: String,
    val generation: String,
    val iconUrl: String,
    val pinned: Boolean,
    val enabled: Boolean,
    val error: SourceErrorDisplay?,
)

data class BrowseUiState(
    val loading: Boolean = true,
    val sources: List<BrowseSourceRow> = emptyList(),
    val repositoryCount: Int = 0,
)

class BrowseViewModel(
    private val sourceRegistry: SourceRegistryRepository,
    errorDao: SourceErrorDao,
) : ViewModel() {

    val state: StateFlow<BrowseUiState> = combine(
        sourceRegistry.observeAll(),
        errorDao.observeAll(),
    ) { sources, errors ->
        val errorBySourceId = errors.associate {
            it.sourceId to SourceErrorDisplay(it.kind, it.reason, it.updatedAt)
        }
        // The fixed Local pseudo-source has its own fixed entry above the
        // list; its registered row would only duplicate that entry here.
        val listed = sources.filter { it.ecosystem != SourceEcosystem.LOCAL.key }
        BrowseUiState(
            loading = false,
            sources = listed.map { source ->
                BrowseSourceRow(
                    identity = source.identity,
                    name = source.displayName,
                    lang = source.lang,
                    ecosystem = source.ecosystem,
                    generation = source.generation,
                    iconUrl = source.iconUrl,
                    pinned = source.pinned,
                    enabled = source.enabled,
                    error = errorBySourceId[source.sourceId],
                )
            },
            repositoryCount = listed.map { it.repoUrl }.filter { it.isNotEmpty() }.distinct().size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun togglePin(row: BrowseSourceRow) {
        viewModelScope.launch { sourceRegistry.setPinned(row.identity, !row.pinned) }
    }

    fun setEnabled(row: BrowseSourceRow, enabled: Boolean) {
        viewModelScope.launch { sourceRegistry.setEnabled(row.identity, enabled) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as AnansiApplication).container
                BrowseViewModel(
                    sourceRegistry = container.sourceRegistryRepository,
                    errorDao = container.database.sourceErrorDao(),
                )
            }
        }
    }
}
