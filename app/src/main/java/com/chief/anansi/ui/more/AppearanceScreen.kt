package com.chief.anansi.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chief.anansi.R
import com.chief.anansi.settings.SeedTheme
import com.chief.anansi.settings.ThemeMode
import com.chief.anansi.ui.SettingsUiState
import com.chief.anansi.ui.theme.swatchColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetSeedTheme: (SeedTheme) -> Unit,
    onSetPinnedTabsOnly: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
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
                text = stringResource(R.string.screen_appearance),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Label(stringResource(R.string.appearance_theme_mode_label))
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(
                selected = state.themeMode == ThemeMode.SYSTEM,
                label = stringResource(R.string.theme_mode_system),
                iconRes = null,
                onClick = { onSetThemeMode(ThemeMode.SYSTEM) },
            )
            ModeChip(
                selected = state.themeMode == ThemeMode.DARK,
                label = stringResource(R.string.theme_mode_dark),
                iconRes = R.drawable.ic_moon,
                onClick = { onSetThemeMode(ThemeMode.DARK) },
            )
            ModeChip(
                selected = state.themeMode == ThemeMode.LIGHT,
                label = stringResource(R.string.theme_mode_light),
                iconRes = R.drawable.ic_sun,
                onClick = { onSetThemeMode(ThemeMode.LIGHT) },
            )
        }
        Label(stringResource(R.string.appearance_seed_label))
        FlowRow(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SeedTheme.entries.forEach { seed ->
                SeedSwatch(
                    seed = seed,
                    selected = seed == state.seedTheme,
                    onClick = { onSetSeedTheme(seed) },
                )
            }
        }
        Text(
            text = stringResource(R.string.appearance_seed_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.appearance_pinned_tabs_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.appearance_pinned_tabs_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.pinnedTabsOnly,
                onCheckedChange = onSetPinnedTabsOnly,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 8.dp),
    )
}

@Composable
private fun ModeChip(selected: Boolean, label: String, iconRes: Int?, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            val res = if (selected) R.drawable.ic_check else iconRes
            if (res != null) {
                Icon(
                    painter = painterResource(res),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
            }
        },
    )
}

@Composable
private fun SeedSwatch(seed: SeedTheme, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(seed.swatchColor())
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
        )
        Text(
            text = seedLabel(seed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun seedLabel(seed: SeedTheme): String = stringResource(
    when (seed) {
        SeedTheme.VIOLET -> R.string.seed_violet
        SeedTheme.LAVENDER -> R.string.seed_lavender
        SeedTheme.MIDNIGHT_DUSK -> R.string.seed_midnight_dusk
        SeedTheme.STRAWBERRY -> R.string.seed_strawberry
        SeedTheme.TAKO -> R.string.seed_tako
        SeedTheme.TEAL_TURQUOISE -> R.string.seed_teal_turquoise
        SeedTheme.YOTSUBA -> R.string.seed_yotsuba
    },
)
