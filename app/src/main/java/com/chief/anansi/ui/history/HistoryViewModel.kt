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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class HistoryViewModel(private val historyRepository: HistoryRepository) : ViewModel() {

    val history: Flow<List<HistoryEntry>> = historyRepository.observeHistory()

    fun removeEntry(novelId: Long) {
        viewModelScope.launch { historyRepository.removeEntry(novelId) }
    }

    fun clearAll() {
        viewModelScope.launch { historyRepository.clearAll() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as AnansiApplication
                HistoryViewModel(application.container.historyRepository)
            }
        }
    }
}
