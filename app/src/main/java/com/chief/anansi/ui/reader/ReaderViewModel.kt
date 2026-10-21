package com.chief.anansi.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.ReadingProgressEntity
import com.chief.anansi.data.repo.HistoryRepository
import com.chief.anansi.data.repo.LibraryRepository
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.ui.browse.SourceSessions
import com.chief.anansi.ui.browse.failureKindOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The registry-sourced failure pair the reader displays, never reclassifies. */
data class ReaderFailure(val kind: String, val reason: String)

/** A neighboring chapter row the footer opens; null at the list's ends. */
data class NeighborChapter(val rowId: Long, val path: String)

data class ReaderUiState(
    val loading: Boolean = true,
    /** The source or shelved novel behind this chapter no longer exists. */
    val unavailable: Boolean = false,
    val failure: ReaderFailure? = null,
    val paragraphs: List<String> = emptyList(),
    /**
     * The unsplit chapter text: the sentence layer runs over it so
     * highlight offsets land exactly where the paragraphs render.
     */
    val chapterText: String = "",
    val novelTitle: String = "",
    val chapterTitle: String = "",
    val percent: Int = 0,
    val previousChapter: NeighborChapter? = null,
    val nextChapter: NeighborChapter? = null,
    /** The saved offset to restore once; 0 opens at the top. */
    val initialOffsetPx: Int = 0,
) {
    val hasText: Boolean get() = paragraphs.isNotEmpty()
}

/**
 * The scroll-mode reader: loads chapter text through the same
 * Source path the details screen uses, renders registry-sourced states, and
 * writes progress through the per-novel library record (offset + percent,
 * throttled during scroll, definitive on exit and app background). The read
 * mark applies at 97 percent through the library read marks; a chapter stored
 * at 100 percent reopens at the top (v1 parity).
 */
class ReaderViewModel(
    private val sourceRegistry: SourceRegistryRepository,
    private val sessions: SourceSessions,
    private val libraryRepository: LibraryRepository,
    private val historyRepository: HistoryRepository,
    private val textCache: ChapterTextCache,
    private val settingsStore: ReaderSettingsStore,
    private val appScope: CoroutineScope,
    private val identity: String,
    private val novelPath: String,
    private val chapterPath: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state

    val settings: StateFlow<ReaderSettings> = settingsStore.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ReaderSettings(),
    )

    /** The chapter row behind the loaded content; null until resolved. */
    private var novelId: Long = 0
    private var currentChapter: ChapterEntity? = null

    /** The latest on-scroll position awaiting its library write. */
    private var pendingWrite: ProgressWrite? = null
    private var lastWriteAtMs = 0L

    private data class ProgressWrite(val chapterId: Long, val offsetPx: Int, val percent: Int)

    init {
        refresh()
    }

    /** Loads (or reloads) the current chapter's text. */
    fun retry() {
        if (_state.value.unavailable) return
        refresh()
    }

    /**
     * The scroll feed: percent updates live for the footer; the library
     * write is throttled to about one per 500 ms. [flushProgress] makes the
     * latest position definitive on chapter change, reader exit, and app
     * background.
     */
    fun recordScroll(offsetPx: Int, percent: Int) {
        val chapter = currentChapter ?: return
        val clampedPercent = percent.coerceIn(0, 100)
        _state.update { it.copy(percent = clampedPercent) }
        pendingWrite = ProgressWrite(chapter.id, offsetPx, clampedPercent)
        val now = System.currentTimeMillis()
        if (now - lastWriteAtMs >= WRITE_THROTTLE_MS) {
            persistProgress()
        }
    }

    fun flushProgress() {
        if (pendingWrite != null) persistProgress()
    }

    fun setTheme(theme: ReaderTheme) {
        viewModelScope.launch { settingsStore.setTheme(theme) }
    }

    fun setTextSizeSp(sizeSp: Int) {
        viewModelScope.launch { settingsStore.setTextSizeSp(sizeSp) }
    }

    fun setLineSpacing(spacing: Float) {
        viewModelScope.launch { settingsStore.setLineSpacing(spacing) }
    }

    fun setTypeface(typeface: ReaderTypeface) {
        viewModelScope.launch { settingsStore.setTypeface(typeface) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setKeepScreenOn(enabled) }
    }

    fun setVolumeKeysScroll(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setVolumeKeysScroll(enabled) }
    }

    private fun refresh() {
        _state.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            try {
                loadChapter()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailure(e)
            }
        }
    }

    private suspend fun loadChapter() {
        val sourceEntity = sourceRegistry.findByIdentity(identity)
            ?: return unavailable()
        val source = sessions.load(sourceEntity)
        val novel = libraryRepository.novelBySourceNovel(sourceEntity.id, novelPath)
            ?: return unavailable()
        val shelf = libraryRepository.novelWithProgress(novel.id)
            ?: return unavailable()
        // The @Relation list carries no ORDER BY (it can come back in the
        // unique-index's lexicographic chapterUrl order); neighbors key on
        // the source order the details screen shows.
        val chapters = shelf.chapters.sortedBy { it.sortOrder }
        val current = chapters.firstOrNull { it.chapterUrl == chapterPath }
            ?: return unavailable()
        val index = chapters.indexOf(current)

        this.novelId = novel.id
        this.currentChapter = current

        val progress = shelf.progress.firstOrNull()
        val restoresThisChapter = progress != null &&
            progress.chapterId == current.id &&
            progress.percent < PERCENT_FINISHED
        val restoredPercent = if (restoresThisChapter) progress.percent else 0

        val text = textCache.get(current.id)
            ?: source.chapterText(novel.url, current.chapterUrl)
        if (text.isBlank()) {
            // An empty chapter never opens as a blank reader (board matrix);
            // the adapters already raise this through the registry, so this
            // guard only covers sources that return blank text directly.
            throw EmptyChapterException(chapterPath)
        }
        textCache.put(current.id, text)

        _state.update {
            it.copy(
                loading = false,
                unavailable = false,
                failure = null,
                paragraphs = splitParagraphs(text),
                chapterText = text,
                novelTitle = novel.title,
                chapterTitle = current.title,
                percent = restoredPercent,
                previousChapter = chapters.getOrNull(index - 1)?.let { chapter ->
                    NeighborChapter(chapter.id, chapter.chapterUrl)
                },
                nextChapter = chapters.getOrNull(index + 1)?.let { chapter ->
                    NeighborChapter(chapter.id, chapter.chapterUrl)
                },
                initialOffsetPx = if (restoresThisChapter) {
                    progress.scrollOffset.coerceAtLeast(0).toInt()
                } else {
                    0
                },
            )
        }
    }

    private fun showFailure(e: Exception) {
        val failure = when (e) {
            is EmptyChapterException -> ReaderFailure(
                kind = "Parse",
                reason = e.message ?: "Source returned an empty chapter",
            )

            else -> ReaderFailure(
                kind = failureKindOf(e),
                reason = e.message ?: "unknown failure",
            )
        }
        _state.update {
            it.copy(loading = false, failure = failure)
        }
    }

    private fun unavailable() {
        _state.update { it.copy(loading = false, unavailable = true) }
    }

    private fun persistProgress() {
        val write = pendingWrite ?: return
        pendingWrite = null
        lastWriteAtMs = System.currentTimeMillis()
        // Launches on the app scope, not viewModelScope: reader exit and the
        // footer's chapter switch tear the back-stack entry down, whose
        // clear() would cancel the definitive write before Room commits it.
        appScope.launch {
            historyRepository.upsertProgress(
                ReadingProgressEntity(
                    novelId = novelId,
                    chapterId = write.chapterId,
                    scrollOffset = write.offsetPx.toLong(),
                    percent = write.percent,
                    updatedAt = lastWriteAtMs,
                ),
            )
            if (write.percent >= READ_MARK_PERCENT) {
                libraryRepository.setChapterRead(novelId, write.chapterId, true)
            }
        }
    }

    private fun splitParagraphs(text: String): List<String> =
        text.split("\n\n")
            .map { block -> block.trim() }
            .filter { block -> block.isNotEmpty() }

    private class EmptyChapterException(path: String) :
        RuntimeException("Source returned an empty chapter for \"$path\"")

    companion object {
        /** v1's read-mark threshold; the audio reader reuses it for spoken chapters. */
        const val READ_MARK_PERCENT = 97
        const val PERCENT_FINISHED = 100
        private const val WRITE_THROTTLE_MS = 500L

        fun factory(
            identity: String,
            novelPath: String,
            chapterPath: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as AnansiApplication).container
                ReaderViewModel(
                    sourceRegistry = container.sourceRegistryRepository,
                    sessions = container.sourceSessions,
                    libraryRepository = container.libraryRepository,
                    historyRepository = container.historyRepository,
                    textCache = container.chapterTextCache,
                    settingsStore = container.readerSettingsStore,
                    appScope = container.appScope,
                    identity = identity,
                    novelPath = novelPath,
                    chapterPath = chapterPath,
                )
            }
        }
    }
}
