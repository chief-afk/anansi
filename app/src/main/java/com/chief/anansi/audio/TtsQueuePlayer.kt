package com.chief.anansi.audio

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Maps the TTS queue onto media3 session transport controls (
 * a SimpleBasePlayer, not a full Player). Each playlist item is one chapter;
 * skipToNext/skipToPrevious move chapters, seeking inside an item moves the
 * sentence position (1 second per sentence, so the notification progress bar
 * and lockscreen seek track sentence position), and stop ends the session.
 */
@OptIn(UnstableApi::class)
class TtsQueuePlayer(
    looper: Looper,
) : SimpleBasePlayer(looper) {

    /** Chapter metadata shown by the notification (novel, chapter, author). */
    data class ChapterItem(
        val uid: String,
        val mediaId: String,
        val chapterTitle: String,
        val novelTitle: String,
        val author: String?,
        val sentenceCount: Int,
    )

    /** The service's playback brain; every transport event lands here. */
    interface Listener {
        fun onPlayerPlay()
        fun onPlayerPause()
        fun onPlayerStop()
        fun onPlayerSeekChapter(index: Int)
        fun onPlayerSeekSentence(index: Int)
    }

    var listener: Listener? = null

    private var items: List<ChapterItem> = emptyList()
    private var currentIndex: Int = 0
    private var sentenceIndex: Int = 0
    private var playWhenReady: Boolean = false
    private var playbackState: Int = Player.STATE_IDLE

    /** Pushes the service's queue state into the session; main thread only. */
    fun sync(
        items: List<ChapterItem>,
        currentIndex: Int,
        sentenceIndex: Int,
        playWhenReady: Boolean,
        buffering: Boolean,
    ) {
        this.items = items
        this.currentIndex = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        this.sentenceIndex = sentenceIndex
        this.playWhenReady = playWhenReady
        this.playbackState = when {
            items.isEmpty() -> Player.STATE_IDLE
            buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        invalidateState()
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    )
                    .build(),
            )
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            // A "previous" always means the previous chapter, never the
            // chapter's start.
            .setMaxSeekToPreviousPositionMs(0)
            .setPlaylist(
                items.map { item ->
                    MediaItemData.Builder(item.uid)
                        .setMediaItem(MediaItem.Builder().setMediaId(item.mediaId).build())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(item.chapterTitle)
                                .setArtist(
                                    listOfNotNull(
                                        item.novelTitle.takeIf { it.isNotBlank() },
                                        item.author?.takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                )
                                .build(),
                        )
                        .setIsSeekable(true)
                        .setDurationUs(item.sentenceCount * 1_000_000L)
                        .build()
                },
            )
            .setCurrentMediaItemIndex(currentIndex)
            .setContentPositionMs(sentenceIndex * 1_000L)
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) listener?.onPlayerPlay() else listener?.onPlayerPause()
        return Futures.immediateFuture(null)
    }

    override fun handleStop(): ListenableFuture<*> {
        listener?.onPlayerStop()
        return Futures.immediateFuture(null)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (mediaItemIndex != currentIndex) {
            listener?.onPlayerSeekChapter(mediaItemIndex)
        } else {
            listener?.onPlayerSeekSentence((positionMs / 1_000L).toInt())
        }
        return Futures.immediateFuture(null)
    }

    /** Released with the service; nothing to stop asynchronously. */
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateFuture(null)
}
