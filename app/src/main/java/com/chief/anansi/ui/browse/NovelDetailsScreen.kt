package com.chief.anansi.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.R
import com.chief.anansi.ui.LocalTtsController
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.NovelCover
import com.chief.anansi.ui.reader.TtsDrawer
import com.chief.anansi.ui.reader.rememberTtsStart

/**
 * Novel details: metadata, source line naming the ecosystem
 * origin, the Coil cover, the In library toggle, and the chapter list with
 * read-mark dimming. The sort/filter sheet ships here; the
 * actions are In library, the per-chapter read marks, and the chapter tap
 * that opens the reader (the reader's entry point). The Listen control arrives
 * with the audio reader and appears nowhere as a dead button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailsScreen(
    identity: String,
    novelPath: String,
    onBack: () -> Unit,
    onOpenChapter: (ChapterRow) -> Unit,
    modifier: Modifier = Modifier,
    /** Runs after a local novel's removal deletes the novel for good. */
    onRemoved: () -> Unit = {},
    viewModel: NovelDetailsViewModel = viewModel(
        key = "$identity::$novelPath",
        factory = NovelDetailsViewModel.factory(identity, novelPath),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    // Listen starts the queue at the progress record's chapter,
    // else the first chapter, and opens the audio sheet.
    val ttsController = LocalTtsController.current
    var showTtsDrawer by remember { mutableStateOf(false) }
    val startListen = rememberTtsStart {
        ttsController.start(identity, novelPath, null)
        showTtsDrawer = true
    }
    // A failed or skipped refresh surfaces once as a snackbar; the screen
    // keeps its loaded content either way.
    val snackbarHostState = remember { SnackbarHostState() }
    val refreshMessage = when {
        state.refreshFailure != null -> state.refreshFailure
        state.refreshSkipped -> stringResource(R.string.details_refresh_skipped)
        else -> null
    }
    LaunchedEffect(refreshMessage) {
        if (refreshMessage != null) {
            snackbarHostState.showSnackbar(refreshMessage)
            viewModel.refreshFailureShown()
        }
    }
    // A local removal deleted the novel and its stored files: the screen
    // leaves with it.
    LaunchedEffect(state.removed) {
        if (state.removed) onRemoved()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            DetailsHeader(
                title = state.novel?.title.orEmpty(),
                onBack = onBack,
                refreshing = state.refreshing,
                onRefresh = viewModel::refreshFromSource,
            )
            when {
                state.sourceMissing -> EmptyState(
                    icon = painterResource(R.drawable.ic_globe),
                    title = stringResource(R.string.details_source_missing_title),
                    body = stringResource(R.string.details_source_missing_body),
                )

                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.failed -> SourceErrorState(
                    kind = state.failureKind,
                    reason = state.failureReason,
                    onRetry = viewModel::retry,
                )

                else -> NovelDetailsContent(
                    state = state,
                    onToggleLibrary = viewModel::toggleLibrary,
                    onMarkRead = viewModel::setChapterRead,
                    onOpenSheet = { showSheet = true },
                    onOpenChapter = { chapter ->
                        viewModel.openChapter(chapter, onReady = onOpenChapter)
                    },
                    onListen = startListen,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showTtsDrawer) {
        TtsDrawer(onDismiss = { showTtsDrawer = false })
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ChapterSheetContent(
                state = state,
                onApply = { sort, showUnread, showRead ->
                    showSheet = false
                    viewModel.applySheetOptions(sort, showUnread, showRead)
                },
            )
        }
    }
}

@Composable
private fun DetailsHeader(
    title: String,
    onBack: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 4.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.cd_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.cd_details_refresh),
                )
            }
        }
    }
}

@Composable
private fun NovelDetailsContent(
    state: NovelDetailsUiState,
    onToggleLibrary: () -> Unit,
    onMarkRead: (ChapterRow, Boolean) -> Unit,
    onOpenSheet: () -> Unit,
    onOpenChapter: (ChapterRow) -> Unit,
    onListen: () -> Unit,
) {
    val novel = state.novel ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "meta") {
            MetaHeader(
                state = state,
                novel = novel,
                onToggleLibrary = onToggleLibrary,
                onListen = onListen,
            )
        }
        item(key = "summary") {
            if (!novel.summary.isNullOrBlank()) {
                Text(
                    text = novel.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        }
        item(key = "chapters-header") {
            ChaptersHeader(
                chapterCount = state.chapters.size,
                onOpenSheet = onOpenSheet,
            )
        }
        items(visibleChapters(state), key = { it.path }) { chapter ->
            ChapterItem(
                chapter = chapter,
                onToggleRead = { read -> onMarkRead(chapter, read) },
                onOpen = { onOpenChapter(chapter) },
            )
        }
    }
}

@Composable
private fun MetaHeader(
    state: NovelDetailsUiState,
    novel: NovelDisplay,
    onToggleLibrary: () -> Unit,
    onListen: () -> Unit,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
        ) {
            NovelCover(
                title = novel.title,
                coverUrl = novel.coverUrl,
                headers = state.coverHeaders,
                modifier = Modifier
                    .width(110.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = novel.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val author = novel.author ?: novel.artist
                if (!author.isNullOrBlank()) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val statusLine = listOfNotNull(
                    novel.status,
                    stringResource(R.string.details_chapter_count, state.chapters.size),
                ).joinToString(" · ")
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.sourceLine.isNotEmpty()) {
                    Text(
                        text = state.sourceLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!novel.genres.isNullOrBlank()) {
                    Text(
                        text = novel.genres,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        if (state.inLibrary) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                FilledTonalButton(
                    onClick = onToggleLibrary,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // A local novel leaves the library only by removal,
                        // which deletes it and its stored files.
                        stringResource(
                            if (state.isLocal) R.string.details_remove else R.string.details_in_library,
                        ),
                    )
                }
                // The Listen entry: any novel with a loaded chapter list
                // works, because beginQueue creates the reader rows the
                // queue needs.
                FilledTonalButton(
                    onClick = onListen,
                    enabled = state.chapters.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_headphones),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.details_listen))
                }
            }
        } else {
            Button(
                onClick = onToggleLibrary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.details_add_library))
            }
        }
    }
}

@Composable
private fun ChaptersHeader(chapterCount: Int, onOpenSheet: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.details_chapters_label, chapterCount).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSheet) {
            Icon(
                painter = painterResource(R.drawable.ic_filter),
                contentDescription = stringResource(R.string.cd_details_filter),
            )
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: ChapterRow,
    onToggleRead: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Tapping creates the reader rows lazily; progress and read
            // marks key on them once they exist.
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 4.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (chapter.isRead) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            chapter.releaseDate?.takeIf { it.isNotBlank() }?.let { releaseDate ->
                Text(
                    text = releaseDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Read marks exist once the novel is shelved; the toggle
        // appears with the library row instead of sitting inert before it.
        if (chapter.id != null) {
            IconButton(onClick = { onToggleRead(!chapter.isRead) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(
                        if (chapter.isRead) {
                            R.string.cd_details_mark_unread
                        } else {
                            R.string.cd_details_mark_read
                        },
                    ),
                    tint = if (chapter.isRead) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Sort order, show filter, and the visibly inert Downloaded chip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterSheetContent(
    state: NovelDetailsUiState,
    onApply: (ChapterSort, Boolean, Boolean) -> Unit,
) {
    var sort by remember(state.sort) { mutableStateOf(state.sort) }
    var showUnread by remember(state.showUnread) { mutableStateOf(state.showUnread) }
    var showRead by remember(state.showRead) { mutableStateOf(state.showRead) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.details_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            text = stringResource(R.string.details_sort_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SortChip(
                label = stringResource(R.string.details_sort_source),
                selected = sort == ChapterSort.SourceOrder,
                onClick = { sort = ChapterSort.SourceOrder },
            )
            SortChip(
                label = stringResource(R.string.details_sort_newest),
                selected = sort == ChapterSort.NewestFirst,
                onClick = { sort = ChapterSort.NewestFirst },
            )
            SortChip(
                label = stringResource(R.string.details_sort_oldest),
                selected = sort == ChapterSort.OldestFirst,
                onClick = { sort = ChapterSort.OldestFirst },
            )
        }
        Text(
            text = stringResource(R.string.details_show_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            FilterChip(
                selected = showUnread && showRead,
                onClick = {
                    showUnread = true
                    showRead = true
                },
                label = { Text(stringResource(R.string.details_show_all)) },
            )
            FilterChip(
                selected = showUnread,
                onClick = {
                    val next = !showUnread
                    showUnread = next
                    // At least one filter stays on so the list never goes
                    // silently empty.
                    if (!next && !showRead) showRead = true
                },
                label = { Text(stringResource(R.string.details_show_unread)) },
            )
            FilterChip(
                selected = showRead,
                onClick = {
                    val next = !showRead
                    showRead = next
                    if (!next && !showUnread) showUnread = true
                },
                label = { Text(stringResource(R.string.details_show_read)) },
            )
            // Where the downloads row lands,
            // deliberately inert, never a working control elsewhere.
            FilterChip(
                selected = false,
                onClick = {},
                enabled = false,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.details_downloaded))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.backlog_badge),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = {
                sort = ChapterSort.SourceOrder
                showUnread = true
                showRead = true
            }) {
                Text(stringResource(R.string.filter_reset))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onApply(sort, showUnread, showRead) }) {
                Text(stringResource(R.string.filter_apply))
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
    )
}
