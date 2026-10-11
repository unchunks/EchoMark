package com.unchunks.echomark.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.unchunks.echomark.ui.bookmark.BookmarkListScreen
import com.unchunks.echomark.ui.bookmark.BookmarkViewModel
import com.unchunks.echomark.ui.bookmark.ListLaunchAction
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * 一覧(開始画面)へ、ほかの画面やアプリショートカットから渡す依頼。
 *
 * 一覧の [NavBackStackEntry.savedStateHandle] に入れて届ける。この SavedStateHandle は
 * hiltViewModel() で作る [BookmarkViewModel] の SavedStateHandle とは別物なので、ViewModel が直接は読めない。
 * そのため一覧の画面([BookmarkListDestination])がエントリ側を購読し、ViewModel の関数を呼んで渡す。
 */
internal object BookmarkListRequests {
    /** タグ管理画面から「このタグで絞り込む」 */
    const val KEY_SELECT_TAG_ID = "selectTagId"

    /** 起動直後の操作([ListLaunchAction] の name) */
    const val KEY_LAUNCH_ACTION = "launchAction"
}

/** 一覧のルート(開始画面) */
internal const val BOOKMARKS_ROUTE = "bookmarks"

/** そのタグで絞り込んだ一覧へ戻る(タグ管理画面から)。一覧がバックスタックに無ければ何もしない */
internal fun NavController.returnToBookmarkListWithTag(tagId: Long) {
    val entry = runCatching { getBackStackEntry(BOOKMARKS_ROUTE) }.getOrNull() ?: return
    entry.savedStateHandle[BookmarkListRequests.KEY_SELECT_TAG_ID] = tagId
    popBackStack(BOOKMARKS_ROUTE, inclusive = false)
}

/** 一覧まで戻り、追加シート・検索モードを開くよう伝える(ショートカット・ウィジェットから) */
internal fun NavController.openBookmarkListWith(action: ListLaunchAction) {
    popBackStack(BOOKMARKS_ROUTE, inclusive = false)
    val entry = runCatching { getBackStackEntry(BOOKMARKS_ROUTE) }.getOrNull() ?: return
    entry.savedStateHandle[BookmarkListRequests.KEY_LAUNCH_ACTION] = action.name
}

/**
 * [handle](一覧のエントリの SavedStateHandle)に届いた依頼を1回ずつ取り出して渡す。
 * 取り出したら消すので、画面に戻ったときや作り直したときに同じ依頼を繰り返さない。
 */
@Composable
internal fun BookmarkListRequestsEffect(
    handle: SavedStateHandle,
    onSelectTag: (Long) -> Unit,
    onLaunchAction: (ListLaunchAction) -> Unit
) {
    val currentOnSelectTag by rememberUpdatedState(onSelectTag)
    val currentOnLaunchAction by rememberUpdatedState(onLaunchAction)
    LaunchedEffect(handle) {
        launch {
            handle.getStateFlow<Long?>(BookmarkListRequests.KEY_SELECT_TAG_ID, null).filterNotNull().collect { tagId ->
                handle[BookmarkListRequests.KEY_SELECT_TAG_ID] = null
                currentOnSelectTag(tagId)
            }
        }
        launch {
            handle.getStateFlow<String?>(BookmarkListRequests.KEY_LAUNCH_ACTION, null).filterNotNull().collect { name ->
                handle[BookmarkListRequests.KEY_LAUNCH_ACTION] = null
                ListLaunchAction.entries.firstOrNull { it.name == name }?.let { currentOnLaunchAction(it) }
            }
        }
    }
}

/**
 * 一覧の画面。エントリに届いた依頼を ViewModel に渡してから、[BookmarkListScreen] を表示する。
 * [selectedBookmarkId]・[onBookmarkRemoved] は 2 画面表示用([BookmarkListDetailDestination])。
 */
@Composable
internal fun BookmarkListDestination(
    entry: NavBackStackEntry,
    onOpenBookmark: (Long) -> Unit,
    onOpenTagManagement: () -> Unit,
    selectedBookmarkId: Long? = null,
    onBookmarkRemoved: (Long) -> Unit = {},
    viewModel: BookmarkViewModel = hiltViewModel()
) {
    BookmarkListRequestsEffect(
        handle = entry.savedStateHandle,
        onSelectTag = viewModel::showTag,
        onLaunchAction = viewModel::handleLaunchAction
    )
    BookmarkListScreen(
        onOpenBookmark = onOpenBookmark,
        onOpenTagManagement = onOpenTagManagement,
        selectedBookmarkId = selectedBookmarkId,
        onBookmarkRemoved = onBookmarkRemoved,
        viewModel = viewModel
    )
}
