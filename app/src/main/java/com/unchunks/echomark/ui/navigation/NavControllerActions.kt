package com.unchunks.echomark.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.unchunks.echomark.ui.bookmark.ListLaunchAction
import com.unchunks.echomark.domain.help.AppAction
import com.unchunks.echomark.domain.help.AppScreen

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
        LaunchTarget.UNREAD -> openBookmarkListWith(ListLaunchAction.UNREAD)
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
 * 使い方の AI から頼まれた画面を開く。タブ(一覧・チャット・見つける・設定)はそのタブへ切り替え、
 * それ以外の画面は使い方の画面の上に積む(戻ると、続けて質問できるよう使い方の画面に戻る)。
 * 画面を開く操作([AppAction.Open]・[AppAction.Search])以外は何もしない。
 */
internal fun NavController.openFromAppHelp(action: AppAction) {
    when (action) {
        is AppAction.Search -> openBookmarkListWithSearch(action.query)
        is AppAction.Open -> when (action.screen) {
            AppScreen.BOOKMARKS -> navigateToTopLevel(BOOKMARKS_ROUTE)
            AppScreen.ADD_BOOKMARK -> openBookmarkListWith(ListLaunchAction.ADD_BOOKMARK)
            AppScreen.UNREAD -> openBookmarkListWith(ListLaunchAction.UNREAD)
            AppScreen.NEW_CHAT -> {
                navigateToTopLevel(CHAT_ROUTE)
                navigate(CHAT_NEW_ROUTE)
            }
            AppScreen.EXPLORE -> navigateToTopLevel(EXPLORE_ROUTE)
            AppScreen.SETTINGS -> navigateToTopLevel(TopLevelDestination.SETTINGS.route)
            AppScreen.AI_SETTINGS -> navigate(Routes.AI_SETTINGS)
            AppScreen.CATEGORIES -> navigate(Routes.CATEGORIES)
            AppScreen.PROCESSING_QUEUE -> navigate(Routes.PROCESSING_QUEUE)
            AppScreen.OFFLINE_VIDEOS -> navigate(Routes.OFFLINE_VIDEOS)
            AppScreen.ONBOARDING -> navigate(Routes.ONBOARDING)
            AppScreen.REVIEW -> navigate(Routes.REVIEW)
            AppScreen.DISCOVER -> navigate(Routes.DISCOVER)
            AppScreen.INSIGHTS -> navigate(Routes.INSIGHTS)
            AppScreen.GRAPH -> navigate(Routes.GRAPH)
            AppScreen.PLACES -> navigate(Routes.PLACES)
            AppScreen.COLLECTIONS -> navigate(Routes.COLLECTIONS)
            AppScreen.TAGS -> navigate(Routes.TAGS)
            AppScreen.DUPLICATES -> navigate(Routes.DUPLICATES)
            AppScreen.BROKEN_LINKS -> navigate(Routes.BROKEN_LINKS)
            AppScreen.PODCASTS -> navigate(Routes.PODCASTS)
            AppScreen.READING_DICTIONARY -> navigate(Routes.READING_DICTIONARY)
        }
        else -> Unit
    }
}
