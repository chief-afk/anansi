package com.chief.anansi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.chief.anansi.settings.SeedTheme
import com.chief.anansi.settings.ThemeMode

@Composable
fun AnansiTheme(
    themeMode: ThemeMode,
    seedTheme: SeedTheme,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = seedTheme.colorScheme(darkTheme),
        content = content,
    )
}
