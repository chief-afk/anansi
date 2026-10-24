package com.chief.anansi.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.dao.HistoryEntry
import com.chief.anansi.data.repo.HistoryRepository
import com.chief.anansi.data.repo.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val historyRepository: HistoryRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val history: Flow<List<HistoryEntry>> = historyRepository.observeHistory()

    fun removeEntry(novelId: Long) {
        viewModelScope.launch { historyRepository.removeEntry(novelId) }
    }

    fun clearAll() {
        viewModelScope.launch { historyRepository.clearAll() }
    }

    /**
     * The uniform entry contract: ensure the reader's rows, then navigate.
     * A progress row always has its novel row via FK, so this is the
     * idempotent fast path; a failure still navigates and the reader
     * renders its loud failure states.
     */
    fun openEntry(entry: HistoryEntry, onReady: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                libraryRepository.ensureSourceNovelRows(entry.sourceIdentity, entry.novel.url)
            }
            onReady()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as AnansiApplication
                HistoryViewModel(
                    historyRepository = application.container.historyRepository,
                    libraryRepository = application.container.libraryRepository,
                )
            }
        }
    }
}
