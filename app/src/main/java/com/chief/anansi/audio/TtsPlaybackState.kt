package com.chief.anansi.audio

/**
 * Playback state of the audio reader. The service owns this
 * state; the reader UI only observes it through [TtsController.state] and
 * sends commands back through the controller. Playback is process-lifetime:
 * after process death, restore happens through persisted progress when the
 * user reopens, not through a saved session.
 */
enum class TtsPhase {
    /** No session exists; the mini-bar, drawer entry, and play control hide. */
    Idle,

    /** The queue holds a chapter; synthesis is running or waiting on focus. */
    Playing,

    /** The queue holds a chapter and its position; synthesis is stopped. */
    Paused,
}

/** The chapter the queue is positioned on. */
data class TtsChapterRef(
    val rowId: Long,
    val path: String,
    val title: String,
    /** 1-based position in the novel's source order. */
    val number: Int,
    val count: Int,
)

/** The registry-sourced failure pair retained for retry, never reclassified. */
data class TtsFailure(val kind: String, val reason: String)

data class TtsPlaybackState(
    val phase: TtsPhase = TtsPhase.Idle,
    /** Source identity, for the reader deep link back to the spoken chapter. */
    val identity: String = "",
    val novelPath: String = "",
    val novelId: Long = 0,
    val novelTitle: String = "",
    val author: String? = null,
    val chapter: TtsChapterRef? = null,
    /** 0-based index of the spoken sentence; -1 while the engine warms up. */
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    /** Fetching or synthesizing is in flight; the thin progress shows. */
    val preparing: Boolean = false,
    /** The engine failed; the drawer and settings offer the system picker. */
    val engineBroken: Boolean = false,
    /** Next-chapter fetch failure, retained for retry. */
    val failure: TtsFailure? = null,
) {
    val isActive: Boolean get() = phase != TtsPhase.Idle
    val isPlaying: Boolean get() = phase == TtsPhase.Playing

    /** Progress percent through the chapter, derived from sentence position. */
    val percent: Int
        get() = when {
            sentenceCount <= 0 -> 0
            else -> ((sentenceIndex + 1) * 100 / sentenceCount).coerceIn(0, 100)
        }
}
