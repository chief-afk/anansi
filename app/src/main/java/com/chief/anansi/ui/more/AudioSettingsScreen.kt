package com.chief.anansi.ui.more

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chief.anansi.R
import com.chief.anansi.audio.TtsSettings
import com.chief.anansi.ui.LocalTtsController
import com.chief.anansi.ui.reader.SpeedChip
import com.chief.anansi.ui.reader.SwitchRow
import kotlinx.coroutines.launch

/**
 * Audio reader settings (More tab): the engine row deep-links
 * the platform engine picker, and every control is a platform primitive
 * (rate, pitch) or a queue behavior, persisted under the tts namespace.
 * Nothing is bundled: the note names the posture.
 */
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = LocalTtsController.current
    val settings by remember { controller.settingsStore.settings }
        .collectAsStateWithLifecycle(TtsSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                text = stringResource(R.string.screen_audio_reader),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        SectionLabel(stringResource(R.string.tts_engine_label))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable { openPlatformTtsSettings(context) }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_headphones),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = remember { defaultEngineName(context) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.tts_engine_row_caption),
                    style = MaterialTheme.typography.bodySmall,
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

        SectionLabel(stringResource(R.string.tts_default_speed_label))
        val setSpeed: (Float) -> Unit = { rate ->
            scope.launch { controller.settingsStore.setDefaultSpeed(rate) }
            controller.setSpeed(rate)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SpeedChip(stringResource(R.string.tts_speed_075), 0.75f, settings.defaultSpeed, setSpeed)
            SpeedChip(stringResource(R.string.tts_speed_1), 1f, settings.defaultSpeed, setSpeed)
            SpeedChip(stringResource(R.string.tts_speed_125), 1.25f, settings.defaultSpeed, setSpeed)
            SpeedChip(stringResource(R.string.tts_speed_15), 1.5f, settings.defaultSpeed, setSpeed)
            SpeedChip(stringResource(R.string.tts_speed_2), 2f, settings.defaultSpeed, setSpeed)
        }

        SectionLabel(stringResource(R.string.tts_pitch_label))
        val pitchState = remember { mutableFloatStateOf(settings.pitch) }
        LaunchedEffect(settings.pitch) { pitchState.floatValue = settings.pitch }
        Slider(
            value = pitchState.floatValue,
            onValueChange = { pitchState.floatValue = it },
            valueRange = TtsSettings.PITCH_MIN..TtsSettings.PITCH_MAX,
            onValueChangeFinished = {
                scope.launch { controller.settingsStore.setPitch(pitchState.floatValue) }
            },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            text = stringResource(R.string.tts_pitch_value, pitchState.floatValue),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        SwitchRow(
            title = stringResource(R.string.tts_auto_play_next),
            caption = null,
            checked = settings.autoPlayNext,
            onCheckedChange = { checked ->
                scope.launch { controller.settingsStore.setAutoPlayNext(checked) }
            },
        )
        SwitchRow(
            title = stringResource(R.string.tts_pause_on_disconnect),
            caption = null,
            checked = settings.pauseOnDisconnect,
            onCheckedChange = { checked ->
                scope.launch { controller.settingsStore.setPauseOnDisconnect(checked) }
            },
        )
        SwitchRow(
            title = stringResource(R.string.tts_announce_chapter_start),
            caption = stringResource(R.string.tts_announce_caption),
            checked = settings.announceChapterStart,
            onCheckedChange = { checked ->
                scope.launch { controller.settingsStore.setAnnounceChapterStart(checked) }
            },
        )
        Text(
            text = stringResource(R.string.tts_posture_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

private fun defaultEngineName(context: Context): String = runCatching {
    android.provider.Settings.Secure.getString(
        context.contentResolver,
        "tts_default_synth",
    )
}.getOrNull()?.substringBefore('.')?.replaceFirstChar { it.uppercase() } ?: ""

private fun openPlatformTtsSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent("com.android.settings.TTS_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
