package com.chief.anansi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.audio.TtsPlaybackService
import com.chief.anansi.ui.AnansiApp
import com.chief.anansi.ui.AppViewModel
import com.chief.anansi.ui.LocalTtsController
import com.chief.anansi.ui.theme.AnansiTheme

class MainActivity : ComponentActivity() {

    /** A pending reader deep link from the TTS notification tap. */
    private var pendingReaderRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingReaderRoute = readerRouteFromIntent(intent)
        setContent {
            val viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)
            val state by viewModel.settingsUiState.collectAsState()
            val container = (application as AnansiApplication).container
            CompositionLocalProvider(LocalTtsController provides container.ttsController) {
                AnansiTheme(
                    themeMode = state.themeMode,
                    seedTheme = state.seedTheme,
                ) {
                    AnansiApp(
                        state = state,
                        onSetThemeMode = viewModel::setThemeMode,
                        onSetSeedTheme = viewModel::setSeedTheme,
                        onSetPinnedTabsOnly = viewModel::setPinnedTabsOnly,
                        pendingReaderRoute = pendingReaderRoute,
                        onReaderRouteConsumed = { pendingReaderRoute = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readerRouteFromIntent(intent)?.let { pendingReaderRoute = it }
    }

    private fun readerRouteFromIntent(intent: Intent?): String? {
        val identity = intent?.getStringExtra(TtsPlaybackService.EXTRA_IDENTITY) ?: return null
        val novelPath = intent.getStringExtra(TtsPlaybackService.EXTRA_NOVEL_PATH) ?: return null
        val chapterPath = intent.getStringExtra(TtsPlaybackService.EXTRA_CHAPTER_PATH) ?: return null
        return "reader/$identity/${Uri.encode(novelPath)}/${Uri.encode(chapterPath)}"
    }
}
