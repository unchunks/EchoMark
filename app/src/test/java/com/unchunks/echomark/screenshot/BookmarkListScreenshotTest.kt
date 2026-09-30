package com.unchunks.echomark.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.bookmark.AddBookmarkForm
import com.unchunks.echomark.ui.bookmark.AddBookmarkSheetContent
import com.unchunks.echomark.ui.bookmark.AddMode
import com.unchunks.echomark.ui.bookmark.BookmarkListCallbacks
import com.unchunks.echomark.ui.bookmark.BookmarkListContent
import com.unchunks.echomark.ui.bookmark.BookmarkListUiState
import com.unchunks.echomark.ui.components.PreviewSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 一覧(ホーム)画面の主要な状態と、追加シートの見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookmarkListScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW
    private val day = 24 * 60 * 60 * 1000L

    private val rediscover = listOf(
        Bookmark(
            id = 20, type = BookmarkType.URL, contentUri = "https://zenn.dev/articles/kotlin-flow",
            title = "Kotlin Flow の cold / hot を図で理解する", siteName = "Zenn",
            createdAt = now - 90 * day, lastAccessedAt = now - 60 * day
        ),
        Bookmark(
            id = 21, type = BookmarkType.TEXT, title = "来年やりたいことリスト",
            createdAt = now - 45 * day, lastAccessedAt = now - 45 * day
        )
    )

    private val tags = listOf(Tag(1, "Android"), Tag(2, "Compose"), Tag(3, "読書"), Tag(4, "パフォーマンス"))

    private fun content(state: BookmarkListUiState) = @androidx.compose.runtime.Composable {
        BookmarkListContent(
            uiState = state,
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkListCallbacks(),
            nowMillis = now
        )
    }

    @Test
    fun normal() = screenshot.captureScreenLightDark(
        "library_list_normal",
        content = content(
            BookmarkListUiState(
                bookmarks = PreviewSamples.all,
                allTags = tags,
                rediscover = rediscover,
                totalCount = PreviewSamples.all.size
            )
        )
    )

    @Test
    fun firstRunEmpty() = screenshot.captureScreenLightDark(
        "library_list_empty",
        content = content(BookmarkListUiState(totalCount = 0))
    )

    /** 幅の狭い端末(360dp)でも、見出しが単語の途中で改行されないこと */
    @Test
    fun firstRunEmptyNarrow() = screenshot.captureScreenLightDark(
        "library_list_empty_narrow",
        widthDp = 320,
        content = content(BookmarkListUiState(totalCount = 0))
    )

    @Test
    fun favoritesEmpty() = screenshot.captureScreenLightDark(
        "library_list_favorites_empty",
        content = content(BookmarkListUiState(filter = BookmarkFilter.FAVORITES, allTags = tags, totalCount = 4))
    )

    @Test
    fun searchResults() = screenshot.captureScreenLightDark(
        "library_list_search",
        content = content(
            BookmarkListUiState(
                bookmarks = listOf(PreviewSamples.urlBookmark, PreviewSamples.textBookmark),
                allTags = tags,
                isSearchActive = true,
                searchQuery = "動作を軽くする方法",
                semanticSearchAvailable = true,
                semanticMatchIds = setOf(PreviewSamples.textBookmark.id),
                totalCount = 4
            )
        )
    )

    @Test
    fun searchNoResult() = screenshot.captureScreenLightDark(
        "library_list_search_empty",
        content = content(
            BookmarkListUiState(
                isSearchActive = true,
                searchQuery = "量子コンピュータ",
                semanticSearchAvailable = false,
                totalCount = 4
            )
        )
    )

    @Test
    fun addSheet() = screenshot.captureLightDark("library_add_sheet") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AddBookmarkSheetContent(
                form = AddBookmarkForm(mode = AddMode.LINK),
                showClipboardSuggestion = true,
                onModeChange = {},
                onUrlChange = {},
                onTitleChange = {},
                onMemoChange = {},
                onTextChange = {},
                onPasteFromClipboard = {},
                onCancel = {},
                onSave = {}
            )
        }
    }

    @Test
    fun addSheetNoteWithError() = screenshot.captureLightDark("library_add_sheet_note") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AddBookmarkSheetContent(
                form = AddBookmarkForm(mode = AddMode.NOTE, text = "情報は編集することで知識になる。\n次に読む本の候補"),
                showClipboardSuggestion = false,
                onModeChange = {},
                onUrlChange = {},
                onTitleChange = {},
                onMemoChange = {},
                onTextChange = {},
                onPasteFromClipboard = {},
                onCancel = {},
                onSave = {}
            )
        }
    }
}
