package com.chief.anansi.di

import android.content.Context
import com.chief.anansi.settings.SettingsStore

class AppContainer(context: Context) {
    val settingsStore: SettingsStore = SettingsStore(context)
}
