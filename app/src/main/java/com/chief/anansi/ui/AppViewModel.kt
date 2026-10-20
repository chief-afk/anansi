package com.chief.anansi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chief.anansi.AnansiApplication
import com.chief.anansi.settings.SeedTheme
import com.chief.anansi.settings.SettingsStore
import com.chief.anansi.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val seedTheme: SeedTheme = SeedTheme.VIOLET,
    val pinnedTabsOnly: Boolean = false,
)

class AppViewModel(private val settings: SettingsStore) : ViewModel() {

    val settingsUiState: StateFlow<SettingsUiState> =
        combine(settings.themeMode, settings.seedTheme, settings.pinnedTabsOnly) { mode, seed, pinnedOnly ->
            SettingsUiState(
                themeMode = mode,
                seedTheme = seed,
                pinnedTabsOnly = pinnedOnly,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setSeedTheme(seed: SeedTheme) {
        viewModelScope.launch { settings.setSeedTheme(seed) }
    }

    fun setPinnedTabsOnly(pinnedOnly: Boolean) {
        viewModelScope.launch { settings.setPinnedTabsOnly(pinnedOnly) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as AnansiApplication
                AppViewModel(application.container.settingsStore)
            }
        }
    }
}
