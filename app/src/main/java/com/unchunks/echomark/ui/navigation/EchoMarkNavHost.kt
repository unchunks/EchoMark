package com.unchunks.echomark.ui.navigation

import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.unchunks.echomark.ui.discover.ResourceFinderScreen
import com.unchunks.echomark.ui.categories.CategoryManagementScreen
import com.unchunks.echomark.ui.tags.TagManagementScreen
import com.unchunks.echomark.ui.chat.ChatScreen
import com.unchunks.echomark.ui.chat.ChatViewModel
import com.unchunks.echomark.ui.detail.BookmarkDetailScreen
import com.unchunks.echomark.ui.detail.BookmarkDetailViewModel
import com.unchunks.echomark.ui.collections.CollectionDetailScreen
import com.unchunks.echomark.ui.collections.CollectionDetailViewModel
import com.unchunks.echomark.ui.collections.CollectionsScreen
import com.unchunks.echomark.ui.discover.DiscoverScreen
import com.unchunks.echomark.ui.explore.ExploreActions
import com.unchunks.echomark.ui.explore.ExploreScreen
import com.unchunks.echomark.ui.linkcheck.BrokenLinksScreen
import com.unchunks.echomark.ui.video.OfflineVideosScreen
import com.unchunks.echomark.ui.duplicates.DuplicatesScreen
import com.unchunks.echomark.ui.graph.GraphScreen
import com.unchunks.echomark.ui.help.AppHelpScreen
import com.unchunks.echomark.ui.insights.InsightsScreen
import com.unchunks.echomark.ui.places.PlacesScreen
import com.unchunks.echomark.ui.review.ReviewHubScreen
import com.unchunks.echomark.ui.review.ReviewSessionScreen
import com.unchunks.echomark.ui.onboarding.OnboardingExit
import com.unchunks.echomark.ui.onboarding.OnboardingScreen
import com.unchunks.echomark.ui.podcast.PodcastCreateScreen
import com.unchunks.echomark.ui.podcast.PodcastEpisodesScreen
import com.unchunks.echomark.ui.podcast.PodcastPlayerScreen
import com.unchunks.echomark.ui.podcast.ReadingDictionaryScreen
import com.unchunks.echomark.ui.settings.SettingsScreen
import com.unchunks.echomark.ui.settings.ai.AiSettingsScreen
import com.unchunks.echomark.ui.settings.queue.ProcessingQueueScreen
import kotlinx.coroutines.flow.first

// 画面遷移アニメーション。控えめに、タブ間はフェード、サブ画面は少しだけ横から入る
internal const val TRANSITION_MILLIS = 250

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
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                    onOpenDiscover = { navController.navigate(Routes.DISCOVER) },
                    onOpenDuplicates = { navController.navigate(Routes.DUPLICATES) },
                    onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
                    onOpenReview = { navController.navigate(Routes.REVIEW) },
                    onOpenCollections = { navController.navigate(Routes.COLLECTIONS) },
                    onOpenBrokenLinks = { navController.navigate(Routes.BROKEN_LINKS) },
                    onOpenPodcasts = { navController.navigate(Routes.PODCASTS) },
                    onMakePodcast = { ids -> navController.navigate(Routes.podcastNew(ids)) }
                )
            }
            // ポッドキャストのエピソードの一覧(一覧のメニュー・できた通知から開く)
            composable(
                route = Routes.PODCASTS,
                deepLinks = listOf(navDeepLink { uriPattern = Routes.PODCASTS_DEEP_LINK }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                PodcastEpisodesScreen(
                    onBack = navController.popBackAction(),
                    onOpenDictionary = { navController.navigate(Routes.READING_DICTIONARY) },
                    onPlay = { id -> navController.navigate(Routes.podcastPlayer(id)) }
                )
            }
            // ポッドキャストの再生(一覧の「聞く」・再生の通知から開く)。開くと再生する(もう再生していれば続ける)
            composable(
                route = Routes.PODCAST_PLAYER,
                arguments = listOf(navArgument(Routes.ARG_PODCAST_EPISODE_ID) { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = Routes.PODCAST_PLAYER_DEEP_LINK }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                PodcastPlayerScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = { navController.navigate(Routes.bookmarkDetail(it)) }
                )
            }
            // ポッドキャストを作る(詳細画面・複数選択から開く)。作り始めたら、この画面の代わりに一覧を開く
            composable(
                route = Routes.PODCAST_NEW,
                arguments = listOf(navArgument(Routes.ARG_PODCAST_BOOKMARK_IDS) { type = NavType.StringType; defaultValue = "" }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                PodcastCreateScreen(
                    onClose = navController.popBackAction(),
                    onStarted = {
                        navController.navigate(Routes.PODCASTS) {
                            popUpTo(Routes.PODCAST_NEW) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            // 資料を探す。AI が選んだ資料をまとめて追加し、戻ると一覧に追加したものが並ぶ
            composable(
                route = Routes.DISCOVER,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                DiscoverScreen(
                    onBack = navController.popBackAction(),
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                    onOpenPageOrFiles = { navController.navigate(Routes.DISCOVER_FROM_PAGE_OR_FILES) }
                )
            }
            // 一覧ページのリンク・端末内のファイルから、AI が選んで保存する(資料を探す画面から開く)
            composable(
                route = Routes.DISCOVER_FROM_PAGE_OR_FILES,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ResourceFinderScreen(onBack = navController.popBackAction())
            }
            // 重複の整理と関連のまとまり。ブックマークをタップしたら詳細を開く
            composable(
                route = Routes.DUPLICATES,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                DuplicatesScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.bookmarkDetail(id)) }
                )
            }
            // ライブラリの分析。タグをタップしたらそのタグの一覧へ、ブックマークをタップしたら詳細を開く
            composable(
                route = Routes.INSIGHTS,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                InsightsScreen(
                    onBack = navController.popBackAction(),
                    onOpenTag = dropUnlessResumedWith { tagId: Long -> navController.returnToBookmarkListWithTag(tagId) },
                    onOpenBookmark = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.bookmarkDetail(id)) },
                    onOpenGraph = dropUnlessResumed { navController.navigate(Routes.GRAPH) },
                    onOpenPlaces = dropUnlessResumed { navController.navigate(Routes.PLACES) }
                )
            }
            // ナレッジグラフ。ブックマークをタップしたら詳細を開く
            composable(
                route = Routes.GRAPH,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                GraphScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.bookmarkDetail(id)) }
                )
            }
            // 場所のあるブックマークの分布図と一覧。ブックマークをタップしたら詳細を開く
            composable(
                route = Routes.PLACES,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                PlacesScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.bookmarkDetail(id)) }
                )
            }
            // 読む・ふり返る(今週のふり返り・あとで読む・復習)。通知のディープリンクから開くと今週のふり返りの表示になる
            composable(
                route = Routes.REVIEW,
                deepLinks = listOf(navDeepLink { uriPattern = Routes.REVIEW_DEEP_LINK }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ReviewHubScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.bookmarkDetail(id)) },
                    onStartReview = dropUnlessResumed { navController.navigate(Routes.REVIEW_SESSION) }
                )
            }
            // 復習。元のブックマークを開けるが、戻ると復習の続きに戻る
            composable(
                route = Routes.REVIEW_SESSION,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ReviewSessionScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.bookmarkDetail(id)) }
                )
            }
            // コレクションの一覧と中身。中身のブックマークをタップしたら詳細を開く
            composable(
                route = Routes.COLLECTIONS,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                CollectionsScreen(
                    onBack = navController.popBackAction(),
                    onOpenCollection = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.collectionDetail(id)) }
                )
            }
            composable(
                route = Routes.COLLECTION_DETAIL,
                arguments = listOf(navArgument(CollectionDetailViewModel.ARG_COLLECTION_ID) { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = Routes.COLLECTION_DEEP_LINK }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                CollectionDetailScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.bookmarkDetail(id)) }
                )
            }
            // リンク切れの一覧。ブックマークをタップしたら詳細を開く
            composable(
                route = Routes.BROKEN_LINKS,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                BrokenLinksScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = dropUnlessResumedWith { id: Long -> navController.navigate(Routes.bookmarkDetail(id)) }
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
            // 「見つける」タブ。ここから開いた画面は、このタブの上に積む(戻るとこのタブに戻る)
            composable(TopLevelDestination.EXPLORE.route) {
                ExploreScreen(
                    actions = ExploreActions(
                        onOpenReview = dropUnlessResumed { navController.navigate(Routes.REVIEW) },
                        onOpenDiscover = dropUnlessResumed { navController.navigate(Routes.DISCOVER) },
                        onOpenInsights = dropUnlessResumed { navController.navigate(Routes.INSIGHTS) },
                        onOpenCollections = dropUnlessResumed { navController.navigate(Routes.COLLECTIONS) },
                        onOpenTags = dropUnlessResumed { navController.navigate(Routes.TAGS) },
                        onOpenDuplicates = dropUnlessResumed { navController.navigate(Routes.DUPLICATES) },
                        onOpenBrokenLinks = dropUnlessResumed { navController.navigate(Routes.BROKEN_LINKS) },
                        onOpenPodcasts = dropUnlessResumed { navController.navigate(Routes.PODCASTS) }
                    )
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                    onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                    onOpenAppHelp = { navController.navigate(Routes.APP_HELP) },
                    onOpenProcessingQueue = { navController.navigate(Routes.PROCESSING_QUEUE) },
                    onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                    onOpenOfflineVideos = { navController.navigate(Routes.OFFLINE_VIDEOS) },
                    layout = layout
                )
            }
            // 使い方を AI に聞く(設定の「このアプリについて」の欄から開く)
            composable(
                route = Routes.APP_HELP,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                AppHelpScreen(
                    onBack = navController.popBackAction(),
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                    onNavigate = { action -> navController.openFromAppHelp(action) }
                )
            }
            // オフラインの動画(設定の「データ」の欄から開く)
            composable(
                route = Routes.OFFLINE_VIDEOS,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                OfflineVideosScreen(onBack = navController.popBackAction())
            }
            // カテゴリの管理(設定の「AI」の欄から開く)
            composable(
                route = Routes.CATEGORIES,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                CategoryManagementScreen(onBack = navController.popBackAction())
            }
            // ポッドキャストの読みの辞書
            composable(
                route = Routes.READING_DICTIONARY,
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ReadingDictionaryScreen(onBack = navController.popBackAction())
            }
            // 処理の状況(設定の「処理」の欄・まとめての処理の通知から開く)
            composable(
                route = Routes.PROCESSING_QUEUE,
                deepLinks = listOf(navDeepLink { uriPattern = Routes.PROCESSING_QUEUE_DEEP_LINK }),
                enterTransition = subScreenEnter,
                popExitTransition = subScreenPopExit
            ) {
                ProcessingQueueScreen(
                    onBack = navController.popBackAction(),
                    onOpenBookmark = { id -> navController.navigate(Routes.bookmarkDetail(id)) }
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
                    onMakePodcast = { navController.navigate(Routes.podcastNew(listOf(it))) },
                    onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) }
                )
            }
        }
    }
}

/**
 * 画面の下端のうち、中身の上に重ねたボトムバー(クリアのガラスのバー)に覆われる高さ。重ねていなければ 0。
 * ボトムバーのある画面は、Scaffold の contentWindowInsets に足して、追加ボタン・スナックバー・最後の行がバーに隠れないようにする。
 * 一覧のようにバーの裏まで中身を流したい画面は、これを余白(Modifier.padding)ではなくリストの contentPadding に足す。
 */
val LocalBottomBarOverlap = compositionLocalOf { 0.dp }
