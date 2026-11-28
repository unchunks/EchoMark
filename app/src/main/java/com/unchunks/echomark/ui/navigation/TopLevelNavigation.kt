package com.unchunks.echomark.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import com.unchunks.echomark.ui.chat.ChatViewModel

/** ボトムバー・ナビゲーションレールに並ぶトップレベル画面。route は文字列ルート。選択中は塗りのアイコンにする。 */
internal enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    BOOKMARKS(BOOKMARKS_ROUTE, "ブックマーク", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    CHAT(CHAT_ROUTE, "チャット", Icons.Filled.Forum, Icons.Outlined.Forum),
    EXPLORE(EXPLORE_ROUTE, "見つける", Icons.Filled.Explore, Icons.Outlined.Explore),
    SETTINGS("settings", "設定", Icons.Filled.Settings, Icons.Outlined.Settings)
}

/** 「見つける」タブ(読む・ふり返る、資料を探す、分析、コレクション、タグ、重複、リンク切れへの入口) */
internal const val EXPLORE_ROUTE = "explore"

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
internal fun NavController.isInBackStack(route: String): Boolean =
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
internal fun NavDestination?.showsNavigation(navigation: NavigationLayout): Boolean = when (navigation) {
    NavigationLayout.BAR -> isTopLevel()
    NavigationLayout.RAIL -> this?.route != Routes.ONBOARDING
}
