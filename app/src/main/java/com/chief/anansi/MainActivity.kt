package com.chief.anansi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.ui.AnansiApp
import com.chief.anansi.ui.AppViewModel
import com.chief.anansi.ui.theme.AnansiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)
            val state by viewModel.settingsUiState.collectAsState()
            AnansiTheme(
                themeMode = state.themeMode,
                seedTheme = state.seedTheme,
            ) {
                AnansiApp(
                    state = state,
                    onSetThemeMode = viewModel::setThemeMode,
                    onSetSeedTheme = viewModel::setSeedTheme,
                    onSetPinnedTabsOnly = viewModel::setPinnedTabsOnly,
                )
            }
        }
    }
}
