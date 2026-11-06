package com.unchunks.echomark.ui.collections

import androidx.lifecycle.SavedStateHandle
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.export.ExportException
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.export.ExportResult
import com.unchunks.echomark.testing.FakeCollectionRepository
import com.unchunks.echomark.testing.FakeExportRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeCollectionRepository()
    private val exports = FakeExportRepository()

    private fun bookmark(id: Long) = Bookmark(id = id, type = BookmarkType.TEXT, title = "題$id", createdAt = id, lastAccessedAt = id)

    private fun TestScope.start(viewModel: CollectionsViewModel): MutableList<CollectionsMessage> {
        val messages = mutableListOf<CollectionsMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun 作ったコレクションが件数つきで並ぶ() = runTest {
        val a = (repository.create("A") as CollectionResult.Saved).id
        repository.create("B")
        repository.bookmarks.value = listOf(bookmark(1), bookmark(2))
        repository.add(a, 1)
        repository.add(a, 2)
        val viewModel = CollectionsViewModel(repository)
        start(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("A" to 2, "B" to 0), state.collections.map { it.name to it.bookmarkCount })
    }

    @Test
    fun 作る_直すの結果をそのまま返し_重複した名前は断る() = runTest {
        val viewModel = CollectionsViewModel(repository)
        start(viewModel)
        val results = mutableListOf<CollectionResult>()

        viewModel.create("A", "説明") { results += it }
        viewModel.create("a", "") { results += it }
        viewModel.create("  ", "") { results += it }
        advanceUntilIdle()
        val id = (results[0] as CollectionResult.Saved).id
        viewModel.update(id, "A2", "") { results += it }
        advanceUntilIdle()

        assertEquals(CollectionResult.NameTaken, results[1])
        assertEquals(CollectionResult.InvalidName, results[2])
        assertEquals(CollectionResult.Saved(id), results[3])
        assertEquals(listOf("A2"), viewModel.uiState.value.collections.map { it.name })
    }

    @Test
    fun 削除すると元に戻せるよう知らせ_戻すと同じものが戻る() = runTest {
        val id = (repository.create("A") as CollectionResult.Saved).id
        repository.bookmarks.value = listOf(bookmark(1))
        repository.add(id, 1)
        val viewModel = CollectionsViewModel(repository)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.delete(viewModel.uiState.value.collections.single())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.collections.isEmpty())
        val deleted = (messages.single() as CollectionsMessage.Deleted).deleted
        assertEquals("A", deleted.name)

        viewModel.restore(deleted)
        advanceUntilIdle()
        assertEquals(listOf("A" to 1), viewModel.uiState.value.collections.map { it.name to it.bookmarkCount })
    }

    @Test
    fun 同じ名前を作ったあとは元に戻せず知らせる() = runTest {
        val id = (repository.create("A") as CollectionResult.Saved).id
        val viewModel = CollectionsViewModel(repository)
        val messages = start(viewModel)
        advanceUntilIdle()
        viewModel.delete(viewModel.uiState.value.collections.single())
        advanceUntilIdle()
        val deleted = (messages.single() as CollectionsMessage.Deleted).deleted
        repository.create("A")

        viewModel.restore(deleted)
        advanceUntilIdle()

        assertEquals(CollectionsMessage.RestoreFailed, messages.last())
        assertEquals(1, viewModel.uiState.value.collections.size)
        assertTrue(id != viewModel.uiState.value.collections.single().id)
    }

    // ---- コレクションの中身 ----

    private fun detailViewModel(id: Long) =
        CollectionDetailViewModel(SavedStateHandle(mapOf(CollectionDetailViewModel.ARG_COLLECTION_ID to id)), repository, exports)

    private fun TestScope.start(viewModel: CollectionDetailViewModel): MutableList<CollectionDetailMessage> {
        val messages = mutableListOf<CollectionDetailMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun コレクションの中身を入れた順に出し_外すと元に戻せるよう知らせる() = runTest {
        val id = (repository.create("A") as CollectionResult.Saved).id
        repository.bookmarks.value = listOf(bookmark(1), bookmark(2))
        repository.add(id, 2)
        repository.add(id, 1)
        val viewModel = detailViewModel(id)
        val messages = start(viewModel)
        advanceUntilIdle()

        assertEquals(listOf(2L, 1L), viewModel.uiState.value.bookmarks.map { it.id })
        assertEquals("A", viewModel.uiState.value.collection?.name)

        viewModel.remove(bookmark(2))
        advanceUntilIdle()
        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })
        assertEquals(listOf<CollectionDetailMessage>(CollectionDetailMessage.Removed(bookmark(2))), messages)

        viewModel.add(bookmark(2))
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())
    }

    @Test
    fun 削除されたコレクションは無いものとして出す() = runTest {
        val viewModel = detailViewModel(999L)
        start(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.collection)
    }

    @Test
    fun 書き出すと結果を知らせ_失敗は理由を知らせる() = runTest {
        val id = (repository.create("A") as CollectionResult.Saved).id
        exports.result = ExportResult(exported = 3, skipped = 1)
        val viewModel = detailViewModel(id)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.export(ExportFormat.BROWSER_HTML, "content://out/1")
        advanceUntilIdle()
        exports.failure = ExportException("ファイルに書き込めませんでした")
        viewModel.export(ExportFormat.MARKDOWN, "content://out/2")
        advanceUntilIdle()
        exports.failure = IllegalStateException("想定外")
        viewModel.export(ExportFormat.MARKDOWN, "content://out/3")
        advanceUntilIdle()

        assertEquals(
            listOf(id to ExportFormat.BROWSER_HTML, id to ExportFormat.MARKDOWN, id to ExportFormat.MARKDOWN),
            exports.collectionExports.map { it.first to it.second }
        )
        assertEquals(CollectionDetailMessage.Exported(ExportResult(3, 1), ExportFormat.BROWSER_HTML), messages[0])
        assertEquals(CollectionDetailMessage.Failed("ファイルに書き込めませんでした"), messages[1])
        assertTrue((messages[2] as CollectionDetailMessage.Failed).message.isNotBlank())
    }

    @Test
    fun 書き出し後の知らせは飛ばした件数を添える() {
        assertEquals("3件を書き出しました", exportedText(3, 0))
        assertEquals("3件を書き出しました(リンクの無い2件は、この形式には入りません)", exportedText(3, 2))
    }
}
