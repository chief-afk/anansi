package com.chief.anansi.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.dao.SourceErrorDao
import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.repo.RepoRegistrationService
import com.chief.anansi.plugin.repo.SourcePermission
import com.chief.anansi.plugin.repo.SourcePermissions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SourceManageUiState(
    val source: SourceEntity? = null,
    val error: SourceErrorDisplay? = null,
    val permissions: List<SourcePermission> = emptyList(),
)

/**
 * One source's management surface: the registered row, its last recorded
 * error (display-only, from the persistent registry), and the permission
 * declarations the plugin layer resolves.
 */
class SourceManageViewModel(
    private val sourceRegistry: SourceRegistryRepository,
    private val service: RepoRegistrationService,
    errorDao: SourceErrorDao,
    identity: String,
) : ViewModel() {

    val state: StateFlow<SourceManageUiState> = sourceRegistry.observeByIdentity(identity)
        .flatMapLatest { entity ->
            if (entity == null) {
                flowOf(SourceManageUiState())
            } else {
                errorDao.observe(entity.sourceId).map { recorded ->
                    SourceManageUiState(
                        source = entity,
                        error = recorded?.let { SourceErrorDisplay(it.kind, it.reason) },
                        permissions = SourcePermissions.resolve(entity.ecosystem),
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceManageUiState())

    fun setEnabled(enabled: Boolean) {
        val identity = state.value.source?.identity ?: return
        viewModelScope.launch { sourceRegistry.setEnabled(identity, enabled) }
    }

    fun setPinned(pinned: Boolean) {
        val identity = state.value.source?.identity ?: return
        viewModelScope.launch { sourceRegistry.setPinned(identity, pinned) }
    }

    fun remove(onRemoved: () -> Unit) {
        val identity = state.value.source?.identity ?: return
        viewModelScope.launch {
            service.removeSource(identity)
            onRemoved()
        }
    }

    companion object {
        fun factory(identity: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as AnansiApplication).container
                SourceManageViewModel(
                    sourceRegistry = container.sourceRegistryRepository,
                    service = container.repoRegistrationService,
                    errorDao = container.database.sourceErrorDao(),
                    identity = identity,
                )
            }
        }
    }
}
