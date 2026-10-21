package com.chief.anansi.ui.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// A separate store file from the app settings: reader keys carry their
// own reader_ namespace and live only in this store.
private val Context.readerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "anansi_reader_settings",
)

/**
 * The reader settings store: every control persists immediately
 * and reads back as a live [ReaderSettings] flow, so the open reader updates
 * while the sheet is up (live preview). Global scope; per-novel overrides
 * ride with paginated mode.
 */
class ReaderSettingsStore(private val context: Context) {

    val settings: Flow<ReaderSettings> = context.readerDataStore.data.map { prefs ->
        ReaderSettings(
            theme = prefs[THEME_KEY]?.let { name -> ReaderTheme.entries.firstOrNull { it.name == name } }
                ?: ReaderTheme.DARK,
            textSizeSp = (prefs[TEXT_SIZE_KEY] ?: DEFAULT_TEXT_SIZE_SP.toFloat()).toInt(),
            lineSpacing = prefs[LINE_SPACING_KEY] ?: DEFAULT_LINE_SPACING,
            typeface = prefs[TYPEFACE_KEY]?.let { name -> ReaderTypeface.entries.firstOrNull { it.name == name } }
                ?: ReaderTypeface.SYSTEM,
            keepScreenOn = prefs[KEEP_SCREEN_ON_KEY] ?: false,
            volumeKeysScroll = prefs[VOLUME_SCROLL_KEY] ?: false,
        )
    }

    suspend fun setTheme(theme: ReaderTheme) {
        context.readerDataStore.edit { it[THEME_KEY] = theme.name }
    }

    suspend fun setTextSizeSp(sizeSp: Int) {
        context.readerDataStore.edit { it[TEXT_SIZE_KEY] = sizeSp.toFloat() }
    }

    suspend fun setLineSpacing(spacing: Float) {
        context.readerDataStore.edit { it[LINE_SPACING_KEY] = spacing }
    }

    suspend fun setTypeface(typeface: ReaderTypeface) {
        context.readerDataStore.edit { it[TYPEFACE_KEY] = typeface.name }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.readerDataStore.edit { it[KEEP_SCREEN_ON_KEY] = enabled }
    }

    suspend fun setVolumeKeysScroll(enabled: Boolean) {
        context.readerDataStore.edit { it[VOLUME_SCROLL_KEY] = enabled }
    }

    private companion object {
        const val DEFAULT_TEXT_SIZE_SP = 17
        const val DEFAULT_LINE_SPACING = 1.7f

        val THEME_KEY = stringPreferencesKey("reader_theme")
        val TEXT_SIZE_KEY = floatPreferencesKey("reader_text_size")
        val LINE_SPACING_KEY = floatPreferencesKey("reader_line_spacing")
        val TYPEFACE_KEY = stringPreferencesKey("reader_typeface")
        val KEEP_SCREEN_ON_KEY = booleanPreferencesKey("reader_keep_screen_on")
        val VOLUME_SCROLL_KEY = booleanPreferencesKey("reader_volume_keys_scroll")
    }
}
