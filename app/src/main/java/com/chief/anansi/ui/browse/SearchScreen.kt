package com.chief.anansi.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.R
import com.chief.anansi.plugin.SourceNovelItem
import com.chief.anansi.plugin.runtime.SourceRuntimeLimits
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.NovelCover
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Global search: per-source result groups with explicit
 * no-results and error groups, so a dead or picky source is visible instead
 * of silently missing. Reached from the Browse appbar lens; the catalog's
 * appbar lens reaches the same screen scoped to one source. Results route
 * to novel details.
 */
@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(
    scopedIdentity: String?,
    onBack: () -> Unit,
    onOpenNovel: (sourceIdentity: String, novelPath: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel(
        key = scopedIdentity ?: "global",
        factory = SearchViewModel.factory(scopedIdentity),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var field by rememberSaveable { mutableStateOf("") }

    // Auto-run: typing settles for a beat and runs. A query the ViewModel
    // already answered (screen recreation restores the field) is skipped.
    LaunchedEffect(Unit) {
        snapshotFlow { field }
            .map { it.trim() }
            .filter {
                it.length >= SourceRuntimeLimits.SEARCH_MIN_QUERY_LENGTH && it != state.query
            }
            .distinctUntilChanged()
            .debounce(SourceRuntimeLimits.SEARCH_DEBOUNCE_MILLIS)
            .collectLatest { viewModel.search(it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SearchHeader(
            title = state.scopedSourceName.ifEmpty { stringResource(R.string.screen_search) },
            searching = state.groups.any { it.status == SearchGroupStatus.Searching },
            done = state.groups.count {
                it.status == SearchGroupStatus.Done || it.status == SearchGroupStatus.Failed
            },
            total = state.groups.size,
            onBack = onBack,
        )
        OutlinedTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            placeholder = {
                Text(
                    text = if (state.scopedSourceName.isEmpty()) {
                        stringResource(R.string.search_hint_all)
                    } else {
                        stringResource(R.string.search_hint_source, state.scopedSourceName)
                    },
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (field.isNotEmpty()) {
                    IconButton(onClick = {
                        field = ""
                        viewModel.clearQuery()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.cd_search_clear),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(field) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        when {
            !state.hasSearched -> EmptyState(
                icon = painterResource(R.drawable.ic_search),
                title = stringResource(R.string.search_initial_title),
                body = stringResource(
                    if (state.groups.isEmpty() && state.scopedSourceName.isEmpty()) {
                        R.string.search_no_sources_body
                    } else {
                        R.string.search_initial_body
                    },
                ),
            )

            else -> ResultGroups(
                state = state,
                onOpenNovel = onOpenNovel,
                onRetryGroup = viewModel::retryGroup,
            )
        }
    }
}

@Composable
private fun SearchHeader(
    title: String,
    searching: Boolean,
    done: Int,
    total: Int,
    onBack: () -> Unit,
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
        when {
            searching -> {
                Text(
                    text = stringResource(R.string.search_progress_counts, done, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
            }

            total > 0 && done == total -> {
                Text(
                    text = stringResource(R.string.search_progress_complete, done, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

@Composable
private fun ResultGroups(
    state: SearchUiState,
    onOpenNovel: (String, String) -> Unit,
    onRetryGroup: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        state.groups.forEach { group ->
            item(key = "${group.identity}-header") {
                GroupHeader(group = group)
            }
            when (group.status) {
                SearchGroupStatus.Pending, SearchGroupStatus.Searching -> item(
                    key = "${group.identity}-progress",
                ) {
                    Text(
                        text = stringResource(R.string.search_group_searching),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                    )
                }

                SearchGroupStatus.Done ->
                    if (group.novels.isEmpty()) {
                        item(key = "${group.identity}-empty") {
                            Text(
                                text = stringResource(R.string.search_no_results_body),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                            )
                        }
                    } else {
                        items(
                            group.novels,
                            key = { "${group.identity}-${it.path}" },
                        ) { novel ->
                            ResultRow(
                                novel = novel,
                                coverHeaders = group.coverHeaders,
                                onOpen = { onOpenNovel(group.identity, novel.path) },
                            )
                        }
                    }

                SearchGroupStatus.Failed -> item(key = "${group.identity}-error") {
                    GroupError(
                        kind = group.failureKind,
                        reason = group.failureReason,
                        onRetry = { onRetryGroup(group.identity) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: SearchGroupUi) {
    val header = when (group.status) {
        SearchGroupStatus.Done ->
            stringResource(R.string.search_group_results, group.sourceName, group.novels.size)
        SearchGroupStatus.Failed ->
            stringResource(R.string.search_group_failed, group.sourceName)
        SearchGroupStatus.Pending, SearchGroupStatus.Searching ->
            stringResource(R.string.search_group_searching_source, group.sourceName)
    }
    Text(
        text = header.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ResultRow(
    novel: SourceNovelItem,
    coverHeaders: Map<String, String>,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
    ) {
        NovelCover(
            title = novel.name,
            coverUrl = novel.cover,
            headers = coverHeaders,
            modifier = Modifier
                .width(46.dp)
                .height(66.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            text = novel.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GroupError(kind: String, reason: String, onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, bottom = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = kind,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
