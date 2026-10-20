package com.chief.anansi.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.dao.LibraryNovel
import com.chief.anansi.data.repo.LibraryRepository
import kotlinx.coroutines.flow.Flow

class LibraryViewModel(libraryRepository: LibraryRepository) : ViewModel() {

    val library: Flow<List<LibraryNovel>> = libraryRepository.observeLibrary()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as AnansiApplication
                LibraryViewModel(application.container.libraryRepository)
            }
        }
    }
}
