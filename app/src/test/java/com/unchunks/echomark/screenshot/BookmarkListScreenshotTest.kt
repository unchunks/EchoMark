package com.unchunks.echomark.screenshot

import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.ui.common.SelectedFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.Recommendation
import com.unchunks.echomark.domain.bookmark.model.RecommendationReason
import com.unchunks.echomark.domain.bookmark.model.SearchSuggestion
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

    /** タグで絞り込んでいる間は、右端に「すべて解除」を出す */
    @Test
    fun tagFiltered() = screenshot.captureScreenLightDark(
        "library_list_tag_filtered",
        content = content(
            BookmarkListUiState(
                bookmarks = PreviewSamples.all.take(2),
                allTags = listOf(tags[1]) + tags.filterNot { it.id == 2L },
                selectedTagIds = setOf(2L),
                totalCount = PreviewSamples.all.size
            )
        )
    )

    /** タグを2つ・カテゴリを1つ選んでいる間は、「すべて解除」と、タグの組み合わせ方(すべて含む / どれかを含む)を出す */
    @Test
    fun multiFiltered() = screenshot.captureScreenLightDark(
        "library_list_multi_filtered",
        content = content(
            BookmarkListUiState(
                bookmarks = PreviewSamples.all.take(1),
                allTags = listOf(tags[1], tags[0]) + tags.filterNot { it.id == 1L || it.id == 2L },
                selectedTagIds = setOf(1L, 2L),
                tagMatch = TagMatch.ANY,
                allCategories = listOf("技術", "ニュース", "レシピ"),
                selectedCategories = setOf("技術"),
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
    fun compactDisplay() = screenshot.captureScreenLightDark(
        "library_list_compact",
        content = content(
            BookmarkListUiState(
                bookmarks = PreviewSamples.all.mapIndexed { index, b -> if (index == 2) b.copy(readAt = now - day) else b },
                allTags = tags,
                display = BookmarkListDisplay.COMPACT,
                totalCount = 4
            )
        )
    )

    @Test
    fun gridDisplay() = screenshot.captureScreenLightDark(
        "library_list_grid",
        content = content(
            BookmarkListUiState(
                bookmarks = PreviewSamples.all,
                allTags = tags,
                display = BookmarkListDisplay.GRID,
                totalCount = 4
            )
        )
    )

    @Test
    fun readNextRecommendations() = screenshot.captureScreenLightDark(
        "library_list_read_next",
        content = content(
            BookmarkListUiState(
                // 読んだものはドットなし、本文があれば「約N分」を出す
                bookmarks = listOf(
                    PreviewSamples.urlBookmark.copy(content = "あ".repeat(1_800)),
                    PreviewSamples.textBookmark.copy(readAt = now - day)
                ),
                recommendations = listOf(
                    Recommendation(PreviewSamples.urlBookmark, RecommendationReason.SharedTag("Compose")),
                    Recommendation(PreviewSamples.processingBookmark, RecommendationReason.Short(3)),
                    Recommendation(PreviewSamples.textBookmark, RecommendationReason.Favorite)
                ),
                allTags = tags,
                totalCount = 4
            )
        )
    )

    @Test
    fun unreadFilter() = screenshot.captureScreenLightDark(
        "library_list_unread",
        content = content(
            BookmarkListUiState(
                bookmarks = listOf(PreviewSamples.urlBookmark),
                allTags = tags,
                readState = ReadState.UNREAD,
                totalCount = 4
            )
        )
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
    fun searchSuggestionsWithConditions() = screenshot.captureScreenLightDark(
        "library_list_search_suggestions",
        content = content(
            BookmarkListUiState(
                bookmarks = listOf(PreviewSamples.urlBookmark),
                allTags = tags,
                isSearchActive = true,
                searchQuery = "compo",
                searchTerms = listOf("compo"),
                suggestions = listOf(
                    SearchSuggestion.History("compose 入門"),
                    SearchSuggestion.Tag(2, "Compose"),
                    SearchSuggestion.Category("技術記事")
                ),
                selectedTypes = setOf(BookmarkType.URL),
                period = CreatedPeriod.PAST_MONTH,
                totalCount = 4
            )
        )
    )

    @Test
    fun searchRecentHistory() = screenshot.captureScreenLightDark(
        "library_list_search_history",
        content = content(
            BookmarkListUiState(
                bookmarks = listOf(PreviewSamples.urlBookmark, PreviewSamples.textBookmark),
                allTags = tags,
                isSearchActive = true,
                suggestions = listOf(
                    SearchSuggestion.History("compose パフォーマンス"),
                    SearchSuggestion.History("読書メモ"),
                    SearchSuggestion.History("kotlin flow")
                ),
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

    @Test
    fun addSheetFiles() = screenshot.captureLightDark("library_add_sheet_files") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AddBookmarkSheetContent(
                form = AddBookmarkForm(
                    mode = AddMode.FILE,
                    files = listOf(
                        SelectedFile("content://docs/1", "application/pdf", "論文_Attention Is All You Need.pdf", 2_215_000L),
                        SelectedFile("content://media/2", "image/png", "ホワイトボード.png", 640_000L),
                        SelectedFile("content://rec/3", "audio/mp4", "講義の録音.m4a", 48_000_000L)
                    )
                ),
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

    @Test
    fun addSheetFilesEmpty() = screenshot.captureLightDark("library_add_sheet_files_empty") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AddBookmarkSheetContent(
                form = AddBookmarkForm(mode = AddMode.FILE),
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
