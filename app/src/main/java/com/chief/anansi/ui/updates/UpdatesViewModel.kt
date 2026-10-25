package com.chief.anansi.ui.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.dao.UpdateRow
import com.chief.anansi.data.repo.CheckAllSummary
import com.chief.anansi.data.repo.NovelCheckResult
import com.chief.anansi.data.repo.UpdateRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One Updates-tab row's rendered state: the persisted check result (the
 * new-arrival aggregate and checked time) overlaid with the run-scoped
 * pieces that only live in memory - the in-flight spinner and the latest
 * failure kind and reason, which the schema's three columns cannot hold.
 */
data class UpdateRowUi(
    val row: UpdateRow,
    val checking: Boolean = false,
    val failureReason: String? = null,
    val failureKind: String? = null,
)

data class UpdatesUiState(
    /** Null until the database's first emission; empty means no library novels. */
    val rows: List<UpdateRowUi>? = null,
    val checkingAll: Boolean = false,
)

/** The run's completion counts, surfaced once as the snackbar summary. */
data class UpdatesSummaryUi(
    val updated: Int,
    val caughtUp: Int,
    val failed: Int,
    val skipped: Int,
)

/**
 * The Updates tab: the manual check-all with its in-flight
 * guard, per-row progress, and the run's completion summary. Checks run
 * sequentially in this scope, so leaving the tab cancels the run's UI work
 * through the normal ViewModel teardown; there is no background checking.
 */
class UpdatesViewModel(
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdatesUiState())
    val state: StateFlow<UpdatesUiState> = _state

    private val _summary = MutableStateFlow<UpdatesSummaryUi?>(null)
    val summary: StateFlow<UpdatesSummaryUi?> = _summary

    /** The manual check-all's in-flight guard: a second run is ignored. */
    private val checkAllRunning = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            updateRepository.observeUpdateRows().collect { rows ->
                _state.update { current ->
                    val uiRows = rows.map { known ->
                        val existing = current.rows?.firstOrNull { it.row.novel.id == known.novel.id }
                        UpdateRowUi(
                            row = known,
                            checking = existing?.checking == true,
                            failureReason = existing?.failureReason,
                            failureKind = existing?.failureKind,
                        )
                    }
                    current.copy(rows = uiRows)
                }
            }
        }
    }

    /** The appbar "Check now" action; ignored while a run is in flight. */
    fun checkAll() {
        if (!checkAllRunning.compareAndSet(false, true)) return
        _state.update { it.copy(checkingAll = true) }
        viewModelScope.launch {
            try {
                val result = updateRepository.checkAll(
                    onNovelStarted = { novelId ->
                        _state.update { current ->
                            current.copy(rows = current.rows?.map {
                                if (it.row.novel.id == novelId) {
                                    it.copy(checking = true, failureReason = null, failureKind = null)
                                } else {
                                    it
                                }
                            })
                        }
                    },
                    onNovelResult = { checkResult ->
                        _state.update { current ->
                            current.copy(rows = current.rows?.map {
                                if (it.row.novel.id == checkResult.novelId) {
                                    when (checkResult) {
                                        is NovelCheckResult.Failed -> it.copy(
                                            checking = false,
                                            failureReason = checkResult.reason,
                                            failureKind = checkResult.failureKind,
                                        )

                                        else ->
                                            it.copy(checking = false, failureReason = null, failureKind = null)
                                    }
                                } else {
                                    it
                                }
                            })
                        }
                    },
                )
                _summary.value = result.toUi()
            } finally {
                checkAllRunning.set(false)
                _state.update { it.copy(checkingAll = false) }
            }
        }
    }

    /** The screen shows the summary once, then acknowledges it. */
    fun summaryShown() {
        _summary.value = null
    }

    private fun CheckAllSummary.toUi() = UpdatesSummaryUi(
        updated = updated,
        caughtUp = caughtUp,
        failed = failed,
        skipped = skipped,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as AnansiApplication).container
                UpdatesViewModel(updateRepository = container.updateRepository)
            }
        }
    }
}
