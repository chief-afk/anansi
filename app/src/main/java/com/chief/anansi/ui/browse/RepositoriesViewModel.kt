package com.chief.anansi.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.repo.RepoRegistrationService
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RepoRowUi(
    val repoUrl: String,
    val host: String,
    val sourceCount: Int,
    val ecosystem: String,
    val generationCounts: Map<String, Int>,
)

/** The add-repo flow state: working indicator, result report, or failure reason. */
data class RepoAddUiState(
    val working: Boolean = false,
    val report: RepoRegistrationService.RepoAddReport? = null,
    val error: String? = null,
)

class RepositoriesViewModel(
    private val service: RepoRegistrationService,
    sourceRegistry: SourceRegistryRepository,
) : ViewModel() {

    val repos: StateFlow<List<RepoRowUi>> = sourceRegistry.observeAll()
        .map { sources ->
            sources.filter { it.repoUrl.isNotEmpty() }
                .groupBy { it.repoUrl }
                .map { (repoUrl, rows) ->
                    RepoRowUi(
                        repoUrl = repoUrl,
                        host = hostOf(repoUrl),
                        sourceCount = rows.size,
                        ecosystem = rows.first().ecosystem,
                        generationCounts = rows.groupingBy { it.generation }.eachCount(),
                    )
                }
                .sortedBy { it.host }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _add = MutableStateFlow(RepoAddUiState())
    val add: StateFlow<RepoAddUiState> = _add.asStateFlow()

    fun addRepo(url: String) {
        if (_add.value.working) return
        viewModelScope.launch {
            _add.value = RepoAddUiState(working = true)
            _add.value = try {
                RepoAddUiState(report = service.addOrUpdateRepo(url))
            } catch (e: SourcePluginException) {
                RepoAddUiState(error = e.error.reason)
            } catch (e: Exception) {
                RepoAddUiState(error = e.message ?: "Unexpected error")
            }
        }
    }

    fun removeRepo(repoUrl: String) {
        viewModelScope.launch { service.removeRepo(repoUrl) }
    }

    fun dismissResult() {
        if (!_add.value.working) _add.value = RepoAddUiState()
    }

    private fun hostOf(url: String): String = try {
        URI(url).host ?: url
    } catch (e: Exception) {
        url
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as AnansiApplication).container
                RepositoriesViewModel(
                    service = container.repoRegistrationService,
                    sourceRegistry = container.sourceRegistryRepository,
                )
            }
        }
    }
}
