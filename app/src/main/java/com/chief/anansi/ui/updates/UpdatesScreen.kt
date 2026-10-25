package com.chief.anansi.ui.updates

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.R
import com.chief.anansi.ui.LocalTtsController
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.NovelCover
import com.chief.anansi.ui.components.ScreenTitle
import com.chief.anansi.ui.reader.rememberTtsStart
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The chapter a row's play affordance opens and starts speaking. */
private data class PlayTarget(
    val identity: String,
    val novelPath: String,
    val chapterPath: String,
)

/**
 * Updates: the honest manual-only surface. The appbar refresh
 * checks every library novel whose source is enabled; rows group by the
 * novel's last-checked day and show the new-arrival count, the caught-up
 * state, or the failed check's registry reason. Row tap-through opens the
 * novel's details; the play affordance opens the newest new chapter in the
 * reader with playback running. No automatic checking exists anywhere.
 */
@Composable
fun UpdatesScreen(
    onOpenNovel: (identity: String, novelPath: String) -> Unit,
    onPlayNewest: (identity: String, novelPath: String, chapterPath: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpdatesViewModel = viewModel(factory = UpdatesViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val summaryText = summary?.let {
        stringResource(
            R.string.updates_summary,
            it.updated,
            it.caughtUp,
            it.failed,
            it.skipped,
        )
    }
    LaunchedEffect(summaryText) {
        if (summaryText != null) {
            snackbarHostState.showSnackbar(summaryText)
            viewModel.summaryShown()
        }
    }

    // The play affordance rides the reader's own start gate (the audio reader's
    // notification permission ask) and speaks the newest new chapter.
    val ttsController = LocalTtsController.current
    var pendingPlay by remember { mutableStateOf<PlayTarget?>(null) }
    val startPlayback = rememberTtsStart {
        pendingPlay?.let { target ->
            ttsController.start(target.identity, target.novelPath, target.chapterPath)
            onPlayNewest(target.identity, target.novelPath, target.chapterPath)
        }
        pendingPlay = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.tab_updates), actions = {
                if (state.checkingAll) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = viewModel::checkAll) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.cd_updates_check_now),
                        )
                    }
                }
            })
            Text(
                text = stringResource(R.string.updates_manual_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 4.dp),
            )
            val rows = state.rows
            when {
                rows == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                rows.isEmpty() -> EmptyState(
                    icon = painterResource(R.drawable.ic_check),
                    title = stringResource(R.string.updates_empty_title),
                )

                else -> UpdatesList(
                    rows = rows,
                    onOpen = onOpenNovel,
                    onPlay = { identity, novelPath, chapterPath ->
                        pendingPlay = PlayTarget(identity, novelPath, chapterPath)
                        startPlayback()
                    },
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private data class UpdateDayGroup(val label: String, val rows: List<UpdateRowUi>)

@Composable
private fun UpdatesList(
    rows: List<UpdateRowUi>,
    onOpen: (String, String) -> Unit,
    onPlay: (String, String, String) -> Unit,
) {
    val today = LocalDate.now()
    val groups = groupByCheckedDay(rows, today)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        groups.forEach { group ->
            item(key = "header-${group.label}") {
                Text(
                    text = group.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(group.rows, key = { it.row.novel.id }) { ui ->
                UpdateItem(ui = ui, onOpen = onOpen, onPlay = onPlay)
            }
        }
    }
}

@Composable
private fun UpdateItem(
    ui: UpdateRowUi,
    onOpen: (String, String) -> Unit,
    onPlay: (String, String, String) -> Unit,
) {
    val row = ui.row
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(row.sourceIdentity, row.novel.sourceNovelId) }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        NovelCover(
            title = row.novel.title,
            coverUrl = row.novel.coverUrl,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = row.novel.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val failure = ui.failureReason
            Text(
                text = statusLine(ui),
                style = MaterialTheme.typography.bodySmall,
                color = if (failure != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLine(ui),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            ui.checking -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(20.dp),
                strokeWidth = 2.dp,
            )

            row.newCount > 0 && row.newestNewUrl != null -> IconButton(
                onClick = { onPlay(row.sourceIdentity, row.novel.sourceNovelId, row.newestNewUrl) },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = stringResource(R.string.cd_updates_play_newest),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The row's check outcome: the failure, the arrivals, caught up, or never checked. */
@Composable
private fun statusLine(ui: UpdateRowUi): String {
    ui.failureReason?.let { reason ->
        val kind = ui.failureKind
        return if (kind.isNullOrEmpty()) reason else "$kind: $reason"
    }
    return when {
        ui.row.novel.lastCheckedAtEpochMs == 0L -> stringResource(R.string.updates_row_not_checked)
        ui.row.newCount > 0 -> pluralStringResource(
            R.plurals.updates_row_new_chapters,
            ui.row.newCount,
            ui.row.newCount,
            ui.row.newestNewTitle.orEmpty(),
        )

        else -> stringResource(R.string.updates_row_caught_up)
    }
}

@Composable
private fun metaLine(ui: UpdateRowUi): String {
    val checkedAt = ui.row.novel.lastCheckedAtEpochMs
    return if (checkedAt == 0L) {
        ui.row.sourceName
    } else {
        stringResource(
            R.string.updates_row_meta,
            ui.row.sourceName,
            stringResource(R.string.updates_row_checked_at, relativeCheckedTime(checkedAt)),
        )
    }
}

private fun relativeCheckedTime(checkedAt: Long): String =
    DateUtils
        .getRelativeTimeSpanString(
            checkedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()

/** Day groups by the novel's last-checked time; never-checked rows sort last. */
@Composable
private fun groupByCheckedDay(rows: List<UpdateRowUi>, today: LocalDate): List<UpdateDayGroup> {
    val groups = LinkedHashMap<String, UpdateDayGroup>()
    for (ui in rows) {
        val label = dayLabel(ui.row.novel.lastCheckedAtEpochMs, today)
        groups[label] = UpdateDayGroup(
            label = label,
            rows = groups[label]?.rows?.plus(ui) ?: listOf(ui),
        )
    }
    return groups.values.toList()
}

@Composable
private fun dayLabel(checkedAt: Long, today: LocalDate): String = when {
    checkedAt == 0L -> stringResource(R.string.updates_not_checked_header)
    else -> {
        val date = Instant.ofEpochMilli(checkedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        when (date) {
            today -> stringResource(R.string.history_today)
            today.minusDays(1) -> stringResource(R.string.history_yesterday)
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }
}
