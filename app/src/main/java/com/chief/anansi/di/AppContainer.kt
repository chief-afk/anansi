package com.chief.anansi.di

import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.chief.anansi.audio.TtsController
import com.chief.anansi.audio.TtsSettingsStore
import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.repo.HistoryRepository
import com.chief.anansi.data.repo.LibraryRepository
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.data.repo.UpdateRepository
import com.chief.anansi.plugin.JsSourceRuntime
import com.chief.anansi.plugin.error.PersistentSourceErrorRegistry
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.repo.RepoRegistrationService
import com.chief.anansi.plugin.repo.RepoHttp
import com.chief.anansi.plugin.runtime.FetchBridge
import com.chief.anansi.plugin.runtime.QuickJsSourceRuntime
import com.chief.anansi.settings.SettingsStore
import com.chief.anansi.ui.browse.SourceSessions
import com.chief.anansi.ui.reader.ChapterTextCache
import com.chief.anansi.ui.reader.ReaderSettingsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    // Application-lifetime scope for writes that must outlive the screen that
    // started them (reader progress flushes, registry reports).
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsStore: SettingsStore = SettingsStore(context)
    val readerSettingsStore: ReaderSettingsStore = ReaderSettingsStore(appContext)
    val ttsSettingsStore: TtsSettingsStore = TtsSettingsStore(appContext)

    // The audio reader's single controller: the reader and details UIs
    // observe its state and send commands; the service attaches itself as
    // the live handle on create.
    val ttsController = TtsController(appContext, ttsSettingsStore)

    // Fetched chapter text held in memory for the session; the reader's
    // failed-retry and reopen paths read from it (no database text cache).
    val chapterTextCache = ChapterTextCache()

    val database: AnansiDatabase = Room.databaseBuilder(
        appContext,
        AnansiDatabase::class.java,
        AnansiDatabase.NAME,
    ).addMigrations(AnansiDatabase.MIGRATION_1_2, AnansiDatabase.MIGRATION_2_3).build()

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

    // Loads registered sources into live plugin adapters for the discovery
    // screens (catalog, search, details); the dist cache keeps reloads off
    // the network.
    val sourceSessions = SourceSessions(
        runtime = jsSourceRuntime,
        errorRegistry = sourceErrorRegistry,
        http = RepoHttp(okHttpClient),
    )

    val libraryRepository = LibraryRepository(database)
    val historyRepository = HistoryRepository(database)

    // Manual update checks: per-novel and check-all over library novels,
    // each through the novel's own loaded source adapter.
    val updateRepository = UpdateRepository(
        database = database,
        sourceRegistry = sourceRegistryRepository,
        loadSource = { sourceSessions.load(it) },
    )

    private fun readAsset(name: String): String =
        appContext.assets.open(name).bufferedReader().use { it.readText() }
}
