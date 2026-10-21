package com.chief.anansi.ui.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.chief.anansi.ui.components.NovelCover
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The Local source screen: the SAF document picker behind the
 * appbar import action, the on-device promise copy, and the imported-novels
 * list. Before the first import the list is simply empty. Novels open in the
 * same details screen as every other source.
 */
@Composable
fun LocalScreen(
    onBack: () -> Unit,
    onOpenNovel: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalViewModel = viewModel(factory = LocalViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris)
    }

    val outcomeMessage = state.outcome?.let { outcome ->
        buildString {
            if (outcome.imported > 0) {
                append(
                    pluralStringResource(
                        R.plurals.local_import_done,
                        outcome.imported,
                        outcome.imported,
                    ),
                )
            }
            outcome.failures.firstOrNull()?.let { failure ->
                if (isNotEmpty()) append("\n")
                append(failure)
                val more = outcome.failures.size - 1
                if (more > 0) {
                    append("\n")
                    append(stringResource(R.string.local_import_more, more))
                }
            }
        }
    }
    LaunchedEffect(outcomeMessage) {
        if (outcomeMessage != null) {
            snackbarHostState.showSnackbar(outcomeMessage)
            viewModel.outcomeShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LocalHeader(onBack = onBack, onImport = { importPicker.launch(IMPORT_MIME_TYPES) })
            Text(
                text = stringResource(R.string.local_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp),
            )
            if (state.importing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.local_importing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                !state.importing && state.novels.isEmpty() -> EmptyImportedState(
                    onImport = { importPicker.launch(IMPORT_MIME_TYPES) },
                )

                else -> ImportedNovelList(state = state, onOpenNovel = onOpenNovel)
            }
            Text(
                text = stringResource(R.string.local_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LocalHeader(onBack: () -> Unit, onImport: () -> Unit) {
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
            text = stringResource(R.string.screen_local),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onImport) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.cd_local_import),
            )
        }
    }
}

@Composable
private fun EmptyImportedState(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder),
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.local_empty_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = stringResource(R.string.local_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(onClick = onImport, modifier = Modifier.padding(top = 18.dp)) {
            Text(stringResource(R.string.local_import_action))
        }
    }
}

@Composable
private fun ImportedNovelList(state: LocalUiState, onOpenNovel: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
    ) {
        items(state.novels, key = { it.novelPath }) { row ->
            ImportedNovelRow(row = row, onOpen = { onOpenNovel(row.novelPath) })
        }
    }
}

@Composable
private fun ImportedNovelRow(row: LocalNovelRow, onOpen: () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        ) {
            NovelCover(
                title = row.title,
                coverUrl = row.coverUrl,
                modifier = Modifier
                    .width(46.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    row.author?.takeIf { it.isNotBlank() },
                    stringResource(R.string.details_chapter_count, row.chapterCount),
                ).joinToString(" · ")
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val importedOn = remember(row.addedAt) {
                    DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault())
                        .format(Date(row.addedAt))
                }
                Text(
                    text = stringResource(R.string.local_row_meta, row.format, importedOn),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

private val IMPORT_MIME_TYPES = arrayOf("application/epub+zip", "text/plain")
