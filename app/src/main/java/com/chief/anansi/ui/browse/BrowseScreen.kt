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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.NovelCover
import com.chief.anansi.ui.components.ScreenTitle

/**
 * The Browse Sources tab: one flat list of installed sources
 * across all repos and ecosystems, pinned first, with the ecosystem and
 * API generation footer per source, the Repositories chip, and the
 * no-repositories empty state. Tapping a source opens its
 * management surface; catalog tap-through arrives in a later phase.
 */
@Composable
fun BrowseScreen(
    onOpenRepositories: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = viewModel(factory = BrowseViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tab_browse))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text(stringResource(R.string.browse_chip_sources)) },
            )
            FilterChip(
                selected = false,
                onClick = onOpenRepositories,
                label = { Text(stringResource(R.string.browse_chip_repositories)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_globe),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.sources.isEmpty() -> EmptyState(
                icon = painterResource(R.drawable.ic_globe),
                title = stringResource(R.string.browse_empty_title),
                body = stringResource(R.string.browse_empty_body),
                action = {
                    Button(onClick = onOpenRepositories) {
                        Text(stringResource(R.string.browse_add_repository))
                    }
                },
            )

            else -> SourceList(
                state = state,
                onOpenSource = onOpenSource,
                onTogglePin = viewModel::togglePin,
                onSetEnabled = viewModel::setEnabled,
            )
        }
    }
}

@Composable
private fun SourceList(
    state: BrowseUiState,
    onOpenSource: (String) -> Unit,
    onTogglePin: (BrowseSourceRow) -> Unit,
    onSetEnabled: (BrowseSourceRow, Boolean) -> Unit,
) {
    val pinned = state.sources.filter { it.pinned }
    val all = state.sources.filterNot { it.pinned }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (pinned.isNotEmpty()) {
            item(key = "header-pinned") {
                SectionHeader(stringResource(R.string.browse_section_pinned))
            }
            items(pinned, key = { "pinned-${it.identity}" }) { row ->
                SourceRow(row, onOpenSource, onTogglePin, onSetEnabled)
            }
        }
        item(key = "header-all") {
            SectionHeader(stringResource(R.string.browse_section_all, all.size))
        }
        items(all, key = { it.identity }) { row ->
            SourceRow(row, onOpenSource, onTogglePin, onSetEnabled)
        }
        item(key = "footer") {
            Text(
                text = footerSummary(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp),
            )
        }
    }
}

@Composable
private fun footerSummary(state: BrowseUiState): String {
    val repoCount = state.repositoryCount
    val reposText = if (repoCount == 1) {
        stringResource(R.string.browse_footer_one_repository)
    } else {
        stringResource(R.string.browse_footer_repositories, repoCount)
    }
    return stringResource(R.string.browse_footer_summary, reposText, state.sources.size)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourceRow(
    row: BrowseSourceRow,
    onOpenSource: (String) -> Unit,
    onTogglePin: (BrowseSourceRow) -> Unit,
    onSetEnabled: (BrowseSourceRow, Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenSource(row.identity) }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        NovelCover(
            title = row.name,
            coverUrl = row.iconUrl.ifEmpty { null },
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rowMeta(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.error?.let { error ->
                Text(
                    text = stringResource(R.string.browse_row_error_line, error.kind, error.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.browse_row_menu_manage),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.pinned) R.string.browse_row_menu_unpin else R.string.browse_row_menu_pin,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onTogglePin(row)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.enabled) R.string.browse_row_menu_disable else R.string.browse_row_menu_enable,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onSetEnabled(row, !row.enabled)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_row_menu_manage)) },
                    onClick = {
                        menuExpanded = false
                        onOpenSource(row.identity)
                    },
                )
            }
        }
    }
}

@Composable
private fun rowMeta(row: BrowseSourceRow): String {
    val base = listOf(
        row.lang.uppercase(),
        row.ecosystem,
        row.generation,
    ).filter { it.isNotEmpty() }
    val meta = base.joinToString(" · ")
    return if (row.pinned) {
        stringResource(R.string.browse_row_meta_pinned, meta)
    } else {
        meta
    }
}
