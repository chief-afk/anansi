package com.chief.anansi.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.R
import com.chief.anansi.audio.ChapterLayout
import com.chief.anansi.audio.SentenceSplitter
import com.chief.anansi.audio.TtsPlaybackState
import com.chief.anansi.ui.LocalTtsController
import com.chief.anansi.ui.components.EmptyState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private const val VOLUME_PAGE_FRACTION = 0.85f

/**
 * The scroll-mode text reader: the reading surface carries the
 * reader theme preset, chrome toggles on tap, and the footer navigates
 * previous/next chapters. Progress and settings follow the view model.
 */
@Composable
fun ReaderScreen(
    identity: String,
    novelPath: String,
    chapterPath: String,
    onBack: () -> Unit,
    onSwitchChapter: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = viewModel(
        key = "$identity::$novelPath::$chapterPath",
        factory = ReaderViewModel.factory(identity, novelPath, chapterPath),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val theme = settings.theme

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showTtsDrawer by rememberSaveable { mutableStateOf(false) }

    // Keep screen on rides the decor view's flag; the reader clears it on
    // exit so the setting never outlives the reading surface.
    val view = LocalView.current
    LaunchedEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
    }

    // Definitive progress writes: reader exit and app background.
    DisposableEffect(Unit) {
        onDispose {
            view.keepScreenOn = false
            viewModel.flushProgress()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.flushProgress()
    }

    val scrollState = rememberScrollState()
    var contentLaidOut by remember { mutableStateOf(false) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val failure = state.failure

    // Audio reader state: the sentence layout runs over the same
    // cached text the service speaks, so indexes align; a paragraph-count
    // mismatch disables the highlight instead of misplacing it.
    val ttsController = LocalTtsController.current
    val ttsState by ttsController.state.collectAsStateWithLifecycle()
    val sessionHere = ttsState.takeIf {
        it.isActive && it.identity == identity && it.novelPath == novelPath &&
            it.chapter?.path == chapterPath
    }
    val sessionHereState = rememberUpdatedState(sessionHere)
    val chapterLayout = remember(state.chapterText) {
        state.chapterText.takeIf { it.isNotEmpty() }?.let(SentenceSplitter::layout)
    }
    val highlightUsable = chapterLayout != null &&
        chapterLayout.paragraphs.size == state.paragraphs.size
    val activeSentenceIndex = sessionHere?.sentenceIndex?.takeIf { highlightUsable }
    val paragraphCoordinates = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
    val paragraphLayouts = remember { mutableStateMapOf<Int, TextLayoutResult>() }
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val startTtsHere = rememberTtsStart {
        if (sessionHereState.value != null) {
            ttsController.togglePlayPause()
        } else {
            ttsController.start(identity, novelPath, chapterPath)
        }
    }

    // Reader navigation while audio is active moves the queue to the chapter
    // the reader opened; the session's own auto-advance never yanks the
    // queue back (this effect keys on the reader's chapter, not the session).
    LaunchedEffect(state.hasText, chapterPath) {
        if (!state.hasText) return@LaunchedEffect
        val active = ttsState
        if (active.isActive && !active.preparing &&
            active.identity == identity && active.novelPath == novelPath &&
            active.chapter != null && active.chapter.path != chapterPath
        ) {
            ttsController.start(identity, novelPath, chapterPath)
        }
    }

    // Restore runs once per chapter once the text is measured; after it, the
    // scroll feed records user-driven positions only (the restored value's
    // own emission is dropped). With a session on this chapter, the spoken
    // sentence is the position to restore (notification tap returns to it).
    LaunchedEffect(state.hasText, contentLaidOut) {
        if (!state.hasText || !contentLaidOut) return@LaunchedEffect
        val restoreToSentence = sessionHereState.value != null && highlightUsable
        if (restoreToSentence) {
            sentenceTargetY(
                layout = chapterLayout ?: return@LaunchedEffect,
                index = sessionHereState.value?.sentenceIndex ?: return@LaunchedEffect,
                paragraphCoordinates = paragraphCoordinates,
                paragraphLayouts = paragraphLayouts,
                contentCoordinates = contentCoordinates ?: return@LaunchedEffect,
            )?.let { (top, _) ->
                scrollState.scrollTo((top - viewportHeightPx * 0.35f).toInt().coerceIn(0, scrollState.maxValue))
            }
        } else if (state.initialOffsetPx > 0) {
            scrollState.scrollTo(state.initialOffsetPx.coerceAtMost(scrollState.maxValue))
        } else if (scrollState.maxValue == 0) {
            // A chapter fully visible without scrolling is finished.
            viewModel.recordScroll(0, ReaderViewModel.PERCENT_FINISHED)
        }
        snapshotFlow { scrollState.value }
            .drop(1)
            .collect { offset ->
                viewModel.recordScroll(offset, percentOf(offset, scrollState.maxValue))
            }
    }

    // Reading along: the active sentence stays visible while attached.
    LaunchedEffect(activeSentenceIndex, state.hasText, contentLaidOut) {
        if (!state.hasText || !contentLaidOut) return@LaunchedEffect
        val index = activeSentenceIndex ?: return@LaunchedEffect
        val layout = chapterLayout ?: return@LaunchedEffect
        val target = sentenceTargetY(
            layout = layout,
            index = index,
            paragraphCoordinates = paragraphCoordinates,
            paragraphLayouts = paragraphLayouts,
            contentCoordinates = contentCoordinates ?: return@LaunchedEffect,
        ) ?: return@LaunchedEffect
        val (top, bottom) = target
        val viewTop = scrollState.value
        val viewBottom = viewTop + viewportHeightPx
        if (top < viewTop || bottom > viewBottom) {
            scrollState.animateScrollTo(
                (top - viewportHeightPx * 0.35f).toInt().coerceIn(0, scrollState.maxValue),
            )
        }
    }

    // Volume-key scrolling intercepts the keys before the system adjusts
    // volume; it needs a focused node for the keys to reach composition.
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val volumeKeyHandler = if (settings.volumeKeysScroll && state.hasText) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.VolumeDown || event.key == Key.VolumeUp)
            ) {
                val step = viewportHeightPx * VOLUME_PAGE_FRACTION
                scope.launch {
                    scrollState.animateScrollBy(
                        if (event.key == Key.VolumeDown) step else -step,
                    )
                }
                true
            } else {
                false
            }
        }
    } else {
        Modifier
    }
    LaunchedEffect(settings.volumeKeysScroll, state.hasText) {
        if (settings.volumeKeysScroll && state.hasText) {
            focusRequester.requestFocus()
        }
    }

    val contentTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    val contentBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (state.unavailable) MaterialTheme.colorScheme.surface else theme.background)
            .onSizeChanged { viewportHeightPx = it.height }
            .focusRequester(focusRequester)
            .then(volumeKeyHandler)
            .focusable(),
    ) {
        when {
            state.unavailable -> ReaderUnavailableState()

            state.loading && !state.hasText -> SkeletonLines(theme, contentTop)

            !state.hasText && failure != null -> ReaderErrorState(
                failure = failure,
                theme = theme,
                onRetry = viewModel::retry,
            )

            else -> ReaderContent(
                state = state,
                settings = settings,
                scrollState = scrollState,
                contentTop = contentTop,
                contentBottom = contentBottom,
                layout = chapterLayout,
                activeSentenceIndex = activeSentenceIndex,
                paragraphCoordinates = paragraphCoordinates,
                paragraphLayouts = paragraphLayouts,
                onContentLaidOut = { contentCoordinates = it },
                onLaidOut = { contentLaidOut = true },
                onRetry = viewModel::retry,
                onToggleChrome = {
                    chromeVisible = !chromeVisible
                    if (settings.volumeKeysScroll) focusRequester.requestFocus()
                },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && !state.unavailable,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = state.chapterTitle,
                onBack = onBack,
                ttsPlayingHere = sessionHere?.isPlaying == true,
                onTogglePlayback = startTtsHere,
                onOpenSettings = { showSettingsSheet = true },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && state.hasText,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (ttsState.isActive) {
                TtsMiniBar(
                    state = ttsState,
                    onToggle = ttsController::togglePlayPause,
                    onSkipSentence = ttsController::skipSentence,
                    onExpand = { showTtsDrawer = true },
                )
            } else {
                ReaderFooter(
                    percent = state.percent,
                    previousChapter = state.previousChapter,
                    nextChapter = state.nextChapter,
                    onPrevious = { state.previousChapter?.let { onSwitchChapter(it.path) } },
                    onNext = { state.nextChapter?.let { onSwitchChapter(it.path) } },
                )
            }
        }
    }

    if (showTtsDrawer) {
        TtsDrawer(onDismiss = { showTtsDrawer = false })
    }

    if (showSettingsSheet) {
        ReaderSettingsSheet(
            settings = settings,
            onDismiss = { showSettingsSheet = false },
            onSetTheme = viewModel::setTheme,
            onSetTextSize = viewModel::setTextSizeSp,
            onSetLineSpacing = viewModel::setLineSpacing,
            onSetTypeface = viewModel::setTypeface,
            onSetKeepScreenOn = viewModel::setKeepScreenOn,
            onSetVolumeScroll = viewModel::setVolumeKeysScroll,
        )
    }
}

/**
 * The scroll target for a spoken sentence: the sentence's first line top and
 * bottom in scroll-content coordinates. Waits until the paragraph has both a
 * position and a text layout.
 */
private suspend fun sentenceTargetY(
    layout: ChapterLayout,
    index: Int,
    paragraphCoordinates: Map<Int, LayoutCoordinates>,
    paragraphLayouts: Map<Int, TextLayoutResult>,
    contentCoordinates: LayoutCoordinates,
): Pair<Float, Float>? {
    val sentence = layout.sentences.getOrNull(index) ?: return null
    val paragraphIndex = layout.paragraphs.indexOfFirst {
        it.start <= sentence.start && sentence.start < it.end
    }
    if (paragraphIndex < 0) return null
    val found = snapshotFlow {
        paragraphCoordinates[paragraphIndex] to paragraphLayouts[paragraphIndex]
    }.first { it.first != null && it.second != null }
    val textLayout = found.second ?: return null
    val localOffset = sentence.start - layout.paragraphs[paragraphIndex].start
    val line = textLayout.getLineForOffset(localOffset)
    val top = contentCoordinates.localPositionOf(found.first ?: return null, Offset.Zero).y +
        textLayout.getLineTop(line)
    return top to textLayout.getLineBottom(line)
}

@Composable
private fun ReaderContent(
    state: ReaderUiState,
    settings: ReaderSettings,
    scrollState: ScrollState,
    contentTop: Dp,
    contentBottom: Dp,
    layout: ChapterLayout?,
    activeSentenceIndex: Int?,
    paragraphCoordinates: MutableMap<Int, LayoutCoordinates>,
    paragraphLayouts: MutableMap<Int, TextLayoutResult>,
    onContentLaidOut: (LayoutCoordinates) -> Unit,
    onLaidOut: () -> Unit,
    onRetry: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    val theme = settings.theme
    val highlight = activeSentenceIndex?.let { index -> layout?.sentences?.getOrNull(index) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onGloballyPositioned(onContentLaidOut)
            .onSizeChanged { if (it.height > 0) onLaidOut() }
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) }
            .padding(top = contentTop, bottom = contentBottom)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.failure?.let { failure ->
            ErrorBanner(failure = failure, theme = theme, onRetry = onRetry)
        }
        ReaderHeader(state.novelTitle, state.chapterTitle, theme)
        val bodyStyle = TextStyle(
            fontSize = settings.textSizeSp.sp,
            lineHeight = settings.textSizeSp.sp * settings.lineSpacing,
            fontFamily = settings.typeface.fontFamily,
        )
        val highlightStyle = SpanStyle(
            background = MaterialTheme.colorScheme.primaryContainer,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        state.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            Text(
                text = annotatedParagraph(
                    paragraph = paragraph,
                    highlight = highlight,
                    paragraphRange = layout?.paragraphs?.getOrNull(paragraphIndex),
                    highlightStyle = highlightStyle,
                ),
                style = bodyStyle,
                color = theme.onBackground,
                onTextLayout = { paragraphLayouts[paragraphIndex] = it },
                modifier = Modifier.onGloballyPositioned { paragraphCoordinates[paragraphIndex] = it },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The spoken sentence highlighted inline: primary-container
 * background with on-primary-container text, over the exact character range
 * the sentence occupies inside this paragraph.
 */
private fun annotatedParagraph(
    paragraph: String,
    highlight: com.chief.anansi.audio.Sentence?,
    paragraphRange: com.chief.anansi.audio.ParagraphRange?,
    highlightStyle: SpanStyle,
) = buildAnnotatedString {
    append(paragraph)
    if (highlight == null || paragraphRange == null) return@buildAnnotatedString
    if (highlight.end <= paragraphRange.start || highlight.start >= paragraphRange.end) {
        return@buildAnnotatedString
    }
    addStyle(
        highlightStyle,
        (highlight.start - paragraphRange.start).coerceAtLeast(0),
        (highlight.end - paragraphRange.start).coerceAtMost(paragraph.length),
    )
}

@Composable
private fun ReaderHeader(novelTitle: String, chapterTitle: String, theme: ReaderTheme) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = novelTitle,
            style = MaterialTheme.typography.labelMedium,
            color = theme.muted,
            textAlign = TextAlign.Center,
        )
        Text(
            text = chapterTitle,
            style = MaterialTheme.typography.titleMedium,
            color = theme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    ttsPlayingHere: Boolean,
    onTogglePlayback: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Play starts the queue on this chapter; while this
            // chapter's session plays, the control pauses it.
            IconButton(onClick = onTogglePlayback) {
                Icon(
                    painter = painterResource(
                        if (ttsPlayingHere) R.drawable.ic_pause else R.drawable.ic_headphones,
                    ),
                    contentDescription = stringResource(
                        if (ttsPlayingHere) R.string.cd_reader_pause_playback else R.string.cd_reader_play,
                    ),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_tune),
                    contentDescription = stringResource(R.string.cd_reader_settings),
                )
            }
        }
    }
}

@Composable
private fun ReaderFooter(
    percent: Int,
    previousChapter: NeighborChapter?,
    nextChapter: NeighborChapter?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = previousChapter != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.cd_reader_previous_chapter),
                    modifier = Modifier.graphicsLayer(rotationZ = 180f),
                )
            }
            Text(
                text = stringResource(R.string.reader_percent, percent),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext, enabled = nextChapter != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.cd_reader_next_chapter),
                )
            }
        }
    }
}

/** The failed-retry banner: the registry's message over kept session text. */
@Composable
private fun ErrorBanner(failure: ReaderFailure, theme: ReaderTheme, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = theme.onBackground.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 6.dp)) {
            Text(
                text = failure.kind,
                style = MaterialTheme.typography.labelLarge,
                color = theme.onBackground,
            )
            Text(
                text = failure.reason,
                style = MaterialTheme.typography.bodySmall,
                color = theme.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(contentColor = theme.onBackground),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/** The full-screen registry-sourced failure: retry with no cached text kept. */
@Composable
private fun ReaderErrorState(failure: ReaderFailure, theme: ReaderTheme, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warn),
            contentDescription = null,
            tint = theme.muted,
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = stringResource(R.string.reader_error_headline),
            style = MaterialTheme.typography.titleMedium,
            color = theme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = failure.kind,
            style = MaterialTheme.typography.labelLarge,
            color = theme.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = failure.reason,
            style = MaterialTheme.typography.bodySmall,
            color = theme.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun ReaderUnavailableState() {
    EmptyState(
        icon = painterResource(R.drawable.ic_globe),
        title = stringResource(R.string.reader_unavailable_title),
        body = stringResource(R.string.reader_unavailable_body),
    )
}

@Composable
private fun SkeletonLines(theme: ReaderTheme, contentTop: Dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    val widths = listOf(0.6f, 1f, 0.94f, 1f, 0.88f, 1f, 0.97f, 0.9f, 1f, 0.93f, 0.55f)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentTop)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        widths.forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(theme.onBackground.copy(alpha = alpha)),
            )
        }
    }
}

private fun percentOf(offset: Int, maxScroll: Int): Int = when {
    maxScroll <= 0 -> ReaderViewModel.PERCENT_FINISHED
    else -> (offset.toLong() * 100 / maxScroll).toInt().coerceIn(0, 100)
}
