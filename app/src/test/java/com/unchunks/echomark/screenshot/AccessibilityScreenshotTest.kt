package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.ui.bookmark.BookmarkListCallbacks
import com.unchunks.echomark.ui.bookmark.BookmarkListContent
import com.unchunks.echomark.ui.bookmark.BookmarkListUiState
import com.unchunks.echomark.ui.chat.ConversationListContent
import com.unchunks.echomark.ui.chat.ConversationListUiState
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import com.unchunks.echomark.ui.tags.TagManagementContent
import com.unchunks.echomark.ui.tags.TagManagementUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * アクセシビリティ条件での主要画面の見た目。
 * - 文字サイズ最大級(fontScale 2.0)× 幅の狭い端末(360dp): 文字が切れたり、単語の途中で割れたりしないこと
 * - 横向き(高さ 360dp): 空状態のボタンまでスクロールで届くこと
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 縦長(360×1400)と横長(800×360)のどちらの枠も収まるウィンドウにする
@Config(qualifiers = "w900dp-h1600dp-xhdpi")
class AccessibilityScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW

    private val tags = listOf(Tag(1, "Android"), Tag(2, "Compose"), Tag(3, "パフォーマンス"))

    /** タグ名が長く、カードのタグ行に収まりきらないもの */
    private val longTagBookmark = PreviewSamples.urlBookmark.copy(
        id = 10,
        isFavorite = false,
        tags = listOf("ソフトウェアアーキテクチャ", "パフォーマンス改善", "Kotlin Coroutines", "読書")
    )

    private fun list(state: BookmarkListUiState): @Composable () -> Unit = {
        BookmarkListContent(
            uiState = state,
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkListCallbacks(),
            nowMillis = now
        )
    }

    private fun detail(bookmark: Bookmark): @Composable () -> Unit = {
        BookmarkDetailContent(
            uiState = BookmarkDetailUiState(isLoading = false, bookmark = bookmark),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkDetailCallbacks(),
            nowMillis = now
        )
    }

    // ---- 文字サイズ最大級 × 360dp ----

    @Test
    fun listLargeFont() = screenshot.captureScreenLightDark(
        "a11y_list_font2_w360",
        widthDp = NARROW_WIDTH_DP,
        heightDp = TALL_HEIGHT_DP,
        fontScale = LARGEST_FONT_SCALE,
        content = list(
            BookmarkListUiState(
                bookmarks = listOf(PreviewSamples.urlBookmark, longTagBookmark, PreviewSamples.textBookmark),
                allTags = tags,
                totalCount = 3
            )
        )
    )

    /** 標準の文字サイズ・360dp でも、カードのタグ行が詰まって「+N」が割れないこと */
    @Test
    fun listNarrow() = screenshot.captureScreenLightDark(
        "a11y_list_w360",
        widthDp = NARROW_WIDTH_DP,
        content = list(
            BookmarkListUiState(
                bookmarks = listOf(PreviewSamples.urlBookmark, longTagBookmark, PreviewSamples.processingBookmark),
                allTags = tags,
                totalCount = 3
            )
        )
    )

    @Test
    fun detailLargeFont() = screenshot.captureScreenLightDark(
        "a11y_detail_font2_w360",
        widthDp = NARROW_WIDTH_DP,
        heightDp = TALL_HEIGHT_DP,
        fontScale = LARGEST_FONT_SCALE,
        content = detail(PreviewSamples.urlBookmark)
    )

    /** AI 処理に失敗したとき(再試行と AI 設定のボタンが並ぶ) */
    @Test
    fun detailFailedLargeFont() = screenshot.captureScreenLightDark(
        "a11y_detail_failed_font2_w360",
        widthDp = NARROW_WIDTH_DP,
        heightDp = TALL_HEIGHT_DP,
        fontScale = LARGEST_FONT_SCALE,
        content = detail(PreviewSamples.textBookmark.copy(summary = null, aiStatus = AiStatus.FAILED))
    )

    // ---- 横向き ----

    @Test
    fun listEmptyLandscape() = screenshot.captureScreenLightDark(
        "a11y_list_empty_landscape",
        widthDp = LANDSCAPE_WIDTH_DP,
        heightDp = LANDSCAPE_HEIGHT_DP,
        content = list(BookmarkListUiState(totalCount = 0))
    )

    @Test
    fun conversationListEmptyLandscape() = screenshot.captureScreenLightDark(
        "a11y_conversation_list_empty_landscape",
        widthDp = LANDSCAPE_WIDTH_DP,
        heightDp = LANDSCAPE_HEIGHT_DP
    ) {
        ConversationListContent(
            uiState = ConversationListUiState(isLoading = false),
            nowMillis = now,
            onOpenConversation = {},
            onNewConversation = {},
            onOpenAiSettings = {},
            onRename = { _, _ -> },
            onDelete = {}
        )
    }

    @Test
    fun tagsLargeFont() = screenshot.captureScreenLightDark(
        "a11y_tags_font2_w360",
        widthDp = NARROW_WIDTH_DP,
        fontScale = LARGEST_FONT_SCALE
    ) {
        TagManagementContent(
            uiState = TagManagementUiState(
                isLoading = false,
                tags = listOf(TagWithCount(1, "Android", 12), TagWithCount(2, "ソフトウェアアーキテクチャ", 3))
            ),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenTag = {},
            onRename = { _, _ -> },
            onDelete = {}
        )
    }

    private companion object {
        const val LARGEST_FONT_SCALE = 2f
        const val NARROW_WIDTH_DP = 360
        const val TALL_HEIGHT_DP = 1400
        const val LANDSCAPE_WIDTH_DP = 800
        const val LANDSCAPE_HEIGHT_DP = 360
    }
}
