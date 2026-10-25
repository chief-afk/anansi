package com.chief.anansi.ui.reader

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chief.anansi.R
import com.chief.anansi.audio.TtsPlaybackState
import com.chief.anansi.audio.TtsSettings
import com.chief.anansi.ui.LocalTtsController
import kotlinx.coroutines.launch

/**
 * The first-play permission gate (board permissions note): the
 * POST_NOTIFICATIONS runtime ask happens once, on first play; playback
 * proceeds without the notification when the ask is denied.
 */
@Composable
fun rememberTtsStart(onReady: () -> Unit): () -> Unit {
    val controller = LocalTtsController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestReady = rememberUpdatedState(onReady)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { latestReady.value() }
    return {
        scope.launch {
            val needsAsk =
                android.os.Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    !controller.settingsStore.wasNotificationAsked()
            if (needsAsk) {
                controller.settingsStore.markNotificationAsked()
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                latestReady.value()
            }
        }
    }
}

/**
 * The docked audio mini-bar: sentence-skip taps, play/pause,
 * the sentence position with a thin progress line, and the expand control
 * that opens the drawer. It replaces the reader footer while a session is
 * active.
 */
@Composable
fun TtsMiniBar(
    state: TtsPlaybackState,
    onToggle: () -> Unit,
    onSkipSentence: (Boolean) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSkipSentence(false) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = stringResource(R.string.cd_tts_previous_sentence),
                )
            }
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    painter = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(
                        if (state.isPlaying) R.string.cd_tts_pause else R.string.cd_tts_play,
                    ),
                )
            }
            IconButton(onClick = { onSkipSentence(true) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.cd_tts_next_sentence),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            ) {
                Text(
                    text = if (state.preparing) {
                        stringResource(R.string.tts_fetching_next)
                    } else {
                        stringResource(
                            R.string.tts_sentence_position,
                            state.sentenceIndex + 1,
                            state.sentenceCount,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = {
                        if (state.sentenceCount > 0) {
                            (state.sentenceIndex + 1) / state.sentenceCount.toFloat()
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            IconButton(onClick = onExpand) {
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = stringResource(R.string.cd_tts_expand),
                )
            }
        }
    }
}

/**
 * The audio reader sheet: chapter-level seek anchored to
 * sentence position with "Sentence X of Y" labels, sentence/paragraph
 * transport (tap and long-press), speed chips writing the platform rate,
 * the auto-play and disconnect switches, and the system voice row that
 * deep-links the platform TTS settings.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TtsDrawer(onDismiss: () -> Unit) {
    val controller = LocalTtsController.current
    val state by controller.state.collectAsStateWithLifecycle()
    val settings by remember { controller.settingsStore.settings }
        .collectAsStateWithLifecycle(TtsSettings())
    val scope = rememberCoroutineScope()

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
                text = stringResource(R.string.tts_drawer_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = state.novelTitle.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.chapter?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            val sliderValue = remember(state.chapter?.rowId) {
                mutableFloatStateOf((state.sentenceIndex + 1).toFloat())
            }
            LaunchedEffect(state.sentenceIndex, state.chapter?.rowId) {
                sliderValue.floatValue = (state.sentenceIndex + 1).toFloat()
            }
            Slider(
                value = sliderValue.floatValue,
                onValueChange = { sliderValue.floatValue = it },
                onValueChangeFinished = {
                    controller.seekToSentence(sliderValue.floatValue.toInt() - 1)
                },
                valueRange = 1f..state.sentenceCount.coerceAtLeast(1).toFloat(),
                modifier = Modifier.padding(horizontal = 20.dp),
                enabled = state.sentenceCount > 0 && !state.preparing,
            )
            Text(
                text = if (state.preparing) {
                    stringResource(R.string.tts_fetching_next)
                } else {
                    stringResource(
                        R.string.tts_sentence_position,
                        state.sentenceIndex + 1,
                        state.sentenceCount,
                    ) + " · " + stringResource(R.string.tts_transport_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )

            val failure = state.failure
            if (failure != null) {
                TtsFailureBanner(
                    kind = failure.kind,
                    reason = failure.reason,
                    onRetry = controller::retryFetch,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    iconRes = R.drawable.ic_skip_previous,
                    contentDescription = stringResource(R.string.cd_tts_previous_sentence),
                    onClick = { controller.skipSentence(false) },
                    onLongClick = { controller.skipParagraph(false) },
                )
                Spacer(Modifier.width(18.dp))
                FilledIconButton(
                    onClick = controller::togglePlayPause,
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        painter = painterResource(
                            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.cd_tts_pause else R.string.cd_tts_play,
                        ),
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(18.dp))
                TransportButton(
                    iconRes = R.drawable.ic_skip_next,
                    contentDescription = stringResource(R.string.cd_tts_next_sentence),
                    onClick = { controller.skipSentence(true) },
                    onLongClick = { controller.skipParagraph(true) },
                )
            }

            Text(
                text = stringResource(R.string.tts_speed_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                SpeedChip(label = stringResource(R.string.tts_speed_075), rate = 0.75f, current = settings.defaultSpeed) {
                    controller.setSpeed(it)
                }
                SpeedChip(label = stringResource(R.string.tts_speed_1), rate = 1f, current = settings.defaultSpeed) {
                    controller.setSpeed(it)
                }
                SpeedChip(label = stringResource(R.string.tts_speed_125), rate = 1.25f, current = settings.defaultSpeed) {
                    controller.setSpeed(it)
                }
                SpeedChip(label = stringResource(R.string.tts_speed_15), rate = 1.5f, current = settings.defaultSpeed) {
                    controller.setSpeed(it)
                }
                SpeedChip(label = stringResource(R.string.tts_speed_2), rate = 2f, current = settings.defaultSpeed) {
                    controller.setSpeed(it)
                }
            }

            SwitchRow(
                title = stringResource(R.string.tts_auto_play_next),
                caption = stringResource(R.string.tts_auto_play_next_caption),
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

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .combinedClickable(onClick = { openTtsSettings(context) })
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
                        text = stringResource(R.string.tts_system_voice),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.tts_voice_caption,
                            remember { defaultEngineName(context) },
                        ),
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
        }
    }
}

/** The retained next-chapter fetch failure: registry kind, retry action. */
@Composable
private fun TtsFailureBanner(kind: String, reason: String, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 6.dp)) {
            Text(
                text = stringResource(R.string.tts_fetch_failed_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "$kind: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = stringResource(R.string.manage_error_posture),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun TransportButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun SpeedChip(label: String, rate: Float, current: Float, onClick: (Float) -> Unit) {
    FilterChip(
        selected = (current - rate).let { it > -0.01f && it < 0.01f },
        onClick = { onClick(rate) },
        label = { Text(label) },
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    caption: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun defaultEngineName(context: Context): String = runCatching {
    android.provider.Settings.Secure.getString(
        context.contentResolver,
        "tts_default_synth",
    )
}.getOrNull()?.substringBefore('.')?.replaceFirstChar { it.uppercase() } ?: ""

private fun openTtsSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent("com.android.settings.TTS_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
