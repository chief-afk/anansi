package com.chief.anansi.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "anansi_settings")

class SettingsStore(private val context: Context) {

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
            ?: ThemeMode.SYSTEM
    }

    val seedTheme: Flow<SeedTheme> = context.dataStore.data.map { prefs ->
        prefs[SEED_KEY]?.let { value -> SeedTheme.entries.firstOrNull { it.name == value } }
            ?: SeedTheme.VIOLET
    }

    val pinnedTabsOnly: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PINNED_TABS_KEY] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    suspend fun setSeedTheme(seed: SeedTheme) {
        context.dataStore.edit { prefs -> prefs[SEED_KEY] = seed.name }
    }

    suspend fun setPinnedTabsOnly(pinnedOnly: Boolean) {
        context.dataStore.edit { prefs -> prefs[PINNED_TABS_KEY] = pinnedOnly }
    }

    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val SEED_KEY = stringPreferencesKey("seed_theme")
        val PINNED_TABS_KEY = booleanPreferencesKey("pinned_tabs_only")
    }
}
