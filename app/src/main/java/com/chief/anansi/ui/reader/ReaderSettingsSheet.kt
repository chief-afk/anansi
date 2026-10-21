package com.chief.anansi.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chief.anansi.R
import kotlin.math.roundToInt

/**
 * Reader settings: theme presets, text size, line spacing, and
 * typeface chips plus the two reading switches. Every control persists
 * immediately and the open reader updates live behind the sheet (live
 * preview). The Black (OLED) chip renders dimmed with its BACKLOG tag and is
 * inert; the sheet says so in plain words at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onDismiss: () -> Unit,
    onSetTheme: (ReaderTheme) -> Unit,
    onSetTextSize: (Int) -> Unit,
    onSetLineSpacing: (Float) -> Unit,
    onSetTypeface: (ReaderTypeface) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetVolumeScroll: (Boolean) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            SheetLabel(stringResource(R.string.reader_theme_label))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                SheetChip(
                    label = stringResource(R.string.reader_theme_dark),
                    selected = settings.theme == ReaderTheme.DARK,
                    onClick = { onSetTheme(ReaderTheme.DARK) },
                )
                SheetChip(
                    label = stringResource(R.string.reader_theme_slate),
                    selected = settings.theme == ReaderTheme.SLATE,
                    onClick = { onSetTheme(ReaderTheme.SLATE) },
                )
                SheetChip(
                    label = stringResource(R.string.reader_theme_paper),
                    selected = settings.theme == ReaderTheme.PAPER,
                    onClick = { onSetTheme(ReaderTheme.PAPER) },
                )
                // The OLED chip ships dimmed and inert, exactly
                // where the design places it.
                FilterChip(
                    selected = false,
                    onClick = {},
                    enabled = false,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.reader_theme_black))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.backlog_badge),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }

            SheetLabel(stringResource(R.string.reader_text_size_label))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Text(
                    text = stringResource(R.string.reader_text_size_aa),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.textSizeSp.toFloat(),
                    onValueChange = { onSetTextSize(it.roundToInt()) },
                    valueRange = 13f..25f,
                    steps = 11,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                Text(
                    text = settings.textSizeSp.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            SheetLabel(stringResource(R.string.reader_line_spacing_label))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Text(
                    text = stringResource(R.string.reader_line_spacing_aa),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.lineSpacing,
                    onValueChange = { onSetLineSpacing((it * 10).roundToInt() / 10f) },
                    valueRange = 1f..2.5f,
                    steps = 14,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                Text(
                    text = stringResource(R.string.reader_line_spacing_value, settings.lineSpacing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            SheetLabel(stringResource(R.string.reader_typeface_label))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                SheetChip(
                    label = stringResource(R.string.reader_typeface_system),
                    selected = settings.typeface == ReaderTypeface.SYSTEM,
                    onClick = { onSetTypeface(ReaderTypeface.SYSTEM) },
                )
                SheetChip(
                    label = stringResource(R.string.reader_typeface_serif),
                    selected = settings.typeface == ReaderTypeface.SERIF,
                    onClick = { onSetTypeface(ReaderTypeface.SERIF) },
                )
                SheetChip(
                    label = stringResource(R.string.reader_typeface_rounded),
                    selected = settings.typeface == ReaderTypeface.ROUNDED,
                    onClick = { onSetTypeface(ReaderTypeface.ROUNDED) },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            SwitchRow(
                label = stringResource(R.string.reader_keep_screen_on),
                checked = settings.keepScreenOn,
                onCheckedChange = onSetKeepScreenOn,
            )
            SwitchRow(
                label = stringResource(R.string.reader_volume_keys_scroll),
                checked = settings.volumeKeysScroll,
                onCheckedChange = onSetVolumeScroll,
            )

            Text(
                text = stringResource(R.string.reader_backlog_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 6.dp),
    )
}

@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
