package com.unchunks.echomark.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.unchunks.echomark.ui.bookmark.BookmarkListScreen
import com.unchunks.echomark.ui.bookmark.BookmarkViewModel
import com.unchunks.echomark.ui.tags.TagManagementScreen
import com.unchunks.echomark.ui.chat.ChatScreen
import com.unchunks.echomark.ui.chat.ChatViewModel
import com.unchunks.echomark.ui.chat.ConversationListScreen
import com.unchunks.echomark.ui.detail.BookmarkDetailScreen
import com.unchunks.echomark.ui.onboarding.OnboardingExit
import com.unchunks.echomark.ui.onboarding.OnboardingScreen
import com.unchunks.echomark.ui.settings.SettingsScreen
import com.unchunks.echomark.ui.settings.ai.AiSettingsScreen
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import kotlinx.coroutines.flow.first

/** ボトムバーに並ぶトップレベル画面。route は文字列ルート。選択中は塗りのアイコンにする。 */
private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    BOOKMARKS("bookmarks", "ブックマーク", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    CHAT("chat", "チャット", Icons.Filled.Forum, Icons.Outlined.Forum),
    SETTINGS("settings", "設定", Icons.Filled.Settings, Icons.Outlined.Settings)
}

private const val CHAT_NEW_ROUTE = "chat/new"

private val topLevelRoutes = TopLevelDestination.entries.map { it.route }.toSet()

/** トップレベル画面(ボトムバーを出す画面)か。起動直後で未確定(null)のときも出しておく */
private fun NavDestination?.isTopLevel(): Boolean = this == null || route in topLevelRoutes

// 画面遷移アニメーション。控えめに、タブ間はフェード、サブ画面は少しだけ横から入る
private const val TRANSITION_MILLIS = 250

private val fadeEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(TRANSITION_MILLIS))
}
private val fadeExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(TRANSITION_MILLIS))
}
private val subScreenEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideIntoContainer(
        AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(TRANSITION_MILLIS),
        initialOffset = { it / 6 }
    ) + fadeIn(tween(TRANSITION_MILLIS))
}
private val subScreenPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutOfContainer(
        AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(TRANSITION_MILLIS),
        targetOffset = { it / 6 }
    ) + fadeOut(tween(TRANSITION_MILLIS))
}

/**
 * アプリ全体のナビゲーション。
 * トップレベル画面ではボトムバーを出し、詳細・チャット個別などのサブ画面ではボトムバーを隠して全画面にする。
 *
 * インセット: この Scaffold がシステムバー(とボトムバー)の分の余白を付けて consumeWindowInsets するため、
 * 各画面の中の Scaffold / TopAppBar / imePadding は残りの分だけを足す(二重の余白にならない)。
 *
 * @param launchTarget 起動直後に開く画面(アプリショートカット・オンボーディングの続き)。開いたら [onLaunchTargetHandled] を呼ぶ
 */
@Composable
fun EchoMarkNavHost(
    launchTarget: LaunchTarget? = null,
    onLaunchTargetHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // 一覧の上に積んで開くので、戻ると一覧に戻る(チャット・AI 設定はそれぞれのタブを経由する)
    LaunchedEffect(launchTarget) {
        if (launchTarget == null) return@LaunchedEffect
        // NavHost がグラフを設定し、開始画面を表示するまで待つ(それより前の navigate は失敗する)
        navController.currentBackStackEntryFlow.first()
        when (launchTarget) {
            LaunchTarget.NEW_CHAT -> {
                navController.navigate(TopLevelDestination.CHAT.route)
                navController.navigate(CHAT_NEW_ROUTE)
            }
            LaunchTarget.AI_SETTINGS -> {
                navController.navigate(TopLevelDestination.SETTINGS.route)
                navController.navigate(Routes.AI_SETTINGS)
            }
        }
        onLaunchTargetHandled()
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = currentDestination.isTopLevel(),
                enter = slideInVertically(tween(TRANSITION_MILLIS)) { it } + fadeIn(tween(TRANSITION_MILLIS)),
                exit = slideOutVertically(tween(TRANSITION_MILLIS)) { it } + fadeOut(tween(TRANSITION_MILLIS))
            ) {
                EchoMarkNavigationBar(
                    selectedRoute = TopLevelDestination.entries.firstOrNull { destination ->
                        currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    }?.route,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            // スタックが積み上がらないよう、開始地点までを1つにまとめる
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.BOOKMARKS.route,
            // Scaffold が反映済みのインセットを消費し、画面側の imePadding との二重余白を防ぐ
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            enterTransition = fadeEnter,
            exitTransition = fadeExit,
            popEnterTransition = fadeEnter,
            popExitTransition = fadeExit
        ) {
            composable(TopLevelDestination.BOOKMARKS.route) {
                BookmarkListScreen(
                    onOpenBookmark = { navController.navigate(Routes.bookmarkDetail(it)) },
                    onOpenTagManagement = { navController.navigate(Routes.TAGS) }
                )
            }
            // タグ管理。タグをタップしたら、一覧の SavedStateHandle にタグ ID を渡して一覧へ戻る
            composable(
                route = Routes.TAGS,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                TagManagementScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTag = { tagId ->
                        val bookmarksRoute = TopLevelDestination.BOOKMARKS.route
                        runCatching { navController.getBackStackEntry(bookmarksRoute) }.getOrNull()
                            ?.savedStateHandle?.set(BookmarkViewModel.KEY_SELECT_TAG_ID, tagId)
                        navController.popBackStack(bookmarksRoute, inclusive = false)
                    }
                )
            }
            // チャットタブ = 会話一覧。"chat/new" は初回送信時に会話を作成、"chat/{conversationId}" は再開
            composable(TopLevelDestination.CHAT.route) {
                ConversationListScreen(
                    onOpenConversation = { id -> navController.navigate("chat/$id") },
                    onNewConversation = { navController.navigate(CHAT_NEW_ROUTE) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
            composable(
                route = CHAT_NEW_ROUTE,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBookmark = { id -> navController.navigate(Routes.bookmarkDetail(id)) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
            // ブックマーク詳細の「AIに質問」から開く、そのブックマークを必ず文脈に含める新規チャット
            composable(
                route = Routes.CHAT_NEW_ABOUT_BOOKMARK,
                arguments = listOf(navArgument(Routes.ARG_ABOUT_BOOKMARK_ID) { type = NavType.LongType }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBookmark = { id -> navController.navigate(Routes.bookmarkDetail(id)) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
            composable(
                route = "chat/{${ChatViewModel.ARG_CONVERSATION_ID}}",
                arguments = listOf(navArgument(ChatViewModel.ARG_CONVERSATION_ID) { type = NavType.LongType }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBookmark = { id -> navController.navigate(Routes.bookmarkDetail(id)) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                    onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) }
                )
            }
            composable(
                route = Routes.ONBOARDING,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                OnboardingScreen(
                    onFinish = { exit ->
                        navController.popBackStack()
                        if (exit == OnboardingExit.AI_SETTINGS) navController.navigate(Routes.AI_SETTINGS)
                    }
                )
            }
            composable(Routes.AI_SETTINGS) {
                AiSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.BOOKMARK_DETAIL,
                arguments = listOf(navArgument("bookmarkId") { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = Routes.BOOKMARK_DEEP_LINK }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                BookmarkDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBookmark = { navController.navigate(Routes.bookmarkDetail(it)) },
                    onAskAi = { navController.navigate(Routes.chatAboutBookmark(it)) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
        }
    }
}

/**
 * ボトムバー。[selectedRoute] のタブを選択状態(塗りアイコン)にし、タップで [onNavigate] にルートを渡す。
 * 状態を受け取るだけにしてあるので、スクリーンショットテストで単体描画できる。
 */
@Composable
internal fun EchoMarkNavigationBar(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination.route == selectedRoute
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination.route) },
                // ラベルを常に表示しているので、アイコン自体は読み上げない
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = null
                    )
                },
                label = { Text(destination.label) }
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun EchoMarkNavigationBarPreview() {
    EchoMarkTheme {
        EchoMarkNavigationBar(selectedRoute = TopLevelDestination.BOOKMARKS.route, onNavigate = {})
    }
}
