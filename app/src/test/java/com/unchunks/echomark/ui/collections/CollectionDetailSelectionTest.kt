package com.unchunks.echomark.ui.collections

import androidx.lifecycle.SavedStateHandle
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeCategoryRepository
import com.unchunks.echomark.testing.FakeCollectionRepository
import com.unchunks.echomark.testing.FakeExportRepository
import com.unchunks.echomark.ui.selection.BulkBookmarkActions
import com.unchunks.echomark.ui.selection.BulkMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** コレクションの中での複数選択と、まとめての操作(「コレクションから外す」を含む)。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailSelectionTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val collections = FakeCollectionRepository()
    private val bookmarks = FakeBookmarkRepository()
    private val exports = FakeExportRepository()

    private fun bookmark(id: Long) = Bookmark(id = id, type = BookmarkType.TEXT, title = "題$id", createdAt = id, lastAccessedAt = id)

    private fun viewModel(collectionId: Long, handle: SavedStateHandle = SavedStateHandle(mapOf(CollectionDetailViewModel.ARG_COLLECTION_ID to collectionId))) =
        CollectionDetailViewModel(handle, collections, exports, FakeCategoryRepository(), BulkBookmarkActions(bookmarks, collections, exports))

    private fun TestScope.start(viewModel: CollectionDetailViewModel): MutableList<CollectionDetailMessage> {
        val messages = mutableListOf<CollectionDetailMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    /** 3件を入れたコレクションの画面 */
    private suspend fun setUp(): Pair<Long, CollectionDetailViewModel> {
        val list = listOf(bookmark(1), bookmark(2), bookmark(3))
        collections.bookmarks.value = list
        bookmarks.bookmarks.value = list
        val id = (collections.create("集め物") as CollectionResult.Saved).id
        list.forEach { collections.add(id, it.id) }
        return id to viewModel(id)
    }

    private fun List<CollectionDetailMessage>.bulk(): List<BulkMessage> = map { (it as CollectionDetailMessage.Bulk).message }

    @Test
    fun 長押しで選択モードに入り_すべて選択はコレクションの中身を選ぶ() = runTest {
        val (_, viewModel) = setUp()
        start(viewModel)
        advanceUntilIdle()

        viewModel.onEnterSelection(2)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSelecting)
        assertEquals(setOf(2L), viewModel.uiState.value.selectedIds)

        viewModel.onSelectAll()
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L, 3L), viewModel.uiState.value.selectedIds)

        viewModel.onExitSelection()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSelecting)
    }

    @Test
    fun 選択は回転のあとの新しいViewModelにも戻る() = runTest {
        val (id, first) = setUp()
        start(first)
        advanceUntilIdle()
        first.onEnterSelection(1)
        first.onToggleSelection(3)
        advanceUntilIdle()

        val handle = SavedStateHandle(mapOf(CollectionDetailViewModel.ARG_COLLECTION_ID to id, "selection_active" to true, "selection_ids" to longArrayOf(1, 3)))
        val second = viewModel(id, handle)
        start(second)
        advanceUntilIdle()

        assertTrue(second.uiState.value.isSelecting)
        assertEquals(setOf(1L, 3L), second.uiState.value.selectedIds)
    }

    @Test
    fun 選んだものをコレクションから外して_取り消すと戻る() = runTest {
        val (id, viewModel) = setUp()
        val messages = start(viewModel)
        advanceUntilIdle()
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(3)
        advanceUntilIdle()

        viewModel.bulkRemoveFromCollection()
        advanceUntilIdle()

        assertEquals(listOf(2L), collections.observeBookmarks(id).first().map { it.id })
        assertFalse(viewModel.uiState.value.isSelecting)
        // ブックマーク自体は消えない
        assertEquals(3, bookmarks.bookmarks.value.size)
        val message = messages.bulk().single() as BulkMessage.RemovedFromCollection
        assertEquals(listOf(1L, 3L), message.bookmarks.map { it.id })

        viewModel.undoBulkRemoveFromCollection(message.collectionId, message.bookmarks)
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L, 3L), collections.observeBookmarks(id).first().map { it.id }.toSet())
    }

    @Test
    fun コレクションの中からまとめてアーカイブ_お気に入り_削除ができる() = runTest {
        val (_, viewModel) = setUp()
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()
        viewModel.bulkSetFavorite(true)
        advanceUntilIdle()
        assertEquals(listOf(true, true, false), bookmarks.bookmarks.value.sortedBy { it.id }.map { it.isFavorite })

        viewModel.onEnterSelection(3)
        advanceUntilIdle()
        viewModel.bulkSetArchived(true)
        advanceUntilIdle()
        assertEquals(listOf(false, false, true), bookmarks.bookmarks.value.sortedBy { it.id }.map { it.isArchived })

        viewModel.onEnterSelection(2)
        advanceUntilIdle()
        viewModel.bulkDelete()
        advanceUntilIdle()
        assertEquals(setOf(1L, 3L), bookmarks.bookmarks.value.map { it.id }.toSet())

        assertEquals(3, messages.size)
        assertTrue(messages.bulk()[2] is BulkMessage.Deleted)
    }

    @Test
    fun 再処理は順番の列にまとめて積み_書き出しは選んだものだけを書き出す() = runTest {
        val (_, viewModel) = setUp()
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()
        viewModel.bulkReprocess()
        advanceUntilIdle()
        assertEquals(listOf(listOf(1L, 2L)), bookmarks.reprocessAllCalls)
        assertTrue(bookmarks.reprocessCalls.isEmpty())

        viewModel.onEnterSelection(3)
        advanceUntilIdle()
        viewModel.bulkExport(com.unchunks.echomark.domain.export.ExportFormat.MARKDOWN, "content://out/2")
        advanceUntilIdle()
        assertEquals(listOf(3L), exports.multiExports.single().ids)
        assertEquals(2, messages.size)
    }

    @Test
    fun 外すなどで一覧から無くなったものは選んでいる数に入らない() = runTest {
        val (id, viewModel) = setUp()
        start(viewModel)
        advanceUntilIdle()
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()

        collections.remove(id, 1)
        advanceUntilIdle()

        assertEquals(setOf(2L), viewModel.uiState.value.selectedIds)
    }

    @Test
    fun 選択肢にはコレクションとカテゴリが出る() = runTest {
        val (_, viewModel) = setUp()
        collections.create("別のコレクション")
        start(viewModel)
        advanceUntilIdle()

        assertEquals(listOf("集め物", "別のコレクション"), viewModel.uiState.value.collections.map { it.name })
    }
}
