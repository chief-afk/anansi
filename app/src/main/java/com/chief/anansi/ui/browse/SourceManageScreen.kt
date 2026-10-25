package com.chief.anansi.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.R
import com.chief.anansi.plugin.repo.SourcePermission

/**
 * One source's management surface: enable/disable,
 * pinning, the permission declarations display, the last recorded error
 * line rendered display-only from the registry (never reclassified), and
 * remove. Requires-JS and incompatible states arrive through the same
 * error line with their reasons.
 */
@Composable
fun SourceManageScreen(
    identity: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourceManageViewModel = viewModel(
        key = identity,
        factory = SourceManageViewModel.factory(identity),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var removeNovelCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(showRemoveConfirm) {
        removeNovelCount = if (showRemoveConfirm) viewModel.affectedNovelCount() else null
    }
    val source = state.source

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
                text = source?.displayName ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }

        if (source != null) {
            ToggleRow(
                title = stringResource(R.string.manage_enabled_label),
                caption = stringResource(R.string.manage_enabled_caption),
                checked = source.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp, end = 16.dp))
            ToggleRow(
                title = stringResource(R.string.manage_pinned_label),
                caption = stringResource(R.string.manage_pinned_caption),
                checked = source.pinned,
                onCheckedChange = viewModel::setPinned,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp, end = 16.dp))

            SectionLabel(stringResource(R.string.manage_permissions_label))
            state.permissions.forEach { permission ->
                PermissionRow(permission = permission)
            }
            Text(
                text = stringResource(R.string.manage_permission_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
            )

            state.error?.let { error ->
                SectionLabel(stringResource(R.string.manage_error_label))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = error.kind,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = error.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    val ageDays = remember(error.updatedAt) { errorAgeDays(error.updatedAt) }
                    Text(
                        text = pluralStringResource(R.plurals.manage_error_age_days, ageDays, ageDays),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    text = stringResource(R.string.manage_error_posture),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
                )
            }

            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { showRemoveConfirm = true },
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(R.string.manage_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showRemoveConfirm && source != null) {
        val novelCount = removeNovelCount
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.manage_remove_confirm_title)) },
            text = {
                Text(
                    if (novelCount != null && novelCount > 0) {
                        stringResource(
                            R.string.manage_remove_confirm_body_with_novels,
                            source.displayName,
                            novelCount,
                        )
                    } else {
                        stringResource(R.string.manage_remove_confirm_body, source.displayName)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    viewModel.remove(onRemoved = onBack)
                }) {
                    Text(stringResource(R.string.manage_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** Whole days since the recorded error, rounding any partial day up to 1. */
private fun errorAgeDays(updatedAt: Long): Int {
    val elapsed = System.currentTimeMillis() - updatedAt
    return ((elapsed + DAY_MILLIS - 1) / DAY_MILLIS).toInt().coerceAtLeast(1)
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

@Composable
private fun ToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun PermissionRow(permissionName: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text = permissionName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(permission: SourcePermission) {
    val (name, description) = when (permission) {
        SourcePermission.NETWORK ->
            stringResource(R.string.manage_permission_network_name) to
                stringResource(R.string.manage_permission_network_body)
        SourcePermission.STORAGE ->
            stringResource(R.string.manage_permission_storage_name) to
                stringResource(R.string.manage_permission_storage_body)
        SourcePermission.WEBVIEW ->
            stringResource(R.string.manage_permission_webview_name) to
                stringResource(R.string.manage_permission_webview_body)
    }
    PermissionRow(permissionName = name, description = description)
}
