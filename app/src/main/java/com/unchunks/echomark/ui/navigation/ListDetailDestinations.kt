package com.unchunks.echomark.ui.navigation

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.unchunks.echomark.ui.chat.ChatScreen
import com.unchunks.echomark.ui.chat.ChatViewModel
import com.unchunks.echomark.ui.chat.ConversationListScreen
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.detail.BookmarkDetailScreen
import com.unchunks.echomark.ui.detail.BookmarkDetailViewModel

/**
 * 2 画面表示(一覧と詳細を左右に並べる)で、右側に開いているものの保存先のキー。
 *
 * 一覧のエントリ([NavBackStackEntry.savedStateHandle])に入れるので、回転・折りたたみの開閉・プロセスの終了後も残る。
 * 1 画面のときは使わず、詳細・会話はこれまでどおりルート("bookmark/{id}"・"chat/{id}")で開く。
 * 画面の広さが変わったときは、ルートとペインの間で開いているものを移し替える
 * ([moveDetailRouteIntoPane] と、各 Destination の「1 画面になったらルートで開き直す」処理)。
 */
internal object PaneSelection {
    /** ブックマークタブ: 右側に出しているブックマークの ID */
    const val KEY_BOOKMARK_ID = "paneBookmarkId"

    /** チャットタブ: 右側の会話の ViewModel を分けるキー([ChatPaneSession]) */
    const val KEY_CHAT_SESSION = "paneChatSession"

    /** チャットタブ: 右側の会話の ID(新しいチャットで、まだ送信していなければ null)。一覧の選択表示に使う */
    const val KEY_CHAT_CONVERSATION_ID = "paneChatConversationId"
}

/** チャットタブの右側の会話を表すキー。会話ごと・新しいチャットを始めるごとに別の ViewModel にする */
internal object ChatPaneSession {
    private const val CONVERSATION_PREFIX = "conversation:"
    private const val NEW_PREFIX = "new:"

    fun conversation(id: Long) = "$CONVERSATION_PREFIX$id"

    fun new(nonce: Long = System.currentTimeMillis()) = "$NEW_PREFIX$nonce"

    /** ViewModel に渡す引数(既存の会話なら conversationId) */
    fun args(session: String): Bundle =
        session.removePrefix(CONVERSATION_PREFIX).takeIf { session.startsWith(CONVERSATION_PREFIX) }?.toLongOrNull()
            ?.let { bundleOf(ChatViewModel.ARG_CONVERSATION_ID to it) }
            ?: Bundle()
}

/**
 * 2 画面表示になったとき、一覧の上にルートで開いていた詳細・会話を右側のペインへ移す
 * (1 画面で詳細を開いたまま折りたたみを開いた、通知から詳細を開いた、など)。移したら true。
 *
 * 一覧から開いたものだけを移す(すぐ下が一覧のとき)。チャットの引用など、別の画面から開いた詳細は
 * 戻る先を変えないよう、そのまま 1 つの画面として表示する。
 */
internal fun NavController.moveDetailRouteIntoPane(): Boolean {
    val current = currentBackStackEntry ?: return false
    val previous = previousBackStackEntry ?: return false
    val arguments = current.arguments ?: return false
    val previousRoute = previous.destination.route
    when (current.destination.route) {
        Routes.BOOKMARK_DETAIL -> {
            if (previousRoute != BOOKMARKS_ROUTE) return false
            val id = arguments.getLong(BookmarkDetailViewModel.ARG_BOOKMARK_ID, -1L).takeIf { it >= 0 } ?: return false
            popBackStack()
            previous.savedStateHandle[PaneSelection.KEY_BOOKMARK_ID] = id
        }
        CHAT_CONVERSATION_ROUTE -> {
            if (previousRoute != CHAT_ROUTE) return false
            val id = arguments.getLong(ChatViewModel.ARG_CONVERSATION_ID, -1L).takeIf { it >= 0 } ?: return false
            popBackStack()
            previous.savedStateHandle.selectChat(ChatPaneSession.conversation(id), id)
        }
        else -> return false
    }
    return true
}

private fun SavedStateHandle.selectChat(session: String?, conversationId: Long?) {
    this[PaneSelection.KEY_CHAT_SESSION] = session
    this[PaneSelection.KEY_CHAT_CONVERSATION_ID] = conversationId
}

/**
 * ブックマークタブ。1 画面なら一覧だけ(タップで詳細のルートを開く)、2 画面なら左に一覧・右に選んだブックマークの詳細。
 *
 * 2 画面のとき: カードのタップ・関連するブックマーク・保存後の「開く」で右側を切り替え、選んだカードを強調する。
 * 詳細の戻るボタンは出さない(右側は常にある領域なので)。一覧で選んでいるものを削除・アーカイブしたら右側を空にする。
 *
 * @param isCurrentEntry このタブ(一覧のエントリ)が今表示中か。1 画面になったときに詳細のルートを開き直してよいかの判定に使う
 * @param onOpenBookmarkRoute 詳細をルートで開く(1 画面のとき)
 */
@Composable
internal fun BookmarkListDetailDestination(
    entry: NavBackStackEntry,
    layout: AdaptiveLayout,
    isCurrentEntry: () -> Boolean,
    onOpenBookmarkRoute: (Long) -> Unit,
    onOpenTagManagement: () -> Unit,
    onAskAi: (Long) -> Unit,
    onOpenAiSettings: () -> Unit
) {
    val handle = entry.savedStateHandle
    val selectedId by handle.getStateFlow<Long?>(PaneSelection.KEY_BOOKMARK_ID, null).collectAsState()
    val twoPane = layout.isTwoPane
    val select: (Long?) -> Unit = { handle[PaneSelection.KEY_BOOKMARK_ID] = it }
    val currentOnOpenBookmarkRoute by rememberUpdatedState(onOpenBookmarkRoute)

    // 2 画面から 1 画面になったら(折りたたんだ・ウィンドウを狭めた)、選んでいたものを詳細の画面で開き直す。戻ると一覧
    LaunchedEffect(twoPane, selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        if (!twoPane && isCurrentEntry()) {
            select(null)
            currentOnOpenBookmarkRoute(id)
        }
    }

    ListDetailPanes(
        directive = layout.paneDirective,
        list = {
            BookmarkListDestination(
                entry = entry,
                onOpenBookmark = { id -> if (twoPane) select(id) else onOpenBookmarkRoute(id) },
                onOpenTagManagement = onOpenTagManagement,
                selectedBookmarkId = if (twoPane) selectedId else null,
                onBookmarkRemoved = { id -> if (id == selectedId) select(null) }
            )
        },
        detail = {
            val id = selectedId
            if (id == null) {
                BookmarkDetailPlaceholder()
            } else {
                // 選び直したら別の ViewModel・別の画面の状態(スクロール位置・入力中のタグなど)にする
                key(id) {
                    BookmarkDetailScreen(
                        onBack = { select(null) },
                        onOpenBookmark = { select(it) },
                        onAskAi = onAskAi,
                        onOpenAiSettings = onOpenAiSettings,
                        showBackButton = false,
                        viewModel = paneViewModel(
                            key = "bookmark_detail_pane:$id",
                            args = bundleOf(BookmarkDetailViewModel.ARG_BOOKMARK_ID to id)
                        )
                    )
                }
            }
        }
    )
}

/** 2 画面表示で、まだ何も選んでいないときの右側 */
@Composable
internal fun BookmarkDetailPlaceholder(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.Bookmarks,
        title = "ブックマークを選んでください",
        description = "一覧から選ぶと、ここに AI の要約やタグ、本文が表示されます。",
        modifier = modifier
    )
}

/**
 * チャットタブ。1 画面なら会話の一覧だけ(タップで会話のルートを開く)、2 画面なら左に会話の一覧・右に選んだ会話。
 *
 * 2 画面で「新しいチャット」を始めた場合、最初の送信で会話が作られたら、その会話を一覧で選択中として表示する
 * (ViewModel は作り直さないので、生成中の回答もそのまま続く)。
 *
 * @param onOpenConversationRoute 会話をルートで開く(1 画面のとき)
 * @param onNewConversationRoute 新しいチャットをルートで開く(1 画面のとき)
 */
@Composable
internal fun ChatListDetailDestination(
    entry: NavBackStackEntry,
    layout: AdaptiveLayout,
    isCurrentEntry: () -> Boolean,
    onOpenConversationRoute: (Long) -> Unit,
    onNewConversationRoute: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onOpenAiSettings: () -> Unit
) {
    val handle = entry.savedStateHandle
    val session by handle.getStateFlow<String?>(PaneSelection.KEY_CHAT_SESSION, null).collectAsState()
    val conversationId by handle.getStateFlow<Long?>(PaneSelection.KEY_CHAT_CONVERSATION_ID, null).collectAsState()
    val twoPane = layout.isTwoPane
    val currentOnOpenConversationRoute by rememberUpdatedState(onOpenConversationRoute)
    val currentOnNewConversationRoute by rememberUpdatedState(onNewConversationRoute)

    // 2 画面から 1 画面になったら、右側の会話をルートで開き直す(まだ送信していない新しいチャットは新しいチャットとして)
    LaunchedEffect(twoPane, session) {
        if (session == null || twoPane || !isCurrentEntry()) return@LaunchedEffect
        val id = conversationId
        handle.selectChat(null, null)
        if (id != null) currentOnOpenConversationRoute(id) else currentOnNewConversationRoute()
    }

    ListDetailPanes(
        directive = layout.paneDirective,
        list = {
            ConversationListScreen(
                onOpenConversation = { id ->
                    when {
                        !twoPane -> onOpenConversationRoute(id)
                        // 開いている会話をもう一度選んだときは、そのまま(作り直さない)
                        id != conversationId -> handle.selectChat(ChatPaneSession.conversation(id), id)
                    }
                },
                onNewConversation = {
                    when {
                        !twoPane -> onNewConversationRoute()
                        // まだ何も送っていない新しいチャットを開いているなら、それを使う
                        session != null && conversationId == null -> Unit
                        else -> handle.selectChat(ChatPaneSession.new(), null)
                    }
                },
                onOpenAiSettings = onOpenAiSettings,
                selectedConversationId = if (twoPane) conversationId else null,
                onConversationDeleted = { id -> if (id == conversationId) handle.selectChat(null, null) }
            )
        },
        detail = {
            val current = session
            if (current == null) {
                EmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "会話を選んでください",
                    description = "一覧から会話を選ぶか、新しいチャットを始めると、ここに表示されます。",
                    actionLabel = "新しいチャット",
                    actionIcon = Icons.Outlined.Add,
                    onAction = { handle.selectChat(ChatPaneSession.new(), null) }
                )
            } else {
                key(current) {
                    val viewModel = paneViewModel<ChatViewModel>(
                        key = "chat_pane:$current",
                        args = ChatPaneSession.args(current)
                    )
                    // 新しいチャットで最初の送信をして会話ができたら、一覧でその会話を選択中にする
                    LaunchedEffect(viewModel) {
                        viewModel.conversationId.collect { id ->
                            if (id != null) handle[PaneSelection.KEY_CHAT_CONVERSATION_ID] = id
                        }
                    }
                    ChatScreen(
                        onBack = { handle.selectChat(null, null) },
                        onOpenBookmark = onOpenBookmark,
                        onOpenAiSettings = onOpenAiSettings,
                        showBackButton = false,
                        viewModel = viewModel
                    )
                }
            }
        }
    )
}
