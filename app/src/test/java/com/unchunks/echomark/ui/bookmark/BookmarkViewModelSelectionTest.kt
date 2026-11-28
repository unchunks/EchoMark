package com.unchunks.echomark.ui.bookmark

import androidx.lifecycle.SavedStateHandle
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.export.ExportException
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.export.ExportResult
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeAttachmentRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeCategoryRepository
import com.unchunks.echomark.testing.FakeCollectionRepository
import com.unchunks.echomark.testing.FakeExportRepository
import com.unchunks.echomark.testing.FakeSmartFolderRepository
import com.unchunks.echomark.testing.FakeTagRepository
import com.unchunks.echomark.testing.testBookmark
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
import com.unchunks.echomark.ui.common.SearchHighlights
import com.unchunks.echomark.ui.selection.BulkBookmarkActions
import com.unchunks.echomark.ui.selection.BulkMessage
import kotlinx.coroutines.Dispatchers
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

/**
 * 一覧の複数選択(長押し・メニューの「選択」)と、まとめての操作のテスト。
 * 選択は検索結果・スマートフォルダの中でも使え、回転やプロセスの再作成のあとも戻る。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModelSelectionTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeBookmarkRepository()
    private val collections = FakeCollectionRepository()
    private val exports = FakeExportRepository()
    private val smartFolders = FakeSmartFolderRepository()

    private fun createViewModel(handle: SavedStateHandle = SavedStateHandle()) = BookmarkViewModel(
        repository, FakeTagRepository(), FakeCategoryRepository(), RecentlyDeletedBookmarks(), FakeAttachmentRepository(),
        FakeAppSettingsRepository(), TestDispatcherProvider(Dispatchers.Unconfined), SearchHighlights(), smartFolders,
        collections, BulkBookmarkActions(repository, collections, exports), handle
    )

    /** uiState を購読し、届いたメッセージを集める */
    private fun TestScope.start(viewModel: BookmarkViewModel): MutableList<BookmarkListMessage> {
        val messages = mutableListOf<BookmarkListMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    private fun TestScope.started(vararg bookmarks: Bookmark): Pair<BookmarkViewModel, MutableList<BookmarkListMessage>> {
        repository.bookmarks.value = bookmarks.toList()
        val viewModel = createViewModel()
        val messages = start(viewModel)
        advanceUntilIdle()
        return viewModel to messages
    }

    private fun BookmarkViewModel.selectedIds(): Set<Long> = uiState.value.selectedIds

    private fun List<BookmarkListMessage>.bulk(): List<BulkMessage> = map { (it as BookmarkListMessage.Bulk).message }

    private fun sorted() = repository.bookmarks.value.sortedBy { it.id }

    // ---- 選択モード ----

    @Test
    fun 長押しで選択モードに入り_そのブックマークを選んだ状態で始まる() = runTest {
        val (viewModel, _) = started(testBookmark(1), testBookmark(2))
        assertFalse(viewModel.uiState.value.isSelecting)

        viewModel.onEnterSelection(2)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSelecting)
        assertEquals(setOf(2L), viewModel.selectedIds())
        assertEquals(listOf(2L), viewModel.uiState.value.selectedBookmarks.map { it.id })
    }

    @Test
    fun メニューの選択で入ると何も選んでおらず_タップで選び外せる() = runTest {
        val (viewModel, _) = started(testBookmark(1), testBookmark(2))

        viewModel.onEnterSelection()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSelecting)
        assertEquals(emptySet<Long>(), viewModel.selectedIds())

        viewModel.onToggleSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L), viewModel.selectedIds())

        viewModel.onToggleSelection(1)
        advanceUntilIdle()
        assertEquals(setOf(2L), viewModel.selectedIds())
    }

    @Test
    fun 選択モードでなければ選べない() = runTest {
        val (viewModel, _) = started(testBookmark(1))

        viewModel.onToggleSelection(1)
        viewModel.onSelectAll()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSelecting)
        assertEquals(emptySet<Long>(), viewModel.selectedIds())
    }

    @Test
    fun すべて選択は今の一覧に出ているものだけを選び_選びを解いても選択モードのまま_戻ると終わる() = runTest {
        val (viewModel, _) = started(testBookmark(1), testBookmark(2).copy(isFavorite = true), testBookmark(3).copy(isFavorite = true))
        viewModel.onFilterChange(BookmarkFilter.FAVORITES)
        viewModel.onEnterSelection()
        advanceUntilIdle()

        viewModel.onSelectAll()
        advanceUntilIdle()
        assertEquals(setOf(2L, 3L), viewModel.selectedIds())

        viewModel.onClearSelection()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSelecting)
        assertEquals(emptySet<Long>(), viewModel.selectedIds())

        viewModel.onExitSelection()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSelecting)
    }

    @Test
    fun 検索結果でも選べて_すべて選択は検索結果だけを選ぶ() = runTest {
        val (viewModel, _) = started(testBookmark(1, "apple pie"), testBookmark(2, "banana"), testBookmark(3, "apple tart"))
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()
        assertEquals(setOf(1L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())

        viewModel.onEnterSelection(1)
        viewModel.onSelectAll()
        advanceUntilIdle()

        assertEquals(setOf(1L, 3L), viewModel.selectedIds())
    }

    @Test
    fun スマートフォルダの中でも選べる() = runTest {
        val (viewModel, _) = started(testBookmark(1).copy(isFavorite = true), testBookmark(2))
        smartFolders.create("お気に入り", BookmarkQuery(filter = BookmarkFilter.FAVORITES), null)
        advanceUntilIdle()
        viewModel.onSmartFolderSelected(smartFolders.folders.value.single())
        advanceUntilIdle()

        viewModel.onEnterSelection(1)
        viewModel.onSelectAll()
        advanceUntilIdle()

        assertEquals(setOf(1L), viewModel.selectedIds())
    }

    @Test
    fun 絞り込みで一覧から外れたものは選んでいる数にも操作の対象にも入らない() = runTest {
        val (viewModel, messages) = started(testBookmark(1), testBookmark(2).copy(isFavorite = true))
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L), viewModel.selectedIds())

        // お気に入りだけに絞ると、1 は一覧に出ない
        viewModel.onFilterChange(BookmarkFilter.FAVORITES)
        advanceUntilIdle()
        assertEquals(setOf(2L), viewModel.selectedIds())

        viewModel.bulkSetArchived(true)
        advanceUntilIdle()

        assertEquals(listOf(false, true), sorted().map { it.isArchived })
        assertEquals(1, messages.size)
    }

    @Test
    fun 選択は回転やプロセスの再作成のあとの新しいViewModelにも戻る() = runTest {
        val handle = SavedStateHandle()
        repository.bookmarks.value = listOf(testBookmark(1), testBookmark(2), testBookmark(3))
        val first = createViewModel(handle)
        backgroundScope.launch { first.uiState.collect {} }
        advanceUntilIdle()
        first.onEnterSelection(1)
        first.onToggleSelection(3)
        advanceUntilIdle()

        val second = createViewModel(handle)
        backgroundScope.launch { second.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(second.uiState.value.isSelecting)
        assertEquals(setOf(1L, 3L), second.selectedIds())
    }

    // ---- まとめての操作 ----

    @Test
    fun 選んだものをまとめてアーカイブして_取り消すと元の状態に戻る() = runTest {
        val (viewModel, messages) = started(testBookmark(1), testBookmark(2), testBookmark(3))
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()

        viewModel.bulkSetArchived(true)
        advanceUntilIdle()

        // 通常の一覧からは外れ、選択モードも終わる
        assertEquals(listOf(3L), viewModel.uiState.value.bookmarks.map { it.id })
        assertFalse(viewModel.uiState.value.isSelecting)
        val message = messages.bulk().single() as BulkMessage.Archived
        assertTrue(message.archived)
        assertEquals(setOf(1L, 2L), message.before.map { it.id }.toSet())

        viewModel.undoBulkArchive(message.before)
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())
    }

    @Test
    fun お気に入りと既読をまとめて変える() = runTest {
        val (viewModel, messages) = started(testBookmark(1), testBookmark(2), testBookmark(3))

        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()
        viewModel.bulkSetFavorite(true)
        advanceUntilIdle()
        assertEquals(listOf(true, true, false), sorted().map { it.isFavorite })

        viewModel.onEnterSelection(2)
        viewModel.onToggleSelection(3)
        advanceUntilIdle()
        viewModel.bulkSetRead(true)
        advanceUntilIdle()
        assertEquals(listOf(false, true, true), sorted().map { it.isRead })

        assertEquals(
            listOf(BulkMessage.Done("2件をお気に入りに追加しました"), BulkMessage.Done("2件を既読にしました")),
            messages.bulk()
        )
    }

    @Test
    fun まとめてタグを付けて外す() = runTest {
        val (viewModel, messages) = started(testBookmark(1).copy(tags = listOf("既存")), testBookmark(2))
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()

        viewModel.bulkAddTag(" 共通 ")
        advanceUntilIdle()
        assertEquals(listOf(listOf("既存", "共通"), listOf("共通")), sorted().map { it.tags })

        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()
        viewModel.bulkRemoveTag("共通")
        advanceUntilIdle()
        assertEquals(listOf(listOf("既存"), emptyList<String>()), sorted().map { it.tags })
        assertEquals(
            listOf(BulkMessage.Done("2件に「共通」を付けました"), BulkMessage.Done("2件から「共通」を外しました")),
            messages.bulk()
        )
    }

    @Test
    fun 空のタグ名は付けず_選んだまま知らせる() = runTest {
        val (viewModel, messages) = started(testBookmark(1))
        viewModel.onEnterSelection(1)
        advanceUntilIdle()

        viewModel.bulkAddTag("  ")
        advanceUntilIdle()

        assertTrue(messages.bulk().single() is BulkMessage.Failed)
        // 失敗したときは選んだままで、もう一度試せる
        assertTrue(viewModel.uiState.value.isSelecting)
        assertEquals(setOf(1L), viewModel.selectedIds())
    }

    @Test
    fun まとめてカテゴリを決める() = runTest {
        val (viewModel, _) = started(testBookmark(1), testBookmark(2))
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()

        viewModel.bulkSetCategory("資料")
        advanceUntilIdle()

        assertEquals(listOf("資料", "資料"), sorted().map { it.category })
        assertEquals(listOf(true, true), sorted().map { it.categoryEdited })
    }

    @Test
    fun コレクションに追加と_新しく作って追加ができる() = runTest {
        val (viewModel, messages) = started(testBookmark(1), testBookmark(2))
        collections.bookmarks.value = repository.bookmarks.value
        val existing = (collections.create("既存") as CollectionResult.Saved).id
        advanceUntilIdle()
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()
        assertEquals(listOf("既存"), viewModel.uiState.value.collections.map { it.name })

        viewModel.bulkAddToCollection(viewModel.uiState.value.collections.single())
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L), collections.observeBookmarks(existing).first().map { it.id }.toSet())
        assertFalse(viewModel.uiState.value.isSelecting)

        // 同じ名前は作らず、選択も続く
        viewModel.onEnterSelection(1)
        advanceUntilIdle()
        val results = mutableListOf<CollectionResult>()
        viewModel.bulkCreateCollectionAndAdd("既存") { results += it }
        advanceUntilIdle()
        assertEquals(listOf<CollectionResult>(CollectionResult.NameTaken), results)
        assertTrue(viewModel.uiState.value.isSelecting)

        viewModel.bulkCreateCollectionAndAdd("新しい") { results += it }
        advanceUntilIdle()
        assertTrue(results.last() is CollectionResult.Saved)
        assertFalse(viewModel.uiState.value.isSelecting)
        assertEquals(listOf("既存", "新しい"), viewModel.uiState.value.collections.map { it.name })
        assertEquals(BulkMessage.Done("新しいコレクション「新しい」に1件を入れました"), messages.bulk().last())
    }

    @Test
    fun 再処理は件ごとにreprocessせず順番の列にまとめて積む() = runTest {
        val (viewModel, messages) = started(testBookmark(1), testBookmark(2), testBookmark(3))
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(3)
        advanceUntilIdle()

        viewModel.bulkReprocess()
        advanceUntilIdle()

        assertEquals(listOf(setOf(1L, 3L)), repository.reprocessAllCalls.map { it.toSet() })
        assertTrue("件ごとの再処理(急ぐ処理)は使わない", repository.reprocessCalls.isEmpty())
        assertEquals(BulkMessage.Done("2件を再処理の順番待ちに入れました。1件ずつ順番に処理します"), messages.bulk().single())
        assertFalse(viewModel.uiState.value.isSelecting)
    }

    @Test
    fun 順番の列に積めない設定のときの再処理は処理待ちにしたと知らせる() = runTest {
        repository.reprocessAllStarted = false
        val (viewModel, messages) = started(testBookmark(1))
        viewModel.onEnterSelection(1)
        advanceUntilIdle()

        viewModel.bulkReprocess()
        advanceUntilIdle()

        val text = (messages.bulk().single() as BulkMessage.Done).text
        assertTrue(text, text.startsWith("1件を処理待ちにしました"))
    }

    @Test
    fun 選んだものを書き出す() = runTest {
        exports.result = ExportResult(exported = 2, skipped = 1)
        val (viewModel, messages) = started(testBookmark(1), testBookmark(2), testBookmark(3))
        viewModel.onEnterSelection(3)
        viewModel.onToggleSelection(1)
        advanceUntilIdle()

        viewModel.bulkExport(ExportFormat.BROWSER_HTML, "content://out/1")
        advanceUntilIdle()

        val export = exports.multiExports.single()
        assertEquals(setOf(1L, 3L), export.ids.toSet())
        assertEquals(ExportFormat.BROWSER_HTML, export.format)
        assertEquals("content://out/1", export.uri)
        assertEquals(BulkMessage.Done("2件を書き出しました(リンクの無い1件は、この形式には入りません)"), messages.bulk().single())
    }

    @Test
    fun 書き出せなかったときは選んだまま知らせる() = runTest {
        exports.failure = ExportException("ファイルに書き込めませんでした")
        val (viewModel, messages) = started(testBookmark(1))
        viewModel.onEnterSelection(1)
        advanceUntilIdle()

        viewModel.bulkExport(ExportFormat.MARKDOWN, "content://out/1")
        advanceUntilIdle()

        assertEquals(BulkMessage.Failed("ファイルに書き込めませんでした"), messages.bulk().single())
        assertTrue(viewModel.uiState.value.isSelecting)
        assertEquals(setOf(1L), viewModel.selectedIds())
    }

    @Test
    fun 選んだものを削除して取り消すと戻る() = runTest {
        val (viewModel, messages) = started(testBookmark(1), testBookmark(2), testBookmark(3))
        viewModel.onEnterSelection(1)
        viewModel.onToggleSelection(2)
        advanceUntilIdle()

        viewModel.bulkDelete()
        advanceUntilIdle()

        assertEquals(listOf(3L), viewModel.uiState.value.bookmarks.map { it.id })
        assertFalse(viewModel.uiState.value.isSelecting)
        val message = messages.bulk().single() as BulkMessage.Deleted
        assertEquals(setOf(1L, 2L), message.bookmarks.map { it.id }.toSet())

        viewModel.undoBulkDelete(message.bookmarks)
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())
    }

    @Test
    fun 選んでいないときの操作は何もしない() = runTest {
        val (viewModel, messages) = started(testBookmark(1))
        viewModel.onEnterSelection()
        advanceUntilIdle()

        viewModel.bulkDelete()
        viewModel.bulkSetFavorite(true)
        viewModel.bulkReprocess()
        advanceUntilIdle()

        assertTrue(messages.isEmpty())
        assertEquals(listOf(1L), repository.bookmarks.value.map { it.id })
        assertTrue(repository.reprocessAllCalls.isEmpty())
    }
}
