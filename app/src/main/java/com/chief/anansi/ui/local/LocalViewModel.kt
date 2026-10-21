package com.chief.anansi.ui.local

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
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.local.LocalLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One imported-novels row: metadata plus its import marks. */
data class LocalNovelRow(
    val novelPath: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val chapterCount: Int,
    /** The display label of the stored format: "EPUB" or "TXT". */
    val format: String,
    val addedAt: Long,
)

data class LocalImportOutcome(val imported: Int, val failures: List<String>)

data class LocalUiState(
    val novels: List<LocalNovelRow> = emptyList(),
    val importing: Boolean = false,
    /** The finished import run's counts and per-file failure reasons. */
    val outcome: LocalImportOutcome? = null,
)

/**
 * The Local source screen's state: the imported-novels list and
 * the SAF document-picker import run. The list exists before the first
 * import (it is simply empty until the fixed Local source registers itself
 * inside the first import).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalViewModel(
    private val sourceRegistry: SourceRegistryRepository,
    private val localLibrary: LocalLibrary,
    private val resolver: ContentResolver,
) : ViewModel() {

    private data class ImportRun(
        val running: Boolean = false,
        val outcome: LocalImportOutcome? = null,
    )

    private val importRun = MutableStateFlow(ImportRun())

    val state: StateFlow<LocalUiState> =
        combine(
            sourceRegistry.observeByIdentity(LocalLibrary.IDENTITY),
            importRun,
        ) { entity, run -> entity to run }
            .flatMapLatest { (entity, run) ->
                // Re-queried per emission: each import-run change re-reads the
                // novel list, so finished imports land without a screen restart.
                flow {
                    val novels = if (entity == null) {
                        emptyList()
                    } else {
                        localLibrary.importedNovels(entity.id)
                    }
                    val display = ArrayList<LocalNovelRow>(novels.size)
                    for (row in novels) {
                        display += LocalNovelRow(
                            novelPath = row.novel.url,
                            title = row.novel.title,
                            author = row.novel.author,
                            coverUrl = row.novel.coverUrl,
                            chapterCount = row.chapterCount,
                            format = localLibrary.formatOf(row.novel.id),
                            addedAt = row.novel.addedAt,
                        )
                    }
                    emit(LocalUiState(novels = display, importing = run.running, outcome = run.outcome))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalUiState())

    /**
     * Imports the picked documents one at a time; each file becomes one
     * novel, and a failed file never blocks the rest.
     */
    fun import(uris: List<Uri>) {
        if (importRun.value.running || uris.isEmpty()) return
        importRun.value = ImportRun(running = true)
        viewModelScope.launch {
            var imported = 0
            val failures = mutableListOf<String>()
            for (uri in uris) {
                try {
                    val name = withContext(Dispatchers.IO) { displayName(uri) }
                    val mime = withContext(Dispatchers.IO) { resolver.getType(uri) }
                    localLibrary.import(fileName = name, mimeType = mime) {
                        resolver.openInputStream(uri)
                    }
                    imported++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += e.message ?: "unknown failure"
                }
            }
            importRun.value = ImportRun(running = false, outcome = LocalImportOutcome(imported, failures))
        }
    }

    /** The screen surfaces the outcome once, then acknowledges it. */
    fun outcomeShown() {
        importRun.value = importRun.value.copy(outcome = null)
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
                LocalViewModel(
                    sourceRegistry = application.container.sourceRegistryRepository,
                    localLibrary = application.container.localLibrary,
                    resolver = application.contentResolver,
                )
            }
        }
    }
}
