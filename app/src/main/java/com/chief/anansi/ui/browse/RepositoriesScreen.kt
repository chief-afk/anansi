package com.chief.anansi.ui.browse

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import com.chief.anansi.plugin.repo.RepoRegistrationService

/**
 * The repository manager: the add-repo card with the verified
 * manifest line and failed-entry reasons, the OPDS catalog card (
 * catalogs are added through this repository flow), the installed
 * repositories, and the neutrality copy. The URL rule is https-only with
 * the loopback exception; foreign manifests are probed and labeled at
 * install, never silently dropped.
 */
@Composable
fun RepositoriesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RepositoriesViewModel = viewModel(factory = RepositoriesViewModel.Factory),
) {
    val repos by viewModel.repos.collectAsStateWithLifecycle()
    val add by viewModel.add.collectAsStateWithLifecycle()
    val catalogAdd by viewModel.catalogAdd.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var removeCandidate by remember { mutableStateOf<String?>(null) }
    var removeNovelCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(removeCandidate) {
        removeNovelCount = removeCandidate?.let { viewModel.affectedNovelCount(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
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
                text = stringResource(R.string.screen_repositories),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        AddRepoCard(
            url = url,
            onUrlChange = { url = it },
            state = add,
            onAdd = { viewModel.addRepo(url) },
            onDismissResult = viewModel::dismissResult,
        )

        AddCatalogCard(
            state = catalogAdd,
            onAdd = viewModel::addCatalog,
            onDismissResult = viewModel::dismissCatalogResult,
        )

        if (repos.isEmpty()) {
            Text(
                text = stringResource(R.string.repo_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        } else {
            repos.forEach { repo ->
                RepoRow(
                    repo = repo,
                    onRemove = { removeCandidate = repo.repoUrl },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp, end = 16.dp))
            }
        }

        Text(
            text = stringResource(R.string.repo_neutrality),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        )
    }

    removeCandidate?.let { repoUrl ->
        val count = repos.firstOrNull { it.repoUrl == repoUrl }?.sourceCount ?: 0
        val novelCount = removeNovelCount
        AlertDialog(
            onDismissRequest = { removeCandidate = null },
            title = { Text(stringResource(R.string.repo_remove_confirm_title)) },
            text = {
                Text(
                    if (novelCount != null && novelCount > 0) {
                        stringResource(
                            R.string.repo_remove_confirm_body_with_novels,
                            count,
                            novelCount,
                        )
                    } else {
                        stringResource(R.string.repo_remove_confirm_body, count)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removeCandidate = null
                    viewModel.removeRepo(repoUrl)
                }) {
                    Text(stringResource(R.string.repo_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeCandidate = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun AddRepoCard(
    url: String,
    onUrlChange: (String) -> Unit,
    state: RepoAddUiState,
    onAdd: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.repo_url_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.repo_url_hint)) },
            singleLine = true,
            enabled = !state.working,
        )
        Button(
            onClick = onAdd,
            enabled = !state.working && url.isNotBlank(),
        ) {
            Text(stringResource(R.string.repo_add_action))
        }
        if (state.working) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.repo_add_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { reason ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.repo_add_failed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        state.report?.let { report ->
            VerifiedResult(report = report, onDismiss = onDismissResult)
        }
    }
}

@Composable
private fun AddCatalogCard(
    state: CatalogAddUiState,
    onAdd: (String) -> Unit,
    onDismissResult: () -> Unit,
) {
    var catalogUrl by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.opds_url_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = catalogUrl,
            onValueChange = { catalogUrl = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.opds_url_hint)) },
            singleLine = true,
            enabled = !state.working,
        )
        Button(
            onClick = { onAdd(catalogUrl) },
            enabled = !state.working && catalogUrl.isNotBlank(),
        ) {
            Text(stringResource(R.string.opds_add_action))
        }
        if (state.working) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.opds_add_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { reason ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.opds_add_failed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        state.added?.let { added ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.opds_verified_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = stringResource(R.string.opds_verified_line, added.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (added.created) {
                        stringResource(R.string.repo_line_added, 1)
                    } else {
                        stringResource(R.string.opds_already_added)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismissResult, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.repo_result_dismiss))
                }
            }
        }
    }
}

@Composable
private fun VerifiedResult(
    report: RepoRegistrationService.RepoAddReport,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.repo_verified_title),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            text = verifiedLine(report),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (report.failures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.repo_result_failures_title, report.failures.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            report.failures.take(MAX_SHOWN_FAILURES).forEach { failure ->
                Text(
                    text = stringResource(
                        R.string.repo_result_failure_line,
                        failure.pluginId,
                        failure.reason,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (report.failures.size > MAX_SHOWN_FAILURES) {
                Text(
                    text = stringResource(
                        R.string.repo_result_failures_more,
                        report.failures.size - MAX_SHOWN_FAILURES,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onDismiss, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.repo_result_dismiss))
        }
    }
}

@Composable
private fun verifiedLine(report: RepoRegistrationService.RepoAddReport): String {
    val parts = buildList {
        report.apiVersion?.let { add(stringResource(R.string.repo_line_api, it)) }
        add(stringResource(R.string.repo_line_plugins, report.created + report.updated))
        if (report.created > 0) add(stringResource(R.string.repo_line_added, report.created))
        if (report.updated > 0) add(stringResource(R.string.repo_line_updated, report.updated))
        if (report.removed > 0) add(stringResource(R.string.repo_line_removed, report.removed))
    }
    val generations = report.generationCounts.entries
        .sortedBy { it.key }
        .joinToString(" · ") { (generation, count) -> "$generation: $count" }
    val lines = if (generations.isEmpty()) parts else parts + generations
    return lines.joinToString(" · ")
}

@Composable
private fun RepoRow(
    repo: RepoRowUi,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_spider),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = repo.host,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = repoMeta(repo),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.repo_remove),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.repo_remove)) },
                    onClick = {
                        menuExpanded = false
                        onRemove()
                    },
                )
            }
        }
    }
}

@Composable
private fun repoMeta(repo: RepoRowUi): String {
    val sourcesText = stringResource(R.string.repo_meta_sources, repo.sourceCount)
    val generationParts = repo.generationCounts.entries
        .sortedBy { it.key }
        .map { (generation, count) -> "$generation: $count" }
    return (listOf(sourcesText, repo.ecosystem) + generationParts)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}

private const val MAX_SHOWN_FAILURES = 8
