package com.unchunks.echomark.ui.graph

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.repository.DuplicateScan
import com.unchunks.echomark.domain.repository.RelatedGroup
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeDuplicateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GraphViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val duplicates = FakeDuplicateRepository()
    private val bookmarks = FakeBookmarkRepository()
    private val settings = FakeAppSettingsRepository()

    private fun bookmark(id: Long, vararg tags: String) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", createdAt = id, lastAccessedAt = id, tags = tags.toList()
    )

    private fun kotlinx.coroutines.test.TestScope.viewModel() = GraphViewModel(
        duplicates, bookmarks, settings, TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))
    )

    @Test
    fun 開くと関連のまとまりと共通のタグからグラフを作って並べる() = runTest {
        val list = listOf(bookmark(1, "a"), bookmark(2, "a"), bookmark(3), bookmark(4), bookmark(5))
        bookmarks.bookmarks.value = list
        duplicates.scanResult = DuplicateScan(
            emptyList(), listOf(RelatedGroup("g", listOf(list[2], list[3], list[4]))), embeddedCount = 5, totalCount = 5
        )
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(5, state.graph.nodes.size)
        assertEquals(5, state.layout.size)
        assertEquals(4, state.graph.edges.size) // 関連 3 組 + 共通のタグ 1 組
        assertFalse(state.indexIncomplete)
        assertEquals(listOf(DuplicateSensitivity.DEFAULT), duplicates.scanCalls)
    }

    @Test
    fun 検索用の索引が全部できていなければ知らせる() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1, "a"), bookmark(2, "a"))
        duplicates.scanResult = DuplicateScan(emptyList(), emptyList(), embeddedCount = 1, totalCount = 2)
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.indexIncomplete)
    }

    @Test
    fun 表示数やタグの絞り込みを変えても_つながりを探し直さない() = runTest {
        bookmarks.bookmarks.value = (1L..6L).map { bookmark(it, "a", if (it <= 3) "b" else "c") }
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(1, duplicates.scanCalls.size)

        vm.setMaxNodes(50)
        vm.setTagFilter("b")
        advanceUntilIdle()

        assertEquals(1, duplicates.scanCalls.size)
        assertEquals("b", vm.uiState.value.tagFilter)
        assertEquals(setOf(1L, 2L, 3L), vm.uiState.value.graph.nodes.map { it.id }.toSet())
    }

    @Test
    fun 探し直すと_もう一度探す() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1, "a"), bookmark(2, "a"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, duplicates.scanCalls.size)
    }

    @Test
    fun 選んだ点は_絞り込みで消えたら選択を外す() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1, "a", "x"), bookmark(2, "a", "x"), bookmark(3, "b"), bookmark(4, "b"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.selectNode(3)
        assertEquals(3L, vm.uiState.value.selectedId)

        vm.setTagFilter("a")
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedId)
    }

    @Test
    fun 絞り込みに選べるタグは付いている数の多い順() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1, "少", "多"), bookmark(2, "多"), bookmark(3, "多", "中"), bookmark(4, "中"))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("多", "中", "少"), vm.uiState.value.filterTags)
    }

    @Test
    fun 探すのに失敗したら_エラーを出して読み込み中を終える() = runTest {
        duplicates.failure = IllegalStateException("boom")
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun ブックマークが無ければ空のグラフ() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.graph.nodes.isEmpty())
        assertFalse(vm.uiState.value.isLoading)
    }
}
