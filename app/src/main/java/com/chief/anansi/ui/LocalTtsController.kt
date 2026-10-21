package com.chief.anansi.ui

import com.chief.anansi.audio.TtsController

/**
 * The audio reader controller, provided once at the app root (MainActivity
 * supplies the AppContainer instance); reader, details, and drawer UIs read
 * state and send commands through it.
 */
val LocalTtsController = androidx.compose.runtime.staticCompositionLocalOf<TtsController> {
    error("TtsController accessed outside the app root")
}
