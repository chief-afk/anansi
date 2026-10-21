package com.chief.anansi.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Own store file next to the reader settings: tts keys carry the tts_
// namespace and live only here.
private val Context.ttsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "anansi_tts_settings",
)

/** The designed audio reader defaults; the drawer mirrors two of them. */
data class TtsSettings(
    val defaultSpeed: Float = 1f,
    val pitch: Float = 1f,
    val autoPlayNext: Boolean = true,
    val pauseOnDisconnect: Boolean = true,
    val announceChapterStart: Boolean = true,
) {
    companion object {
        const val PITCH_MIN = 0.8f
        const val PITCH_MAX = 1.2f
    }
}

/**
 * Persisted audio reader settings under the tts namespace. Every control
 * persists immediately and reads back live; the drawer's speed and switch
 * rows edit the same keys this store owns, and the 7.3 screen edits all of
 * them.
 */
class TtsSettingsStore(private val context: Context) {

    val settings: Flow<TtsSettings> = context.ttsDataStore.data.map { prefs ->
        TtsSettings(
            defaultSpeed = prefs[DEFAULT_SPEED_KEY] ?: 1f,
            pitch = (prefs[PITCH_KEY] ?: 1f).coerceIn(TtsSettings.PITCH_MIN, TtsSettings.PITCH_MAX),
            autoPlayNext = prefs[AUTO_PLAY_NEXT_KEY] ?: true,
            pauseOnDisconnect = prefs[PAUSE_ON_DISCONNECT_KEY] ?: true,
            announceChapterStart = prefs[ANNOUNCE_KEY] ?: true,
        )
    }

    suspend fun current(): TtsSettings = settings.first()

    suspend fun setDefaultSpeed(rate: Float) {
        context.ttsDataStore.edit { it[DEFAULT_SPEED_KEY] = rate }
    }

    suspend fun setPitch(pitch: Float) {
        context.ttsDataStore.edit { it[PITCH_KEY] = pitch.coerceIn(TtsSettings.PITCH_MIN, TtsSettings.PITCH_MAX) }
    }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.ttsDataStore.edit { it[AUTO_PLAY_NEXT_KEY] = enabled }
    }

    suspend fun setPauseOnDisconnect(enabled: Boolean) {
        context.ttsDataStore.edit { it[PAUSE_ON_DISCONNECT_KEY] = enabled }
    }

    suspend fun setAnnounceChapterStart(enabled: Boolean) {
        context.ttsDataStore.edit { it[ANNOUNCE_KEY] = enabled }
    }

    /**
     * The POST_NOTIFICATIONS runtime ask happens once, on first play (board
     * permissions note); playback proceeds without the notification if the
     * user denies it.
     */
    suspend fun wasNotificationAsked(): Boolean = context.ttsDataStore.data.first()[NOTIFICATION_ASKED_KEY] == true

    suspend fun markNotificationAsked() {
        context.ttsDataStore.edit { it[NOTIFICATION_ASKED_KEY] = true }
    }

    private companion object {
        val DEFAULT_SPEED_KEY = floatPreferencesKey("tts_default_speed")
        val PITCH_KEY = floatPreferencesKey("tts_pitch")
        val AUTO_PLAY_NEXT_KEY = booleanPreferencesKey("tts_auto_play_next")
        val PAUSE_ON_DISCONNECT_KEY = booleanPreferencesKey("tts_pause_on_disconnect")
        val ANNOUNCE_KEY = booleanPreferencesKey("tts_announce_chapter_start")
        val NOTIFICATION_ASKED_KEY = booleanPreferencesKey("tts_notification_asked")
    }
}
