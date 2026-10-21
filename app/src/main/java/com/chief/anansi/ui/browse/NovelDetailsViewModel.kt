package com.chief.anansi.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.data.repo.LibraryRepository
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.SourceChapterItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The chapter list orders; source order is the default. */
enum class ChapterSort { SourceOrder, NewestFirst, OldestFirst }

data class NovelDisplay(
    val title: String,
    val coverUrl: String?,
    val author: String?,
    val artist: String?,
    val status: String?,
    val genres: String?,
    val summary: String?,
)

data class ChapterRow(
    /** The library row id once shelved; null while the novel is source-only. */
    val id: Long?,
    val path: String,
    val title: String,
    val releaseDate: String?,
    val sortOrder: Int,
    val isRead: Boolean,
)

data class NovelDetailsUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val failureKind: String = "",
    val failureReason: String = "",
    val sourceMissing: Boolean = false,
    val sourceName: String = "",
    val sourceLine: String = "",
    val coverHeaders: Map<String, String> = emptyMap(),
    val novel: NovelDisplay? = null,
    val inLibrary: Boolean = false,
    val libraryRowId: Long? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val sort: ChapterSort = ChapterSort.SourceOrder,
    val showUnread: Boolean = true,
    val showRead: Boolean = true,
)

/**
 * Novel details: metadata, cover, in-library state, and the
 * chapter list with read-mark dimming. Shelving inserts through the
 * library repository; read marks write only through the library read-mark
 * store. The chapter sort/filter sheet is a view over that one
 * state, never a second store.
 */
class NovelDetailsViewModel(
    private val sourceRegistry: SourceRegistryRepository,
    private val sessions: SourceSessions,
    private val libraryRepository: LibraryRepository,
    private val identity: String,
    private val novelPath: String,
) : ViewModel() {

    private val _state = MutableStateFlow(NovelDetailsUiState())
    val state: StateFlow<NovelDetailsUiState> = _state

    private var entity: SourceEntity? = null

    /** The source's own chapter list, used until the novel is shelved. */
    private var sourceChapters: List<ChapterRow> = emptyList()

    init {
        viewModelScope.launch {
            val loaded = sourceRegistry.findByIdentity(identity)
            if (loaded == null) {
                _state.update { it.copy(sourceMissing = true, loading = false) }
                return@launch
            }
            entity = loaded
            observeLibraryRow(loaded.id)
            loadDetails(loaded)
        }
    }

    fun toggleLibrary() {
        val entityId = entity?.id ?: return
        val display = _state.value.novel ?: return
        viewModelScope.launch {
            val rowId = _state.value.libraryRowId
            if (rowId != null) {
                libraryRepository.setInLibrary(rowId, !_state.value.inLibrary)
            } else {
                libraryRepository.addNovelWithChapters(
                    novel = NovelEntity(
                        sourceId = entityId,
                        sourceNovelId = novelPath,
                        title = display.title,
                        author = display.author,
                        artist = display.artist,
                        status = display.status,
                        genre = display.genres,
                        summary = display.summary,
                        coverUrl = display.coverUrl,
                        url = novelPath,
                        inLibrary = true,
                        addedAt = System.currentTimeMillis(),
                    ),
                    chapters = sourceChapters.map { chapter ->
                        ChapterEntity(
                            novelId = 0,
                            // LNReader chapters carry no separate id; the
                            // verbatim path is the source-local identity.
                            sourceChapterId = chapter.path,
                            chapterUrl = chapter.path,
                            title = chapter.title,
                            releaseDate = chapter.releaseDate,
                            sortOrder = chapter.sortOrder,
                        )
                    },
                )
            }
        }
    }

    fun setChapterRead(chapter: ChapterRow, isRead: Boolean) {
        val rowId = _state.value.libraryRowId ?: return
        val chapterId = chapter.id ?: return
        viewModelScope.launch {
            libraryRepository.setChapterRead(rowId, chapterId, isRead)
        }
    }

    fun applySheetOptions(sort: ChapterSort, showUnread: Boolean, showRead: Boolean) {
        _state.update {
            it.copy(sort = sort, showUnread = showUnread, showRead = showRead)
        }
    }

    /** Re-invokes the failed details call. */
    fun retry() {
        val loaded = entity ?: return
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch { loadDetails(loaded) }
    }

    private suspend fun loadDetails(loaded: SourceEntity) {
        try {
            val source = sessions.load(loaded)
            val details = source.novelDetails(novelPath)
            val fromSource = details.chapters.mapIndexed { index, chapter ->
                chapter.toChapterRow(index)
            }
            sourceChapters = fromSource
            _state.update { current ->
                current.copy(
                    loading = false,
                    sourceName = source.name,
                    sourceLine = sourceLine(source.name, loaded.lang, source.ecosystem),
                    coverHeaders = coverHeaders(source),
                    novel = NovelDisplay(
                        title = details.name ?: novelPath,
                        coverUrl = details.cover,
                        author = details.author,
                        artist = details.artist,
                        status = details.status,
                        genres = details.genres,
                        summary = details.summary,
                    ),
                    chapters = if (current.libraryRowId != null) current.chapters else fromSource,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    failed = true,
                    failureKind = failureKindOf(e),
                    failureReason = e.message ?: "unknown failure",
                )
            }
        }
    }

    /** The shelved row drives in-library state and the chapters' read marks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLibraryRow(sourceEntityId: Long) {
        viewModelScope.launch {
            libraryRepository.observeBySourceNovel(sourceEntityId, novelPath)
                .flatMapLatest { row ->
                    if (row == null) {
                        flowOf(null to emptyList())
                    } else {
                        libraryRepository.observeChapters(row.id).map { chapters -> row to chapters }
                    }
                }
                .collect { (row, chapters) ->
                    _state.update { current ->
                        current.copy(
                            libraryRowId = row?.id,
                            inLibrary = row?.inLibrary == true,
                            chapters = if (row != null) {
                                chapters.map { chapter ->
                                    ChapterRow(
                                        id = chapter.id,
                                        path = chapter.chapterUrl,
                                        title = chapter.title,
                                        releaseDate = chapter.releaseDate,
                                        sortOrder = chapter.sortOrder,
                                        isRead = chapter.isRead,
                                    )
                                }
                            } else {
                                sourceChapters
                            },
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(identity: String, novelPath: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val container = (this[APPLICATION_KEY] as AnansiApplication).container
                    NovelDetailsViewModel(
                        sourceRegistry = container.sourceRegistryRepository,
                        sessions = container.sourceSessions,
                        libraryRepository = container.libraryRepository,
                        identity = identity,
                        novelPath = novelPath,
                    )
                }
            }
    }
}

/** "Source · LANG · ecosystem": the source line names the ecosystem origin. */
private fun sourceLine(name: String, lang: String, ecosystem: String): String =
    listOfNotNull(
        name,
        lang.takeIf { it.isNotBlank() }?.uppercase(),
        ecosystem.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

private fun SourceChapterItem.toChapterRow(sortOrder: Int): ChapterRow = ChapterRow(
    id = null,
    path = path,
    title = name,
    releaseDate = releaseTime,
    sortOrder = sortOrder,
    isRead = false,
)

/**
 * The visible chapter list: the sheet's sort and show filters applied to the
 * current rows (source order is the default sort because novel
 * sites mean their order).
 */
internal fun visibleChapters(state: NovelDetailsUiState): List<ChapterRow> {
    val filtered = state.chapters.filter { chapter ->
        (chapter.isRead && state.showRead) || (!chapter.isRead && state.showUnread)
    }
    return when (state.sort) {
        ChapterSort.SourceOrder -> filtered.sortedBy { it.sortOrder }
        ChapterSort.NewestFirst -> filtered.sortedWith(
            compareByDescending<ChapterRow> { it.releaseDate.orEmpty() }
                .thenByDescending { it.sortOrder },
        )

        ChapterSort.OldestFirst -> filtered.sortedWith(
            compareBy<ChapterRow> { it.releaseDate.orEmpty() }.thenBy { it.sortOrder },
        )
    }
}
