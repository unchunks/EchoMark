package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.ui.collections.CollectionDetailContent
import com.unchunks.echomark.ui.collections.CollectionDetailUiState
import com.unchunks.echomark.ui.collections.CollectionsContent
import com.unchunks.echomark.ui.collections.CollectionsUiState
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.AddToCollectionSheetContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** コレクションの一覧・中身・追加のシートの見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val collections = listOf(
        BookmarkCollection(1, "旅行の計画", "夏の北海道で使う情報", 5, 1),
        BookmarkCollection(2, "読書リスト", null, 12, 2),
        BookmarkCollection(3, "空のコレクション", null, 0, 3)
    )

    @Test
    fun list() = screenshot.captureScreenLightDark("collections_list") {
        CollectionsContent(
            uiState = CollectionsUiState(isLoading = false, collections = collections),
            snackbarHostState = SnackbarHostState(),
            onBack = {}, onOpenCollection = {}, onCreate = { _, _, _ -> }, onUpdate = { _, _, _, _ -> }, onDelete = {}
        )
    }

    @Test
    fun listEmpty() = screenshot.captureScreenLightDark("collections_list_empty") {
        CollectionsContent(
            uiState = CollectionsUiState(isLoading = false),
            snackbarHostState = SnackbarHostState(),
            onBack = {}, onOpenCollection = {}, onCreate = { _, _, _ -> }, onUpdate = { _, _, _, _ -> }, onDelete = {}
        )
    }

    @Test
    fun detail() = screenshot.captureScreenLightDark("collections_detail") {
        CollectionDetailContent(
            uiState = CollectionDetailUiState(
                isLoading = false, collection = collections[0], bookmarks = PreviewSamples.all
            ),
            snackbarHostState = SnackbarHostState(),
            onBack = {}, onOpenBookmark = {}, onRemove = {}, onExport = {},
            nowMillis = PreviewSamples.NOW
        )
    }

    @Test
    fun detailEmpty() = screenshot.captureScreenLightDark("collections_detail_empty") {
        CollectionDetailContent(
            uiState = CollectionDetailUiState(isLoading = false, collection = collections[2]),
            snackbarHostState = SnackbarHostState(),
            onBack = {}, onOpenBookmark = {}, onRemove = {}, onExport = {}
        )
    }

    @Test
    fun addSheet() = screenshot.captureScreenLightDark("collections_add_sheet", heightDp = 700) {
        AddToCollectionSheetContent(
            collections = collections,
            memberOf = setOf(2L),
            onSetInCollection = { _, _ -> },
            onCreate = { _, _ -> },
            onClose = {}
        )
    }

    @Test
    fun addSheetEmpty() = screenshot.captureScreenLightDark("collections_add_sheet_empty", heightDp = 500) {
        AddToCollectionSheetContent(
            collections = emptyList(), memberOf = emptySet(), onSetInCollection = { _, _ -> }, onCreate = { _, _ -> }, onClose = {}
        )
    }
}
