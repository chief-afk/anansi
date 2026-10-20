package com.chief.anansi.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chief.anansi.settings.SeedTheme
import com.chief.anansi.settings.ThemeMode
import com.chief.anansi.ui.browse.BrowseScreen
import com.chief.anansi.ui.history.HistoryScreen
import com.chief.anansi.ui.library.LibraryScreen
import com.chief.anansi.ui.more.AppearanceScreen
import com.chief.anansi.ui.more.MoreScreen
import com.chief.anansi.ui.updates.UpdatesScreen

private const val APPEARANCE_ROUTE = "appearance"

@Composable
fun AnansiApp(
    state: SettingsUiState,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetSeedTheme: (SeedTheme) -> Unit,
    onSetPinnedTabsOnly: (Boolean) -> Unit,
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
                LibraryScreen(onBrowseSources = { navController.navigateToTab(AnansiTab.BROWSE) })
            }
            composable(AnansiTab.UPDATES.route) { UpdatesScreen() }
            composable(AnansiTab.HISTORY.route) { HistoryScreen() }
            composable(AnansiTab.BROWSE.route) { BrowseScreen() }
            composable(AnansiTab.MORE.route) {
                MoreScreen(onOpenAppearance = { navController.navigate(APPEARANCE_ROUTE) })
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
        }
    }
}

private fun NavHostController.navigateToTab(tab: AnansiTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
