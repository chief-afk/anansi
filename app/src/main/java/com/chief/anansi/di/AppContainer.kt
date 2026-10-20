package com.chief.anansi.di

import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.repo.HistoryRepository
import com.chief.anansi.data.repo.LibraryRepository
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.settings.SettingsStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore: SettingsStore = SettingsStore(context)

    val database: AnansiDatabase = Room.databaseBuilder(
        appContext,
        AnansiDatabase::class.java,
        AnansiDatabase.NAME,
    ).build()

    // The app's one image loader; covers load through it only.
    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .components { add(OkHttpNetworkFetcherFactory()) }
        .build()

    val sourceRegistryRepository = SourceRegistryRepository(database)
    val libraryRepository = LibraryRepository(database)
    val historyRepository = HistoryRepository(database)
}
