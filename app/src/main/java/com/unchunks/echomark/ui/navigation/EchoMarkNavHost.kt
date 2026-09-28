package com.unchunks.echomark.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.unchunks.echomark.ui.bookmark.BookmarkListScreen
import com.unchunks.echomark.ui.chat.ChatScreen
import com.unchunks.echomark.ui.chat.ChatViewModel
import com.unchunks.echomark.ui.chat.ConversationListScreen
import com.unchunks.echomark.ui.settings.SettingsScreen

/** ボトムバーに並ぶトップレベル画面。route は文字列ルート。 */
private enum class TopLevelDestination(val route: String, val label: String) {
    BOOKMARKS("bookmarks", "ブックマーク"),
    CHAT("chat", "チャット"),
    SETTINGS("settings", "設定")
}

private const val CHAT_NEW_ROUTE = "chat/new"

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
                        // チャットは "chat/..." の子画面でもタブを選択状態にする
                        selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route || it.route?.startsWith(destination.route + "/") == true
                        } == true,
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
            // Scaffold が反映済みのインセットを消費し、画面側の imePadding との二重余白を防ぐ
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
        ) {
            composable(TopLevelDestination.BOOKMARKS.route) { BookmarkListScreen() }
            // チャットタブ = 会話一覧。"chat/new" は初回送信時に会話を作成、"chat/{conversationId}" は再開
            composable(TopLevelDestination.CHAT.route) {
                ConversationListScreen(
                    onOpenConversation = { id -> navController.navigate("chat/$id") },
                    onNewConversation = { navController.navigate(CHAT_NEW_ROUTE) }
                )
            }
            composable(CHAT_NEW_ROUTE) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBookmark = { id -> navController.navigate("bookmark/$id") } // TODO: Routes.bookmarkDetail に置き換え
                )
            }
            composable(
                route = "chat/{${ChatViewModel.ARG_CONVERSATION_ID}}",
                arguments = listOf(navArgument(ChatViewModel.ARG_CONVERSATION_ID) { type = NavType.LongType })
            ) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBookmark = { id -> navController.navigate("bookmark/$id") } // TODO: Routes.bookmarkDetail に置き換え
                )
            }
            composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}
