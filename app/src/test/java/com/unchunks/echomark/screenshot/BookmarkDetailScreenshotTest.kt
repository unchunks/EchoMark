package com.unchunks.echomark.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import com.unchunks.echomark.ui.detail.EditBookmarkSheetContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 詳細画面(URL・メモ・AI 処理中・失敗・準備待ち)と編集シートの見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookmarkDetailScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private fun content(bookmark: Bookmark?, related: List<Bookmark> = emptyList()): @Composable () -> Unit = {
        BookmarkDetailContent(
            uiState = BookmarkDetailUiState(isLoading = false, bookmark = bookmark, related = related),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkDetailCallbacks(),
            nowMillis = PreviewSamples.NOW
        )
    }

    private val urlBookmark = PreviewSamples.urlBookmark.copy(
        category = "技術記事",
        content = "Jetpack Compose は宣言的に UI を組み立てるツールキットです。状態が変わると関係する部分だけが" +
            "再コンポーズされますが、不安定な型や毎回生成されるラムダがあると、不要な再コンポーズが起きます。" +
            "この記事では、安定性の考え方と、remember・derivedStateOf の使い分け、Lazy リストの key 指定など、" +
            "計測しながら改善する手順を紹介します。まずは Layout Inspector で再コンポーズ回数を確認しましょう。"
    )

    @Test
    fun url() = screenshot.captureScreenLightDark(
        "library_detail_url",
        heightDp = 1400,
        content = content(urlBookmark, related = listOf(PreviewSamples.textBookmark, PreviewSamples.processingBookmark))
    )

    @Test
    fun memo() = screenshot.captureScreenLightDark(
        "library_detail_memo",
        content = content(
            PreviewSamples.textBookmark.copy(
                aiStatus = AiStatus.DONE,
                category = "読書",
                content = "情報は編集することで知識になる。\n関係を見つけて、つなぎ直すのが編集。"
            )
        )
    )

    @Test
    fun processing() = screenshot.captureScreenLightDark(
        "library_detail_processing",
        content = content(PreviewSamples.processingBookmark)
    )

    @Test
    fun failed() = screenshot.captureScreenLightDark(
        "library_detail_failed",
        content = content(PreviewSamples.textBookmark.copy(summary = null))
    )

    @Test
    fun waitingModel() = screenshot.captureScreenLightDark(
        "library_detail_waiting",
        content = content(
            PreviewSamples.processingBookmark.copy(
                aiStatus = AiStatus.WAITING_MODEL,
                title = "アーキテクチャガイド | Android Developers",
                siteName = "Android Developers",
                isArchived = true
            )
        )
    )

    @Test
    fun notFound() = screenshot.captureScreenLightDark("library_detail_not_found", content = content(null))

    @Test
    fun editSheet() = screenshot.captureLightDark("library_detail_edit_sheet") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            EditBookmarkSheetContent(
                isLink = true,
                title = urlBookmark.title,
                content = "あとで読む。第3章が特に参考になる。",
                onTitleChange = {},
                onContentChange = {},
                onCancel = {},
                onSave = {}
            )
        }
    }
}
