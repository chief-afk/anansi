package com.chief.anansi.ui.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.R
import com.chief.anansi.data.backup.BackupImportResult

/**
 * Backup and migrate: the restore-from-LNReader row behind a
 * one-shot SAF file pick, the read-on-device promise, the parse spinner,
 * and the import-result dialog with its unmatched review. The
 * export row is deferred and ships dimmed.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.screen_backup),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            RestoreRow(
                enabled = !state.importing,
                onClick = { importPicker.launch(RESTORE_MIME_TYPES) },
            )
            Text(
                text = stringResource(R.string.backup_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
            if (state.importing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.backup_importing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            BacklogRow(
                title = stringResource(R.string.backup_export_title),
                supporting = stringResource(R.string.backup_export_body),
            )
        }
    }

    state.result?.let { result ->
        ImportResultDialog(
            result = result,
            fileName = state.resultFileName.orEmpty(),
            showUnmatched = state.showUnmatched,
            onToggleUnmatched = viewModel::setShowUnmatched,
            onDismiss = viewModel::dismissResult,
        )
    }
    state.failure?.let { reason ->
        AlertDialog(
            onDismissRequest = viewModel::failureShown,
            title = { Text(stringResource(R.string.backup_failed_title)) },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = viewModel::failureShown) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

private val RESTORE_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

@Composable
private fun RestoreRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.backup_restore_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.backup_restore_body),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BacklogRow(title: String, supporting: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(0.55f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.backlog_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** The result dialog: counts first, never-silent skips, unmatched review. */
@Composable
private fun ImportResultDialog(
    result: BackupImportResult,
    fileName: String,
    showUnmatched: Boolean,
    onToggleUnmatched: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (showUnmatched) {
        AlertDialog(
            onDismissRequest = { onToggleUnmatched(false) },
            title = { Text(stringResource(R.string.backup_unmatched_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.backup_unmatched_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    result.needsSource.forEach { entry ->
                        Text(
                            text = stringResource(
                                R.string.backup_unmatched_row,
                                entry.title,
                                entry.sourceName,
                                entry.v1SourceId,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onToggleUnmatched(false) }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_done_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(
                        R.string.backup_done_headline,
                        result.novelsRestored + result.novelsMerged,
                        fileName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = listOf(
                        stringResource(R.string.backup_done_chapters, result.chaptersInserted),
                        stringResource(R.string.backup_done_read_marks, result.readMarksApplied),
                        stringResource(R.string.backup_done_history, result.historyRestored),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.needsSource.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(
                            R.string.backup_done_needs_source,
                            result.needsSource.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.historyRestored > 0) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.backup_done_positions_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NeverSilentLines(result)
            }
        },
        confirmButton = {
            if (result.needsSource.isNotEmpty()) {
                TextButton(onClick = { onToggleUnmatched(true) }) {
                    Text(stringResource(R.string.backup_review_unmatched))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

/** The skip lines: categories, bookmarks, and every per-item failure reason. */
@Composable
private fun NeverSilentLines(result: BackupImportResult) {
    val lines = buildList {
        if (result.categoriesSkipped > 0) {
            add(stringResource(R.string.backup_skipped_categories, result.categoriesSkipped))
        }
        if (result.bookmarksSkipped > 0) {
            add(stringResource(R.string.backup_skipped_bookmarks, result.bookmarksSkipped))
        }
        result.skippedNovels.forEach { add("${it.label}: ${it.reason}") }
        result.skippedChapters.forEach { add("${it.label}: ${it.reason}") }
        result.skippedHistory.forEach { add("${it.label}: ${it.reason}") }
    }
    if (lines.isEmpty()) return
    Spacer(Modifier.size(8.dp))
    lines.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 1.dp),
        )
    }
}
