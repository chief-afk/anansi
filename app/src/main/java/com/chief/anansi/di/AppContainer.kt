package com.chief.anansi.di

import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.repo.HistoryRepository
import com.chief.anansi.data.repo.LibraryRepository
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.JsSourceRuntime
import com.chief.anansi.plugin.error.PersistentSourceErrorRegistry
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.repo.RepoRegistrationService
import com.chief.anansi.plugin.runtime.FetchBridge
import com.chief.anansi.plugin.runtime.QuickJsSourceRuntime
import com.chief.anansi.settings.SettingsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsStore: SettingsStore = SettingsStore(context)

    val database: AnansiDatabase = Room.databaseBuilder(
        appContext,
        AnansiDatabase::class.java,
        AnansiDatabase.NAME,
    ).addMigrations(AnansiDatabase.MIGRATION_1_2).build()

    // The app's one image loader; covers load through it only.
    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .components { add(OkHttpNetworkFetcherFactory()) }
        .build()

    // The app's one HTTP client: plugin fetches share it with Coil.
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val jsSourceRuntime: JsSourceRuntime = QuickJsSourceRuntime(
        hostScript = readAsset("pluginhost/host.js"),
        vendorScript = readAsset("pluginhost/vendor.js"),
        fetchBridge = FetchBridge(okHttpClient),
    )

    // The single error registry: reports persist so the management screen's
    // error lines survive restarts.
    val sourceErrorRegistry: SourceErrorRegistry = PersistentSourceErrorRegistry(
        store = database.sourceErrorDao(),
        scope = appScope,
    ).apply { preload() }

    val sourceRegistryRepository = SourceRegistryRepository(database)
    val repoRegistrationService = RepoRegistrationService(
        client = okHttpClient,
        runtime = jsSourceRuntime,
        errorRegistry = sourceErrorRegistry,
        sourceRegistry = sourceRegistryRepository,
    )
    val libraryRepository = LibraryRepository(database)
    val historyRepository = HistoryRepository(database)

    private fun readAsset(name: String): String =
        appContext.assets.open(name).bufferedReader().use { it.readText() }
}
