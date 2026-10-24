package com.chief.anansi.ui.history

import android.text.format.DateUtils
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.chief.anansi.data.dao.HistoryEntry
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.NovelCover
import com.chief.anansi.ui.components.ScreenTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen(
    onOpenNovel: (identity: String, novelPath: String, chapterPath: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val history by viewModel.history.collectAsStateWithLifecycle(initialValue = null)
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tab_history), actions = {
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.history_clear),
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history_clear)) },
                        onClick = {
                            expanded = false
                            showClearConfirm = true
                        },
                    )
                }
            }
        })
        val current = history
        when {
            // Before the database has spoken its first word.
            current == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            current.isEmpty() -> EmptyState(
                icon = painterResource(R.drawable.ic_tab_history),
                title = stringResource(R.string.history_empty_title),
            )

            else -> HistoryList(
                entries = current,
                onRemove = viewModel::removeEntry,
                onOpen = { entry ->
                    viewModel.openEntry(entry) {
                        onOpenNovel(entry.sourceIdentity, entry.novel.url, entry.chapter.chapterUrl)
                    }
                },
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.history_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearAll()
                }) {
                    Text(stringResource(R.string.history_clear_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    onRemove: (Long) -> Unit,
    onOpen: (HistoryEntry) -> Unit,
) {
    val today = LocalDate.now()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        groupByDay(entries, today).forEach { group ->
            item(key = "header-${group.date}") {
                Text(
                    text = groupLabel(group.date, today),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(group.entries, key = { it.progress.novelId }) { entry ->
                HistoryRow(entry = entry, onRemove = onRemove, onOpen = { onOpen(entry) })
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onRemove: (Long) -> Unit,
    onOpen: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The whole row opens the reader at the recorded chapter.
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        NovelCover(
            title = entry.novel.title,
            coverUrl = entry.novel.coverUrl,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = entry.novel.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.chapter.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.history_row_meta,
                    recencyText(entry.progress.updatedAt),
                    progressText(entry.progress.percent),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.history_remove),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_remove)) },
                    onClick = {
                        menuExpanded = false
                        onRemove(entry.progress.novelId)
                    },
                )
            }
        }
    }
}

@Composable
private fun progressText(percent: Int): String =
    if (percent >= 100) {
        stringResource(R.string.history_finished)
    } else {
        stringResource(R.string.history_percent, percent)
    }

private fun recencyText(updatedAt: Long): String =
    DateUtils
        .getRelativeTimeSpanString(
            updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()

private data class HistoryGroup(val date: LocalDate, val entries: List<HistoryEntry>)

private fun groupByDay(entries: List<HistoryEntry>, today: LocalDate): List<HistoryGroup> =
    entries
        .groupBy { Instant.ofEpochMilli(it.progress.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
        .entries
        .sortedByDescending { it.key }
        .map { HistoryGroup(it.key, it.value) }

@Composable
private fun groupLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.history_today)
    today.minusDays(1) -> stringResource(R.string.history_yesterday)
    else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}
