package com.chief.anansi.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chief.anansi.settings.SeedTheme
import com.chief.anansi.settings.ThemeMode
import com.chief.anansi.plugin.local.LocalLibrary
import com.chief.anansi.ui.browse.BrowseScreen
import com.chief.anansi.ui.browse.CatalogScreen
import com.chief.anansi.ui.browse.NovelDetailsScreen
import com.chief.anansi.ui.browse.RepositoriesScreen
import com.chief.anansi.ui.browse.SearchScreen
import com.chief.anansi.ui.browse.SourceManageScreen
import com.chief.anansi.ui.history.HistoryScreen
import com.chief.anansi.ui.library.LibraryScreen
import com.chief.anansi.ui.local.LocalScreen
import com.chief.anansi.ui.more.AppearanceScreen
import com.chief.anansi.ui.more.AudioSettingsScreen
import com.chief.anansi.ui.more.BackupScreen
import com.chief.anansi.ui.more.MoreScreen
import com.chief.anansi.ui.reader.ReaderScreen
import com.chief.anansi.ui.updates.UpdatesScreen

private const val APPEARANCE_ROUTE = "appearance"
private const val AUDIO_ROUTE = "audio"
private const val BACKUP_ROUTE = "backup"
private const val REPOSITORIES_ROUTE = "browse/repositories"
private const val LOCAL_ROUTE = "browse/local"
private const val SOURCE_MANAGE_ROUTE = "browse/source/{identity}"
private const val CATALOG_ROUTE = "browse/source/{identity}/catalog"
private const val SEARCH_ROUTE = "browse/search?identity={identity}"
private const val NOVEL_ROUTE = "browse/source/{identity}/novel/{path}"
private const val READER_ROUTE = "reader/{identity}/{novelPath}/{chapterPath}"

@Composable
fun AnansiApp(
    state: SettingsUiState,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetSeedTheme: (SeedTheme) -> Unit,
    onSetPinnedTabsOnly: (Boolean) -> Unit,
    /** A pending reader deep link from the TTS notification tap. */
    pendingReaderRoute: String? = null,
    onReaderRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val visibleTabs = if (state.pinnedTabsOnly) {
        AnansiTab.entries.filter { it != AnansiTab.UPDATES && it != AnansiTab.HISTORY }
    } else {
        AnansiTab.entries.toList()
    }

    Scaffold(
        bottomBar = {
            if (AnansiTab.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    visibleTabs.forEach { tab ->
                        val selected = tab.route == currentRoute
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab) },
                            icon = {
                                Icon(
                                    painter = painterResource(if (selected) tab.selectedIconRes else tab.iconRes),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AnansiTab.LIBRARY.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AnansiTab.LIBRARY.route) {
                LibraryScreen(
                    onBrowseSources = { navController.navigateToTab(AnansiTab.BROWSE) },
                    onOpenNovel = { identity, novelPath ->
                        navController.navigate(
                            "browse/source/$identity/novel/${Uri.encode(novelPath)}",
                        )
                    },
                )
            }
            composable(AnansiTab.UPDATES.route) {
                UpdatesScreen(
                    onOpenNovel = { identity, novelPath ->
                        navController.navigate(
                            "browse/source/$identity/novel/${Uri.encode(novelPath)}",
                        )
                    },
                    onPlayNewest = { identity, novelPath, chapterPath ->
                        navController.navigate(
                            "reader/$identity/${Uri.encode(novelPath)}/${Uri.encode(chapterPath)}",
                        )
                    },
                )
            }
            composable(AnansiTab.HISTORY.route) {
                HistoryScreen(
                    onOpenNovel = { identity, novelPath, chapterPath ->
                        navController.navigate(
                            "reader/$identity/${Uri.encode(novelPath)}/${Uri.encode(chapterPath)}",
                        )
                    },
                )
            }
            composable(AnansiTab.BROWSE.route) {
                BrowseScreen(
                    onOpenRepositories = { navController.navigate(REPOSITORIES_ROUTE) },
                    onOpenSource = { identity ->
                        navController.navigate("browse/source/$identity")
                    },
                    onOpenCatalog = { row ->
                        navController.navigate("browse/source/${row.identity}/catalog")
                    },
                    onOpenSearch = { navController.navigate("browse/search") },
                    onOpenLocal = { navController.navigate(LOCAL_ROUTE) },
                )
            }
            composable(LOCAL_ROUTE) {
                LocalScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNovel = { path ->
                        navController.navigate(
                            "browse/source/${LocalLibrary.IDENTITY}/novel/${Uri.encode(path)}",
                        )
                    },
                )
            }
            composable(REPOSITORIES_ROUTE) {
                RepositoriesScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = SOURCE_MANAGE_ROUTE,
                arguments = listOf(navArgument("identity") { type = NavType.StringType }),
            ) { entry ->
                val identity = entry.arguments?.getString("identity").orEmpty()
                SourceManageScreen(
                    identity = identity,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = CATALOG_ROUTE,
                arguments = listOf(navArgument("identity") { type = NavType.StringType }),
            ) { entry ->
                val identity = entry.arguments?.getString("identity").orEmpty()
                CatalogScreen(
                    identity = identity,
                    onBack = { navController.popBackStack() },
                    onOpenSearch = {
                        navController.navigate("browse/search?identity=${Uri.encode(identity)}")
                    },
                    onOpenNovel = { item ->
                        navController.navigate(
                            "browse/source/$identity/novel/${Uri.encode(item.path)}",
                        )
                    },
                )
            }
            composable(
                route = SEARCH_ROUTE,
                arguments = listOf(
                    navArgument("identity") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val scoped = entry.arguments
                    ?.getString("identity")
                    ?.takeIf { it.isNotEmpty() }
                SearchScreen(
                    scopedIdentity = scoped,
                    onBack = { navController.popBackStack() },
                    onOpenNovel = { sourceIdentity, novelPath ->
                        navController.navigate(
                            "browse/source/$sourceIdentity/novel/${Uri.encode(novelPath)}",
                        )
                    },
                )
            }
            composable(
                route = NOVEL_ROUTE,
                arguments = listOf(
                    navArgument("identity") { type = NavType.StringType },
                    navArgument("path") { type = NavType.StringType },
                ),
            ) { entry ->
                val identity = entry.arguments?.getString("identity").orEmpty()
                val novelPath = entry.arguments?.getString("path").orEmpty()
                NovelDetailsScreen(
                    identity = identity,
                    novelPath = novelPath,
                    onBack = { navController.popBackStack() },
                    onRemoved = { navController.popBackStack() },
                    onOpenChapter = { chapter ->
                        navController.navigate(
                            "reader/$identity/${Uri.encode(novelPath)}/${Uri.encode(chapter.path)}",
                        )
                    },
                )
            }
            composable(
                route = READER_ROUTE,
                arguments = listOf(
                    navArgument("identity") { type = NavType.StringType },
                    navArgument("novelPath") { type = NavType.StringType },
                    navArgument("chapterPath") { type = NavType.StringType },
                ),
            ) { entry ->
                val identity = entry.arguments?.getString("identity").orEmpty()
                val novelPath = entry.arguments?.getString("novelPath").orEmpty()
                val chapterPath = entry.arguments?.getString("chapterPath").orEmpty()
                ReaderScreen(
                    identity = identity,
                    novelPath = novelPath,
                    chapterPath = chapterPath,
                    onBack = { navController.popBackStack() },
                    // Footer navigation replaces the current chapter entry so
                    // system back from any chapter returns to the details
                    // screen.
                    onSwitchChapter = { nextChapterPath ->
                        val current = navController.currentBackStackEntry?.destination?.id
                        navController.navigate(
                            "reader/$identity/${Uri.encode(novelPath)}/${Uri.encode(nextChapterPath)}",
                        ) {
                            if (current != null) {
                                popUpTo(current) { inclusive = true }
                            }
                        }
                    },
                )
            }
            composable(AnansiTab.MORE.route) {
                MoreScreen(
                    onOpenAppearance = { navController.navigate(APPEARANCE_ROUTE) },
                    onOpenAudio = { navController.navigate(AUDIO_ROUTE) },
                    onOpenBackup = { navController.navigate(BACKUP_ROUTE) },
                )
            }
            composable(APPEARANCE_ROUTE) {
                AppearanceScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onSetThemeMode = onSetThemeMode,
                    onSetSeedTheme = onSetSeedTheme,
                    onSetPinnedTabsOnly = onSetPinnedTabsOnly,
                )
            }
            composable(AUDIO_ROUTE) {
                AudioSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(BACKUP_ROUTE) {
                BackupScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    // Tapping the media notification returns to the reader at the
    // spoken sentence. A reader-route tap replaces the open chapter entry
    // (single top), mirroring the reader's own chapter switching.
    LaunchedEffect(pendingReaderRoute) {
        val route = pendingReaderRoute ?: return@LaunchedEffect
        navController.navigate(route) { launchSingleTop = true }
        onReaderRouteConsumed()
    }
}

private fun NavHostController.navigateToTab(tab: AnansiTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
