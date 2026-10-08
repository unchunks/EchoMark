package com.unchunks.echomark.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.unchunks.echomark.ui.bookmark.ListLaunchAction
import com.unchunks.echomark.ui.tags.TagManagementScreen
import com.unchunks.echomark.ui.chat.ChatScreen
import com.unchunks.echomark.ui.chat.ChatViewModel
import com.unchunks.echomark.ui.detail.BookmarkDetailScreen
import com.unchunks.echomark.ui.detail.BookmarkDetailViewModel
import com.unchunks.echomark.ui.onboarding.OnboardingExit
import com.unchunks.echomark.ui.onboarding.OnboardingScreen
import com.unchunks.echomark.ui.settings.SettingsScreen
import com.unchunks.echomark.ui.settings.ai.AiSettingsScreen
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import kotlinx.coroutines.flow.first

/** ボトムバー・ナビゲーションレールに並ぶトップレベル画面。route は文字列ルート。選択中は塗りのアイコンにする。 */
private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    BOOKMARKS(BOOKMARKS_ROUTE, "ブックマーク", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    CHAT(CHAT_ROUTE, "チャット", Icons.Filled.Forum, Icons.Outlined.Forum),
    SETTINGS("settings", "設定", Icons.Filled.Settings, Icons.Outlined.Settings)
}

/** チャットタブ(会話の一覧) */
internal const val CHAT_ROUTE = "chat"

internal const val CHAT_NEW_ROUTE = "chat/new"

/** 既存の会話を開き直す */
internal const val CHAT_CONVERSATION_ROUTE = "chat/{${ChatViewModel.ARG_CONVERSATION_ID}}"

private val topLevelRoutes = TopLevelDestination.entries.map { it.route }.toSet()

/**
 * 表示中の画面 [currentRoute] がどのタブに属するか(タブを選択状態にする)。
 * タブのサブ画面("settings/ai"・"chat/new" など)はルートの先頭で決める。詳細・タグ管理のようにどのタブからも開く画面は、
 * 開いたタブで決める(タブの切り替えは開始画面の上に1つのタブだけを積むので、積み重ねにある開始画面以外のタブ、無ければ開始画面)。
 * @param isInBackStack ルートの画面が積み重ねにあるか
 */
internal fun selectedTopLevelRoute(currentRoute: String?, isInBackStack: (String) -> Boolean): String? {
    if (currentRoute == null) return null
    TopLevelDestination.entries
        .firstOrNull { currentRoute == it.route || currentRoute.startsWith(it.route + "/") }
        ?.let { return it.route }
    return TopLevelDestination.entries
        .filter { it != TopLevelDestination.BOOKMARKS }
        .firstOrNull { isInBackStack(it.route) }?.route
        ?: TopLevelDestination.BOOKMARKS.route
}

/** [route] の画面が積み重ねにあるか */
private fun NavController.isInBackStack(route: String): Boolean =
    runCatching { getBackStackEntry(route) }.isSuccess

/** トップレベル画面(ボトムバーを出す画面)か。起動直後で未確定(null)のときも出しておく */
private fun NavDestination?.isTopLevel(): Boolean = this == null || route in topLevelRoutes

/**
 * ナビゲーションを出すか。
 * ボトムバーはトップレベル画面だけに出し、サブ画面では隠して縦の領域を広く使う。
 * ナビゲーションレールは横に並ぶので縦の領域を取らず、広い画面では左右に余裕があるため、サブ画面でも出したままにする
 * (画面を移るたびに中身が左右にずれず、どの画面からでもタブを切り替えられる)。
 * はじめにの案内だけは、案内に集中できるよう全画面にする。
 */
private fun NavDestination?.showsNavigation(navigation: NavigationLayout): Boolean = when (navigation) {
    NavigationLayout.BAR -> isTopLevel()
    NavigationLayout.RAIL -> this?.route != Routes.ONBOARDING
}

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
 * 画面の広さに合わせて([AdaptiveLayout])、狭い画面ではボトムバー、広い画面ではナビゲーションレールでタブを切り替える。
 * ボトムバーはトップレベル画面だけに出し、詳細・チャット個別などのサブ画面では隠して全画面にする。
 *
 * 広い画面(2 画面表示)では、ブックマークとチャットのタブが一覧と詳細を左右に並べる
 * ([BookmarkListDetailDestination]・[ChatListDetailDestination])。詳細・会話のルートは狭い画面・通知・チャットの引用などから
 * これまでどおり開け、2 画面表示になったときに一覧から開いていたものは右側のペインへ移す([moveDetailRouteIntoPane])。
 *
 * インセットの扱いは [EchoMarkAppScaffold] を参照。
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
    val layout = currentAdaptiveLayout()

    LaunchedEffect(launchTarget) {
        if (launchTarget == null) return@LaunchedEffect
        // NavHost がグラフを設定し、開始画面を表示するまで待つ(それより前の navigate は失敗する)
        navController.currentBackStackEntryFlow.first()
        navController.openLaunchTarget(launchTarget)
        onLaunchTargetHandled()
    }

    // 2 画面表示のときに一覧の上へ詳細・会話のルートが開いたら(折りたたみを開いた・通知から開いた)、右側のペインへ移す
    LaunchedEffect(layout.isTwoPane, backStackEntry) {
        if (layout.isTwoPane && backStackEntry != null) navController.moveDetailRouteIntoPane()
    }

    EchoMarkAppScaffold(
        showNavigation = currentDestination.showsNavigation(layout.navigation),
        navigation = layout.navigation,
        selectedRoute = selectedTopLevelRoute(currentDestination?.route) { route ->
            navController.isInBackStack(route)
        },
        onNavigate = { route -> navController.navigateToTopLevel(route) }
    ) { contentModifier ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.BOOKMARKS.route,
            modifier = contentModifier,
            enterTransition = fadeEnter,
            exitTransition = fadeExit,
            popEnterTransition = fadeEnter,
            popExitTransition = fadeExit
        ) {
            composable(TopLevelDestination.BOOKMARKS.route) { entry ->
                BookmarkListDetailDestination(
                    entry = entry,
                    layout = layout,
                    isCurrentEntry = { navController.currentBackStackEntry?.id == entry.id },
                    onOpenBookmarkRoute = { navController.navigate(Routes.bookmarkDetail(it)) },
                    onOpenTagManagement = { navController.navigate(Routes.TAGS) },
                    onAskAi = { navController.navigate(Routes.chatAboutBookmark(it)) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
            // タグ管理。タグをタップしたら、そのタグで絞り込んだ一覧へ戻る
            composable(
                route = Routes.TAGS,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                TagManagementScreen(
                    onBack = navController.popBackAction(),
                    onOpenTag = dropUnlessResumedWith { tagId: Long -> navController.returnToBookmarkListWithTag(tagId) }
                )
            }
            // チャットタブ = 会話一覧(広い画面では右に会話を並べる)。
            // "chat/new" は初回送信時に会話を作成、"chat/{conversationId}" は再開
            composable(TopLevelDestination.CHAT.route) { entry ->
                ChatListDetailDestination(
                    entry = entry,
                    layout = layout,
                    isCurrentEntry = { navController.currentBackStackEntry?.id == entry.id },
                    onOpenConversationRoute = { id -> navController.navigate("chat/$id") },
                    onNewConversationRoute = { navController.navigate(CHAT_NEW_ROUTE) },
                    onOpenBookmark = { id -> navController.navigate(Routes.bookmarkDetail(id)) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
            composable(
                route = CHAT_NEW_ROUTE,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ChatScreen(
                    onBack = navController.popBackAction(),
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
                    onBack = navController.popBackAction(),
                    onOpenBookmark = { id -> navController.navigate(Routes.bookmarkDetail(id)) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
            composable(
                route = CHAT_CONVERSATION_ROUTE,
                arguments = listOf(navArgument(ChatViewModel.ARG_CONVERSATION_ID) { type = NavType.LongType }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ChatScreen(
                    onBack = navController.popBackAction(),
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
                    onFinish = dropUnlessResumedWith { exit: OnboardingExit ->
                        navController.popBackStack()
                        if (exit == OnboardingExit.AI_SETTINGS) navController.navigate(Routes.AI_SETTINGS)
                    }
                )
            }
            composable(Routes.AI_SETTINGS) {
                AiSettingsScreen(onBack = navController.popBackAction())
            }
            composable(
                route = Routes.BOOKMARK_DETAIL,
                arguments = listOf(navArgument(BookmarkDetailViewModel.ARG_BOOKMARK_ID) { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = Routes.BOOKMARK_DEEP_LINK }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                BookmarkDetailScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = { navController.navigate(Routes.bookmarkDetail(it)) },
                    onAskAi = { navController.navigate(Routes.chatAboutBookmark(it)) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
        }
    }
}

/**
 * アプリ全体の枠。[navigation] に応じて、下にボトムバー([NavigationLayout.BAR])か左にナビゲーションレール
 * ([NavigationLayout.RAIL])を出す。[showNavigation] が false なら隠す(サブ画面のボトムバー・はじめにの案内)。
 *
 * インセット: 左右と下(ナビゲーションバー・ボトムバー)の分だけ余白を付けて consumeWindowInsets する。
 * 上(ステータスバー)は消費せず、各画面の TopAppBar(既定の windowInsets)が自分の背景色で受け持つ。
 * ここで上まで消費すると、ステータスバーの帯がこの Scaffold の背景のままになり、スクロールで色が変わる
 * TopAppBar とつながらない。各画面の imePadding は、ここで消費した下の分を差し引いた残りだけを足す。
 * レールを出しているときは、左(画面の始まり側)の分はレールが受け持つので、中身の側では余白を付けずに消費だけする
 * (インセットはウィンドウの端からの値なので、レールの右に置いた中身でもう一度余白を付けると二重になる)。
 *
 * @param content 画面本体。渡す Modifier(余白とインセットの消費)を付けて表示すること
 */
@Composable
internal fun EchoMarkAppScaffold(
    showNavigation: Boolean,
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    navigation: NavigationLayout = NavigationLayout.BAR,
    content: @Composable (Modifier) -> Unit
) {
    val showRail = navigation == NavigationLayout.RAIL && showNavigation
    Row(Modifier.fillMaxSize()) {
        if (navigation == NavigationLayout.RAIL) {
            AnimatedVisibility(
                visible = showNavigation,
                enter = expandHorizontally(tween(TRANSITION_MILLIS)) + fadeIn(tween(TRANSITION_MILLIS)),
                exit = shrinkHorizontally(tween(TRANSITION_MILLIS)) + fadeOut(tween(TRANSITION_MILLIS))
            ) {
                EchoMarkNavigationRail(
                    selectedRoute = selectedRoute,
                    onNavigate = onNavigate,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
        val railInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
        Scaffold(
            modifier = Modifier
                .weight(1f)
                .then(if (showRail) Modifier.consumeWindowInsets(railInsets) else Modifier),
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
                if (showRail) WindowInsetsSides.End + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            ),
            bottomBar = {
                if (navigation == NavigationLayout.BAR) {
                    AnimatedVisibility(
                        visible = showNavigation,
                        enter = slideInVertically(tween(TRANSITION_MILLIS)) { it } + fadeIn(tween(TRANSITION_MILLIS)),
                        exit = slideOutVertically(tween(TRANSITION_MILLIS)) { it } + fadeOut(tween(TRANSITION_MILLIS))
                    ) {
                        EchoMarkNavigationBar(selectedRoute = selectedRoute, onNavigate = onNavigate)
                    }
                }
            }
        ) { innerPadding ->
            content(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding))
        }
    }
}

/**
 * サブ画面の「戻る」。画面が操作可能(RESUMED)なときだけ戻る。
 * 素早く2回押したときや遷移アニメーション中の2回目を無視し、開始画面まで pop して何も表示されなくなるのを防ぐ。
 */
@Composable
internal fun NavController.popBackAction(): () -> Unit = dropUnlessResumed { popBackStack() }

/** 引数つきの操作を、画面が操作可能(RESUMED)なときだけ行う([dropUnlessResumed] の引数つき版) */
@Composable
internal fun <T> dropUnlessResumedWith(block: (T) -> Unit): (T) -> Unit {
    val lifecycleOwner = LocalLifecycleOwner.current
    return { value -> if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block(value) }
}

/**
 * ボトムバーのタブ [route] を開く。タブごとの画面の積み重ねは保存し、戻ってきたときに復元する。
 * 表示中のタブをもう一度押したときは、そのタブの最初の画面に戻る(サブ画面でもタブを出すナビゲーションレール向け)。
 */
internal fun NavController.navigateToTopLevel(route: String) {
    val startDestination = graph.findStartDestination()
    if (route != startDestination.route && currentDestination?.route != route && isInBackStack(route)) {
        // 開始画面の上に積んだこのタブのサブ画面を閉じる
        popBackStack(route, inclusive = false)
        return
    }
    if (route == startDestination.route) {
        // 開始画面(一覧)のタブは、上に積んだ画面を保存して閉じるだけにする。
        // navigate(restoreState = true) で開くと、popUpTo(開始画面, saveState = true) が保存した
        // 「閉じた画面の状態」が開始画面にも紐付いているため、それが復元されて別のタブが開いてしまう
        // (タブを通らずに開いた設定・チャットから戻ったあと、一覧のタブを押すと設定やチャットになる不具合)
        popBackStack(startDestination.id, inclusive = false, saveState = true)
        return
    }
    navigate(route) {
        // スタックが積み上がらないよう、開始地点までを1つにまとめる
        popUpTo(startDestination.id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 起動直後に [target] の画面を開く。一覧の上に積んで開くので、戻ると一覧に戻る
 * (チャット・AI 設定はそれぞれのタブを経由する)。URL を追加・検索は一覧そのものの上でシート・検索モードを開く。
 */
internal fun NavController.openLaunchTarget(target: LaunchTarget) {
    when (target) {
        LaunchTarget.ADD_BOOKMARK -> openBookmarkListWith(ListLaunchAction.ADD_BOOKMARK)
        LaunchTarget.SEARCH -> openBookmarkListWith(ListLaunchAction.SEARCH)
        LaunchTarget.NEW_CHAT -> {
            navigate(TopLevelDestination.CHAT.route)
            navigate(CHAT_NEW_ROUTE)
        }
        LaunchTarget.AI_SETTINGS -> {
            navigate(TopLevelDestination.SETTINGS.route)
            navigate(Routes.AI_SETTINGS)
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
                icon = { TopLevelIcon(destination, selected) },
                label = { Text(destination.label) }
            )
        }
    }
}

/**
 * ナビゲーションレール(広い画面で左に縦に並べるタブ)。並びと見た目はボトムバー([EchoMarkNavigationBar])と同じ。
 * 項目は上から並べる(親指の届く下側ではなく、視線の起点の左上に置く)。
 */
@Composable
internal fun EchoMarkNavigationRail(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination.route == selectedRoute
            NavigationRailItem(
                selected = selected,
                onClick = { onNavigate(destination.route) },
                icon = { TopLevelIcon(destination, selected) },
                label = { Text(destination.label) }
            )
        }
    }
}

/** タブのアイコン。ラベルを常に表示しているので、アイコン自体は読み上げない */
@Composable
private fun TopLevelIcon(destination: TopLevelDestination, selected: Boolean) {
    Icon(
        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
        contentDescription = null
    )
}

@PreviewLightDark
@Composable
private fun EchoMarkNavigationBarPreview() {
    EchoMarkTheme {
        EchoMarkNavigationBar(selectedRoute = TopLevelDestination.BOOKMARKS.route, onNavigate = {})
    }
}
