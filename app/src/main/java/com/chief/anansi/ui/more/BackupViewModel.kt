package com.chief.anansi.ui.more

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.backup.BackupImportRepository
import com.chief.anansi.data.backup.BackupImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    val importing: Boolean = false,
    /** The finished import's summary; null until an import ran. */
    val result: BackupImportResult? = null,
    /** The file name the result dialog quotes. */
    val resultFileName: String? = null,
    /** The unmatched-review expansion of the result dialog. */
    val showUnmatched: Boolean = false,
    /** A top-level failure's named reason; nothing was written. */
    val failure: String? = null,
)

/**
 * The Backup and migrate screen's state: the one-shot SAF file
 * pick, the parse spinner, and the result or failure dialog. The picked
 * file is read on device and imported fully offline.
 */
class BackupViewModel(
    private val backupImportRepository: BackupImportRepository,
    private val resolver: ContentResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state

    fun import(uri: Uri) {
        if (_state.value.importing) return
        _state.update { BackupUiState(importing = true) }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                val fileName = withContext(Dispatchers.IO) { displayName(uri) }
                val result = withContext(Dispatchers.IO) { backupImportRepository.import(text) }
                _state.update {
                    BackupUiState(result = result, resultFileName = fileName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { BackupUiState(failure = e.message ?: "unknown failure") }
            }
        }
    }

    /** The dialog's Review unmatched toggle. */
    fun setShowUnmatched(show: Boolean) {
        _state.update { it.copy(showUnmatched = show) }
    }

    /** The result dialog's Done: the summary is acknowledged. */
    fun dismissResult() {
        _state.update { BackupUiState() }
    }

    /** The screen surfaces the failure once, then acknowledges it. */
    fun failureShown() {
        _state.update { it.copy(failure = null) }
    }

    private fun readText(uri: Uri): String =
        resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use { reader ->
            reader?.readText() ?: throw Exception("The file could not be opened for reading")
        }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column).orEmpty() else ""
        } ?: ""

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as AnansiApplication
                BackupViewModel(
                    backupImportRepository = application.container.backupImportRepository,
                    resolver = application.contentResolver,
                )
            }
        }
    }
}
