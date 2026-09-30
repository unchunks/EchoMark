package com.unchunks.echomark.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.unchunks.echomark.ui.bookmark.BookmarkListScreen
import com.unchunks.echomark.ui.chat.ChatScreen
import com.unchunks.echomark.ui.settings.SettingsScreen

/** ボトムバーに並ぶトップレベル画面。route は文字列ルート。 */
private enum class TopLevelDestination(val route: String, val label: String) {
    BOOKMARKS("bookmarks", "ブックマーク"),
    CHAT("chat", "チャット"),
    SETTINGS("settings", "設定")
}

@Composable
fun EchoMarkNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                // スタックが積み上がらないよう、開始地点までを1つにまとめる
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.BOOKMARKS.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopLevelDestination.BOOKMARKS.route) { BookmarkListScreen() }
            composable(TopLevelDestination.CHAT.route) { ChatScreen() }
            composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}
