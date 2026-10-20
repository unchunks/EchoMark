package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.core.layout.WindowSizeClass
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.bookmark.BookmarkListCallbacks
import com.unchunks.echomark.ui.bookmark.BookmarkListContent
import com.unchunks.echomark.ui.bookmark.BookmarkListUiState
import com.unchunks.echomark.ui.chat.ChatContent
import com.unchunks.echomark.ui.chat.ChatUiState
import com.unchunks.echomark.ui.chat.ConversationListContent
import com.unchunks.echomark.ui.chat.ConversationListUiState
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import com.unchunks.echomark.ui.navigation.AdaptiveLayout
import com.unchunks.echomark.ui.navigation.BookmarkDetailPlaceholder
import com.unchunks.echomark.ui.navigation.EchoMarkAppScaffold
import com.unchunks.echomark.ui.navigation.ListDetailPanes
import com.unchunks.echomark.ui.navigation.adaptiveLayoutOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 大きな画面(タブレット・折りたたみを開いたとき)の配置。左のナビゲーションレール、一覧と詳細の 2 画面、
 * 1 画面で幅が広いときの複数列の一覧を、画面全体で撮る。
 * 配置は本番と同じ [adaptiveLayoutOf] で、ウィンドウの大きさから求める。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 撮る画面(最大 1000dp × 800dp)が収まるウィンドウにする
@Config(qualifiers = "w1000dp-h800dp-xhdpi")
class LargeScreenLayoutScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW

    /** 横向きのタブレット・折りたたみを開いたとき(Expanded): レール + 2 画面 */
    private val expanded = layoutOf(EXPANDED_WIDTH_DP, HEIGHT_DP)

    /** 縦向きの小さめのタブレット(Medium): レール + 1 画面 */
    private val medium = layoutOf(MEDIUM_WIDTH_DP, HEIGHT_DP)

    private fun layoutOf(widthDp: Int, heightDp: Int): AdaptiveLayout =
        adaptiveLayoutOf(WindowAdaptiveInfo(WindowSizeClass(widthDp.toFloat(), heightDp.toFloat()), Posture()))

    private val listState = BookmarkListUiState(
        bookmarks = PreviewSamples.all,
        allTags = listOf(Tag(1, "Android"), Tag(2, "Compose"), Tag(3, "読書")),
        totalCount = PreviewSamples.all.size
    )

    private val selected = PreviewSamples.urlBookmark.copy(
        category = "技術記事",
        content = "Jetpack Compose は宣言的に UI を組み立てるツールキットです。状態が変わると関係する部分だけが" +
            "再コンポーズされますが、不安定な型や毎回生成されるラムダがあると、不要な再コンポーズが起きます。"
    )

    @Composable
    private fun Frame(layout: AdaptiveLayout, selectedRoute: String, content: @Composable (androidx.compose.ui.Modifier) -> Unit) {
        EchoMarkAppScaffold(
            showNavigation = true,
            selectedRoute = selectedRoute,
            onNavigate = {},
            navigation = layout.navigation,
            content = content
        )
    }

    @Composable
    private fun BookmarkList(selectedId: Long?) {
        BookmarkListContent(
            uiState = listState,
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkListCallbacks(),
            nowMillis = now,
            selectedBookmarkId = selectedId
        )
    }

    @Test
    fun bookmarksTwoPane() = screenshot.captureScreenLightDark(
        "large_bookmarks_two_pane",
        widthDp = EXPANDED_WIDTH_DP,
        heightDp = HEIGHT_DP
    ) {
        Frame(expanded, "bookmarks") { modifier ->
            ListDetailPanes(
                directive = expanded.paneDirective,
                modifier = modifier,
                list = { BookmarkList(selectedId = selected.id) },
                detail = {
                    BookmarkDetailContent(
                        uiState = BookmarkDetailUiState(
                            isLoading = false,
                            bookmark = selected,
                            related = listOf(PreviewSamples.textBookmark)
                        ),
                        snackbarHostState = SnackbarHostState(),
                        callbacks = BookmarkDetailCallbacks(),
                        nowMillis = now,
                        showBackButton = false
                    )
                }
            )
        }
    }

    @Test
    fun bookmarksTwoPaneNothingSelected() = screenshot.captureScreenLightDark(
        "large_bookmarks_two_pane_empty",
        widthDp = EXPANDED_WIDTH_DP,
        heightDp = HEIGHT_DP
    ) {
        Frame(expanded, "bookmarks") { modifier ->
            ListDetailPanes(
                directive = expanded.paneDirective,
                modifier = modifier,
                list = { BookmarkList(selectedId = null) },
                detail = { BookmarkDetailPlaceholder() }
            )
        }
    }

    /** 2 画面にしない幅では、レールの右に一覧だけを出し、カードを 2 列に並べる */
    @Test
    fun bookmarksMediumGrid() = screenshot.captureScreenLightDark(
        "large_bookmarks_medium_grid",
        widthDp = MEDIUM_WIDTH_DP,
        heightDp = HEIGHT_DP
    ) {
        Frame(medium, "bookmarks") { modifier ->
            ListDetailPanes(
                directive = medium.paneDirective,
                modifier = modifier,
                list = { BookmarkList(selectedId = null) },
                detail = {}
            )
        }
    }

    @Test
    fun chatTwoPane() = screenshot.captureScreenLightDark(
        "large_chat_two_pane",
        widthDp = EXPANDED_WIDTH_DP,
        heightDp = HEIGHT_DP
    ) {
        val minute = 60_000L
        val previews = listOf(
            ConversationPreview(
                conversation = Conversation(
                    id = 1, title = "Compose のパフォーマンスについて",
                    createdAt = now - 120 * minute, updatedAt = now - 9 * minute
                ),
                lastMessage = "再コンポーズを減らすには、状態を安定させることが大切です。",
                lastMessageRole = ChatRole.ASSISTANT
            ),
            ConversationPreview(
                conversation = Conversation(
                    id = 2, title = "読書メモ: 『知の編集術』について", isTitleManuallySet = true,
                    createdAt = now - 3 * 24 * 60 * minute, updatedAt = now - 2 * 24 * 60 * minute
                ),
                lastMessage = "情報を編集するとはどういうこと？",
                lastMessageRole = ChatRole.USER
            )
        )
        val messages = listOf(
            ChatMessage(
                id = 1, conversationId = 1, role = ChatRole.USER,
                content = "Compose のパフォーマンスについて保存したものの要点をまとめて", createdAt = now - 10 * minute
            ),
            ChatMessage(
                id = 2, conversationId = 1, role = ChatRole.ASSISTANT,
                content = "保存した記事からは、**再コンポーズを減らす**ことと、Lazy リストに `key` を指定することが大事だと分かります [1]。",
                referencedBookmarkIds = listOf(PreviewSamples.urlBookmark.id),
                createdAt = now - 9 * minute
            )
        )
        Frame(expanded, "chat") { modifier ->
            ListDetailPanes(
                directive = expanded.paneDirective,
                modifier = modifier,
                list = {
                    ConversationListContent(
                        uiState = ConversationListUiState(isLoading = false, conversations = previews),
                        nowMillis = now,
                        onOpenConversation = {},
                        onNewConversation = {},
                        onOpenAiSettings = {},
                        onRename = { _, _ -> },
                        onDelete = {},
                        selectedConversationId = 1
                    )
                },
                detail = {
                    ChatContent(
                        uiState = ChatUiState(
                            title = "Compose のパフォーマンスについて",
                            hasConversation = true,
                            messages = messages,
                            referencedBookmarks = mapOf(PreviewSamples.urlBookmark.id to PreviewSamples.urlBookmark)
                        ),
                        input = "",
                        onInputChange = {},
                        onSend = {},
                        onStop = {},
                        onRetry = {},
                        onBack = {},
                        onOpenBookmark = {},
                        onOpenAiSettings = {},
                        onRename = {},
                        onDelete = {},
                        onCopy = {},
                        showBackButton = false
                    )
                }
            )
        }
    }

    private companion object {
        const val EXPANDED_WIDTH_DP = 1000
        const val MEDIUM_WIDTH_DP = 760
        const val HEIGHT_DP = 800
    }
}
