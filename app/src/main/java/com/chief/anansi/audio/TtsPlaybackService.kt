package com.chief.anansi.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.chief.anansi.AnansiApplication
import com.chief.anansi.MainActivity
import com.chief.anansi.R
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import com.chief.anansi.data.entity.ReadingProgressEntity
import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.data.repo.HistoryRepository
import com.chief.anansi.data.repo.LibraryRepository
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.Source
import com.chief.anansi.ui.browse.SourceSessions
import com.chief.anansi.ui.browse.failureKindOf
import com.chief.anansi.ui.reader.ChapterTextCache
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The audio reader foreground service: it owns playback
 * state end to end. The platform TextToSpeech engine runs here (async init,
 * media-stream output with speech content type, v1's STREAM_MUSIC intent);
 * the queue advances sentence by sentence, auto-plays the next chapter
 * through the Source path, and persists progress through the library record
 * the text reader also writes. A MediaSession exposes play/pause, chapter
 * skip, stop, and progress to the system; the service is a
 * MediaSessionService with a mediaPlayback foreground type, so listening
 * holds through screen-off and app-swipe.
 */
@OptIn(UnstableApi::class)
class TtsPlaybackService : MediaSessionService(), TtsController.Handle {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var controller: TtsController
    private lateinit var settingsStore: TtsSettingsStore
    private lateinit var sourceRegistry: SourceRegistryRepository
    private lateinit var sessions: SourceSessions
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var historyRepository: HistoryRepository
    private lateinit var textCache: ChapterTextCache

    private var player: TtsQueuePlayer? = null
    private var session: MediaSession? = null
    private var tts: TextToSpeech? = null
    private var engineReady = false

    // Queue state; mutated on the main thread only.
    private var source: Source? = null
    private var novel: com.chief.anansi.data.entity.NovelEntity? = null
    private var identity = ""
    private var novelPath = ""
    private var chapters: List<ChapterEntity> = emptyList()
    private var chapterIndex = -1
    private var sentences: List<Sentence> = emptyList()
    private var paragraphStarts: List<Int> = emptyList()
    private var sentenceIndex = 0
    private var phase = TtsPhase.Idle
    private var preparing = false
    private var wantPlaying = false
    private var engineBroken = false
    private var failure: TtsFailure? = null
    private var failedChapterIndex = -1
    /** Where the initial start resumes: percent maps to a sentence once the
     * chapter's layout exists (percent derives from sentence position). */
    private var resumeChapterRowId: Long = -1
    private var resumePercent = 0
    private var preservedScrollOffset = 0L
    private var pendingAnnounce = false
    private var currentSettings = TtsSettings()
    private var lastProgressWriteAt = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusGranted = false
    private val audioManager get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                currentSettings.pauseOnDisconnect &&
                phase == TtsPhase.Playing
            ) {
                pause()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusGranted = false
                pendingResumeAfterFocus = false
                pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                if (phase == TtsPhase.Playing) {
                    pendingResumeAfterFocus = true
                    pause()
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                focusGranted = true
                if (pendingResumeAfterFocus && phase == TtsPhase.Paused) {
                    pendingResumeAfterFocus = false
                    play()
                }
            }
        }
    }
    private var pendingResumeAfterFocus = false

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                when {
                    utteranceId == null -> Unit
                    utteranceId.startsWith(UTTERANCE_TITLE) -> speakCurrentSentence()
                    utteranceId.startsWith(UTTERANCE_SENTENCE) -> {
                        val chunkCount = utteranceId.split(":").getOrNull(4)?.toIntOrNull() ?: 1
                        val chunk = utteranceId.split(":").getOrNull(3)?.toIntOrNull() ?: 0
                        if (chunk == chunkCount - 1) advanceSentence()
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            mainHandler.post { onEngineError() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post { onEngineError() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as AnansiApplication).container
        controller = container.ttsController
        settingsStore = container.ttsSettingsStore
        sourceRegistry = container.sourceRegistryRepository
        sessions = container.sourceSessions
        libraryRepository = container.libraryRepository
        historyRepository = container.historyRepository
        textCache = container.chapterTextCache
        controller.attach(this)

        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anansi:tts")
            .apply { setReferenceCounted(false) }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()

        serviceScope.launch {
            settingsStore.settings.collect { settings ->
                currentSettings = settings
                tts?.let { engine ->
                    engine.setSpeechRate(settings.defaultSpeed)
                    engine.setPitch(settings.pitch)
                }
            }
        }

        // The engine initializes async; the service shows its notification
        // first and speaks once onInit lands.
        tts = TextToSpeech(this) { status ->
            mainHandler.post { onEngineInit(status) }
        }

        val queuePlayer = TtsQueuePlayer(mainHandler.looper)
        queuePlayer.listener = object : TtsQueuePlayer.Listener {
            override fun onPlayerPlay() {
                play()
            }

            override fun onPlayerPause() {
                pause()
            }

            override fun onPlayerStop() {
                stopPlayback(writeHistory = true)
            }

            override fun onPlayerSeekChapter(index: Int) {
                if (index in chapters.indices) loadChapter(index, play = wantPlaying)
            }

            override fun onPlayerSeekSentence(index: Int) {
                seekToSentence(index)
            }
        }
        player = queuePlayer
        val mediaSession = MediaSession.Builder(this, queuePlayer)
            .setSessionActivity(sessionActivityPendingIntent())
            .build()
        session = mediaSession
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Foreground discipline: promote before anything slow happens.
        startForeground(NOTIFICATION_ID, buildStartupNotification())
        when (intent?.action) {
            ACTION_START -> {
                val startedIdentity = intent.getStringExtra(EXTRA_IDENTITY).orEmpty()
                val startedNovelPath = intent.getStringExtra(EXTRA_NOVEL_PATH).orEmpty()
                val startedChapterPath = intent.getStringExtra(EXTRA_CHAPTER_PATH)
                serviceScope.launch {
                    beginQueue(startedIdentity, startedNovelPath, startedChapterPath)
                }
            }

            ACTION_TOGGLE_PLAY -> togglePlayPause()
            ACTION_NEXT_CHAPTER -> switchChapter(forward = true)
            ACTION_PREVIOUS_CHAPTER -> switchChapter(forward = false)
            ACTION_STOP -> stopPlayback(writeHistory = true)
        }
        return START_NOT_STICKY
    }

    // ---- Controller.Handle commands (arrive on the main thread) ----

    override fun togglePlayPause() {
        if (phase == TtsPhase.Playing) pause() else play()
    }

    override fun skipSentence(forward: Boolean) {
        moveSentenceTo(sentenceIndex + if (forward) 1 else -1)
    }

    override fun skipParagraph(forward: Boolean) {
        val target = paragraphIndex(sentenceIndex) + if (forward) 1 else -1
        when {
            paragraphStarts.isEmpty() -> skipSentence(forward)

            target < 0 -> moveSentenceTo(0)

            target >= paragraphStarts.size -> moveSentenceTo(sentences.lastIndex)

            else -> {
                val boundary = paragraphStarts[target]
                val first = sentences.indexOfFirst { it.start >= boundary }
                moveSentenceTo(if (first == -1) sentences.lastIndex else first)
            }
        }
    }

    override fun seekToSentence(index: Int) {
        moveSentenceTo(index)
    }

    override fun switchChapter(forward: Boolean) {
        val target = chapterIndex + if (forward) 1 else -1
        if (target in chapters.indices) loadChapter(target, play = wantPlaying)
    }

    override fun setSpeed(rate: Float) {
        currentSettings = currentSettings.copy(defaultSpeed = rate)
        tts?.setSpeechRate(rate)
        serviceScope.launch { settingsStore.setDefaultSpeed(rate) }
        if (phase == TtsPhase.Playing) speakCurrentSentence()
    }

    override fun retryFetch() {
        if (failedChapterIndex >= 0) loadChapter(failedChapterIndex, play = wantPlaying)
    }

    override fun stopFromUser() {
        stopPlayback(writeHistory = true)
    }

    // ---- Engine lifecycle ----

    private fun onEngineInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onEngineUnavailable()
            return
        }
        val engine = tts ?: return onEngineUnavailable()
        // v1 routed output to STREAM_MUSIC; the modern equivalent is the
        // media usage with speech content type.
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setSpeechRate(currentSettings.defaultSpeed)
        engine.setPitch(currentSettings.pitch)
        engine.setOnUtteranceProgressListener(utteranceListener)
        when (engine.setLanguage(Locale.getDefault())) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> onEngineUnavailable()
            else -> {
                engineReady = true
                engineBroken = false
                publishState()
                if (wantPlaying && chapterIndex >= 0) play()
            }
        }
    }

    /** Matrix rows: no engine shows the system picker; failure toasts and pauses. */
    private fun onEngineUnavailable() {
        engineReady = false
        engineBroken = true
        wantPlaying = false
        phase = TtsPhase.Paused
        val engines = tts?.engines.orEmpty()
        if (engines.isEmpty()) {
            runCatching {
                startActivity(
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            Toast.makeText(this, R.string.tts_no_engine_toast, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.tts_engine_error_toast, Toast.LENGTH_LONG).show()
        }
        publishState()
    }

    private fun onEngineError() {
        Toast.makeText(this, R.string.tts_engine_error_toast, Toast.LENGTH_LONG).show()
        pause()
    }

    // ---- Queue ----

    private suspend fun beginQueue(newIdentity: String, newNovelPath: String, chapterPath: String?) {
        tts?.stop()
        wantPlaying = true
        failure = null
        failedChapterIndex = -1
        identity = newIdentity
        novelPath = newNovelPath

        val sourceEntity = sourceRegistry.findByIdentity(identity) ?: return abortQueue()
        val loadedSource = try {
            sessions.load(sourceEntity)
        } catch (_: Exception) {
            return abortQueue()
        }
        source = loadedSource
        // The queue's rows are created lazily, so Listen works before the
        // novel is ever shelved; unknown sources still abort and a fetch
        // failure aborts instead of crashing the service.
        val loadedNovel = try {
            libraryRepository.ensureSourceNovelRows(identity, novelPath)
        } catch (_: Exception) {
            return abortQueue()
        } ?: return abortQueue()
        novel = loadedNovel
        val shelf = libraryRepository.novelWithProgress(loadedNovel.id) ?: return abortQueue()
        // The @Relation list carries no ORDER BY; the
        // queue must sort by source order itself.
        chapters = shelf.chapters.sortedBy { it.sortOrder }
        if (chapters.isEmpty()) return abortQueue()

        val progress = shelf.progress.firstOrNull()
        preservedScrollOffset = progress?.scrollOffset ?: 0L
        val startIndex = when {
            chapterPath != null -> chapters.indexOfFirst { it.chapterUrl == chapterPath }
                .takeIf { it >= 0 } ?: 0

            progress != null -> chapters.indexOfFirst { it.id == progress.chapterId }
                .takeIf { it >= 0 } ?: 0

            else -> 0
        }
        val resuming = chapterPath == null && progress != null &&
            chapters.getOrNull(startIndex)?.id == progress?.chapterId &&
            progress.percent < PERCENT_FINISHED
        resumeChapterRowId = if (resuming) progress?.chapterId ?: -1 else -1
        resumePercent = if (resuming) progress?.percent ?: 0 else 0
        loadChapter(startIndex, play = true)
    }

    private fun abortQueue() {
        stopPlayback(writeHistory = false)
    }

    private fun loadChapter(index: Int, play: Boolean) {
        val loadedSource = source
        val loadedNovel = novel
        val chapter = chapters.getOrNull(index)
        if (loadedSource == null || loadedNovel == null || chapter == null) return
        chapterIndex = index
        preparing = true
        failure = null
        failedChapterIndex = -1
        publishState()
        serviceScope.launch {
            try {
                val text = textCache.get(chapter.id) ?: loadedSource.chapterText(
                    loadedNovel.url,
                    chapter.chapterUrl,
                )
                if (text.isBlank()) {
                    throw IllegalStateException("Source returned an empty chapter")
                }
                textCache.put(chapter.id, text)
                val layout = SentenceSplitter.layout(text)
                mainHandler.post {
                    sentences = layout.sentences
                    paragraphStarts = layout.paragraphs.map { it.start }
                    sentenceIndex = if (chapter.id == resumeChapterRowId && layout.sentences.isNotEmpty()) {
                        ((resumePercent * layout.sentences.size) / 100 - 1)
                            .coerceIn(0, layout.sentences.lastIndex)
                    } else {
                        0
                    }
                    resumeChapterRowId = -1
                    resumePercent = 0
                    preparing = false
                    pendingAnnounce = currentSettings.announceChapterStart
                    if (play) {
                        wantPlaying = true
                        phase = TtsPhase.Playing
                    }
                    publishState()
                    if (phase == TtsPhase.Playing) speakChapterStart()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    preparing = false
                    failedChapterIndex = index
                    // Matrix: next-chapter fetch waits, paused with a
                    // registry-sourced error retained for retry.
                    failure = TtsFailure(
                        kind = failureKindOf(e),
                        reason = e.message ?: "unknown failure",
                    )
                    phase = TtsPhase.Paused
                    wantPlaying = false
                    publishState()
                }
            }
        }
    }

    private fun speakChapterStart() {
        if (!engineReady) return
        val chapter = chapters.getOrNull(chapterIndex) ?: return
        if (pendingAnnounce) {
            pendingAnnounce = false
            tts?.speak(chapter.title, TextToSpeech.QUEUE_FLUSH, null, "$UTTERANCE_TITLE$chapterIndex")
        } else {
            speakCurrentSentence()
        }
    }

    /**
     * v1 semantics: one sentence in flight; its completion advances the
     * index; pause keeps the index; resume re-speaks the current sentence.
     */
    private fun speakCurrentSentence() {
        if (!engineReady || phase != TtsPhase.Playing) return
        if (!requestAudioFocus()) {
            pause()
            return
        }
        val sentence = sentences.getOrNull(sentenceIndex)
        if (sentence == null) {
            chapterFinished()
            return
        }
        val engine = tts ?: return
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        val chunks = sentence.text.chunked(TextToSpeech.getMaxSpeechInputLength())
        chunks.forEachIndexed { chunkIndex, chunk ->
            val id = "$UTTERANCE_SENTENCE$chapterIndex:$sentenceIndex:$chunkIndex:${chunks.size}"
            engine.speak(
                chunk,
                if (chunkIndex == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                id,
            )
        }
    }

    private fun advanceSentence() {
        if (sentenceIndex < sentences.size - 1) {
            sentenceIndex++
            writeProgress(percentNow(), definitive = false)
            publishState()
            if (phase == TtsPhase.Playing) speakCurrentSentence()
        } else {
            chapterFinished()
        }
    }

    /**
     * The last sentence finished: mark read (v1), then auto-advance through
     * the Source path, hold paused for a manual advance, or end at the last
     * chapter.
     */
    private fun chapterFinished() {
        writeProgress(PERCENT_FINISHED, definitive = true)
        val chapter = chapters.getOrNull(chapterIndex)
        val currentNovel = novel
        if (chapter != null && currentNovel != null) {
            serviceScope.launch {
                libraryRepository.setChapterRead(currentNovel.id, chapter.id, true)
            }
        }
        tts?.stop()
        val next = chapterIndex + 1
        when {
            next < chapters.size && currentSettings.autoPlayNext ->
                loadChapter(next, play = true)

            next < chapters.size -> {
                phase = TtsPhase.Paused
                wantPlaying = false
                releaseWakeLock()
                publishState()
            }

            else -> stopPlayback(writeHistory = false)
        }
    }

    private fun moveSentenceTo(index: Int) {
        if (sentences.isEmpty()) return
        val clamped = index.coerceIn(0, sentences.lastIndex)
        if (clamped == sentenceIndex && phase != TtsPhase.Idle) return
        sentenceIndex = clamped
        if (phase == TtsPhase.Playing) {
            speakCurrentSentence()
        } else {
            publishState()
        }
    }

    // ---- Transport ----

    private fun play() {
        if (chapters.isEmpty() || chapterIndex < 0) {
            wantPlaying = true
            publishState()
            return
        }
        // A chapter paused on its last sentence is complete; play means the
        // next chapter (manual advance when auto-play is off).
        if (!preparing && sentences.isNotEmpty() &&
            sentenceIndex >= sentences.lastIndex &&
            phase == TtsPhase.Paused
        ) {
            val next = chapterIndex + 1
            if (next < chapters.size) {
                loadChapter(next, play = true)
                return
            }
            stopPlayback(writeHistory = false)
            return
        }
        if (!requestAudioFocus()) {
            phase = TtsPhase.Paused
            publishState()
            return
        }
        wantPlaying = true
        phase = TtsPhase.Playing
        publishState()
        if (preparing) return
        speakChapterStart()
    }

    private fun pause() {
        if (phase == TtsPhase.Idle) return
        wantPlaying = false
        tts?.stop()
        phase = TtsPhase.Paused
        writeProgress(percentNow(), definitive = true)
        releaseWakeLock()
        publishState()
    }

    /**
     * Ends the session: definitive history write, state reset, and
     * service teardown.
     */
    private fun stopPlayback(writeHistory: Boolean) {
        if (writeHistory && chapterIndex >= 0) writeProgress(percentNow(), definitive = true)
        phase = TtsPhase.Idle
        wantPlaying = false
        preparing = false
        failure = null
        sentences = emptyList()
        paragraphStarts = emptyList()
        sentenceIndex = 0
        chapters = emptyList()
        chapterIndex = -1
        failedChapterIndex = -1
        releaseWakeLock()
        abandonAudioFocus()
        tts?.stop()
        publishState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundPosted = false
        lastNotificationPhase = null
        stopSelf()
    }

    // ---- Focus, noise, wake ----

    private fun requestAudioFocus(): Boolean {
        if (focusGranted) return true
        val request = focusRequest ?: return false
        focusGranted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusGranted
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusGranted = false
        pendingResumeAfterFocus = false
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    // ---- Progress (same write path as the text reader: offset + percent) ----

    private fun percentNow(): Int = when {
        sentences.isEmpty() -> 0
        else -> ((sentenceIndex + 1) * 100 / sentences.size).coerceIn(0, 100)
    }

    private fun paragraphIndex(sentenceIdx: Int): Int {
        val start = sentences.getOrNull(sentenceIdx)?.start ?: return -1
        return paragraphStarts.indexOfLast { it <= start }.coerceAtLeast(0)
    }

    private fun writeProgress(percent: Int, definitive: Boolean) {
        val now = System.currentTimeMillis()
        if (!definitive && now - lastProgressWriteAt < PROGRESS_WRITE_INTERVAL_MS) return
        val currentNovel = novel ?: return
        val chapter = chapters.getOrNull(chapterIndex) ?: return
        lastProgressWriteAt = now
        serviceScope.launch {
            historyRepository.upsertProgress(
                ReadingProgressEntity(
                    novelId = currentNovel.id,
                    chapterId = chapter.id,
                    scrollOffset = preservedScrollOffset,
                    percent = percent,
                    updatedAt = now,
                ),
            )
            if (percent >= READ_MARK_PERCENT) {
                libraryRepository.setChapterRead(currentNovel.id, chapter.id, true)
            }
        }
    }

    // ---- State publication ----

    private fun publishState() {
        val currentNovel = novel
        val chapter = chapters.getOrNull(chapterIndex)
        controller.publish(
            TtsPlaybackState(
                phase = phase,
                identity = identity,
                novelPath = novelPath,
                novelId = currentNovel?.id ?: 0,
                novelTitle = currentNovel?.title.orEmpty(),
                author = currentNovel?.author,
                chapter = chapter?.let { row ->
                    TtsChapterRef(
                        rowId = row.id,
                        path = row.chapterUrl,
                        title = row.title,
                        number = chapterIndex + 1,
                        count = chapters.size,
                    )
                },
                sentenceIndex = sentenceIndex,
                sentenceCount = sentences.size,
                preparing = preparing,
                engineBroken = engineBroken,
                failure = failure,
            ),
        )
        player?.sync(
            items = chapters.mapIndexed { index, row ->
                TtsQueuePlayer.ChapterItem(
                    uid = "chapter-${row.id}",
                    mediaId = row.chapterUrl,
                    chapterTitle = row.title,
                    novelTitle = currentNovel?.title.orEmpty(),
                    author = currentNovel?.author,
                    sentenceCount = if (index == chapterIndex) sentences.size.coerceAtLeast(1) else 1,
                )
            },
            currentIndex = chapterIndex.coerceAtLeast(0),
            sentenceIndex = sentenceIndex,
            playWhenReady = phase == TtsPhase.Playing,
            buffering = preparing,
        )
        refreshNotification()
    }

    // ---- Notification ----
    // The service owns its MediaStyle notification: the session
    // token powers lockscreen rendering, transport actions are service
    // intents, and dismissal or stop ends the service with the history
    // write.

    private var foregroundPosted = false
    private var lastNotificationPhase: TtsPhase? = null

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.tts_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildStartupNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_spider)
            .setContentTitle(getString(R.string.tts_notification_starting))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** Re-posts the media notification; foreground only on phase changes. */
    private fun refreshNotification() {
        val currentSession = session ?: return
        val notification = buildMediaNotification(currentSession)
        if (!foregroundPosted || lastNotificationPhase != phase) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundPosted = true
            lastNotificationPhase = phase
        } else {
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildMediaNotification(currentSession: MediaSession): Notification {
        val chapter = chapters.getOrNull(chapterIndex)
        val playing = phase == TtsPhase.Playing
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_spider)
            .setContentTitle(
                chapter?.title ?: getString(R.string.tts_notification_starting),
            )
            .setContentText(
                listOfNotNull(
                    novel?.title?.takeIf { it.isNotBlank() },
                    novel?.author?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
            .setContentIntent(contentPendingIntent())
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, DELETE_REQUEST_CODE))
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val compact = mutableListOf<Int>()
        var actionCount = 0
        fun addAction(iconRes: Int, titleRes: Int, action: String, requestCode: Int, compactView: Boolean) {
            builder.addAction(
                NotificationCompat.Action(
                    iconRes,
                    getString(titleRes),
                    servicePendingIntent(action, requestCode),
                ),
            )
            if (compactView) compact.add(actionCount)
            actionCount++
        }
        if (chapterIndex > 0) {
            addAction(
                R.drawable.ic_skip_previous,
                R.string.cd_tts_previous_chapter,
                ACTION_PREVIOUS_CHAPTER,
                PREV_REQUEST_CODE,
                true,
            )
        }
        addAction(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            if (playing) R.string.cd_tts_pause else R.string.cd_tts_play,
            ACTION_TOGGLE_PLAY,
            TOGGLE_REQUEST_CODE,
            true,
        )
        if (chapterIndex in 0 until chapters.size - 1) {
            addAction(
                R.drawable.ic_skip_next,
                R.string.cd_tts_next_chapter,
                ACTION_NEXT_CHAPTER,
                NEXT_REQUEST_CODE,
                true,
            )
        }
        addAction(R.drawable.ic_close, R.string.cd_tts_stop, ACTION_STOP, STOP_REQUEST_CODE, false)

        builder.setStyle(
            MediaStyleNotificationHelper.MediaStyle(currentSession)
                .setShowActionsInCompactView(*compact.toIntArray()),
        )
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        CONTENT_REQUEST_CODE,
        applyDeepLinkExtras(
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun applyDeepLinkExtras(intent: Intent): Intent {
        val chapter = chapters.getOrNull(chapterIndex)
        return intent
            .putExtra(EXTRA_IDENTITY, identity)
            .putExtra(EXTRA_NOVEL_PATH, novelPath)
            .putExtra(EXTRA_CHAPTER_PATH, chapter?.chapterUrl)
    }

    private fun sessionActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            SESSION_REQUEST_CODE,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onDestroy() {
        controller.detach(this)
        serviceScope.cancel()
        runCatching { session?.release() }
        session = null
        player?.release()
        player = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        releaseWakeLock()
        abandonAudioFocus()
        runCatching { unregisterReceiver(noisyReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.chief.anansi.audio.action.START"
        const val ACTION_TOGGLE_PLAY = "com.chief.anansi.audio.action.TOGGLE_PLAY"
        const val ACTION_NEXT_CHAPTER = "com.chief.anansi.audio.action.NEXT_CHAPTER"
        const val ACTION_PREVIOUS_CHAPTER = "com.chief.anansi.audio.action.PREVIOUS_CHAPTER"
        const val ACTION_STOP = "com.chief.anansi.audio.action.STOP"
        const val EXTRA_IDENTITY = "com.chief.anansi.audio.extra.IDENTITY"
        const val EXTRA_NOVEL_PATH = "com.chief.anansi.audio.extra.NOVEL_PATH"
        const val EXTRA_CHAPTER_PATH = "com.chief.anansi.audio.extra.CHAPTER_PATH"

        const val NOTIFICATION_ID = 4101
        const val NOTIFICATION_CHANNEL_ID = "anansi_audio"

        private const val UTTERANCE_TITLE = "t:"
        private const val UTTERANCE_SENTENCE = "s:"

        /** The text reader's threshold; spoken chapters mark read the same way. */
        private const val READ_MARK_PERCENT = 97
        private const val PERCENT_FINISHED = 100
        private const val PROGRESS_WRITE_INTERVAL_MS = 2_000L
        private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L
        private const val SESSION_REQUEST_CODE = 91
        private const val CONTENT_REQUEST_CODE = 92
        private const val TOGGLE_REQUEST_CODE = 93
        private const val NEXT_REQUEST_CODE = 94
        private const val PREV_REQUEST_CODE = 95
        private const val STOP_REQUEST_CODE = 96
        private const val DELETE_REQUEST_CODE = 97
    }
}
