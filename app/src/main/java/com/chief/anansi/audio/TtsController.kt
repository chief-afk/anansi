package com.chief.anansi.audio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The playback controller the reader UI talks to (the audio
 * service owns playback state; the UI only observes and sends commands
 * through this controller, registered once in the AppContainer). The service
 * attaches itself on create and detaches on destroy; commands posted while
 * no service is alive are dropped, and a new start always goes through
 * startForegroundService.
 */
class TtsController(
    context: Context,
    val settingsStore: TtsSettingsStore,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    /** The live service handle; null whenever the service is not running. */
    interface Handle {
        fun togglePlayPause()
        fun skipSentence(forward: Boolean)
        fun skipParagraph(forward: Boolean)
        fun seekToSentence(index: Int)
        fun switchChapter(forward: Boolean)
        fun setSpeed(rate: Float)
        fun retryFetch()
        fun stopFromUser()
    }

    @Volatile
    private var handle: Handle? = null

    fun attach(service: Handle) {
        handle = service
    }

    fun detach(service: Handle) {
        if (handle === service) {
            handle = null
        }
    }

    /** The service pushes each state mutation through here. */
    fun publish(state: TtsPlaybackState) {
        _state.value = state
    }

    /** Starts the queue for a novel: the details Listen and reader play entry. */
    fun start(identity: String, novelPath: String, chapterPath: String?) {
        val intent = Intent(appContext, TtsPlaybackService::class.java)
            .setAction(TtsPlaybackService.ACTION_START)
            .putExtra(TtsPlaybackService.EXTRA_IDENTITY, identity)
            .putExtra(TtsPlaybackService.EXTRA_NOVEL_PATH, novelPath)
            .putExtra(TtsPlaybackService.EXTRA_CHAPTER_PATH, chapterPath)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun togglePlayPause() = dispatch { it.togglePlayPause() }

    fun skipSentence(forward: Boolean) = dispatch { it.skipSentence(forward) }

    fun skipParagraph(forward: Boolean) = dispatch { it.skipParagraph(forward) }

    fun seekToSentence(index: Int) = dispatch { it.seekToSentence(index) }

    fun switchChapter(forward: Boolean) = dispatch { it.switchChapter(forward) }

    fun setSpeed(rate: Float) = dispatch { it.setSpeed(rate) }

    fun retryFetch() = dispatch { it.retryFetch() }

    fun stop() = dispatch { it.stopFromUser() }

    private fun dispatch(command: (Handle) -> Unit) {
        mainHandler.post {
            handle?.let(command)
        }
    }
}
