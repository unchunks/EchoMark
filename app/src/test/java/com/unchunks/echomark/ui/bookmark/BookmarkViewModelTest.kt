package com.unchunks.echomark.ui.bookmark

import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.MainDispatcherRule
import com.unchunks.echomark.testing.testBookmark
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeBookmarkRepository()

    private fun createViewModel() = BookmarkViewModel(repository)

    @Test
    fun 初期状態はローディングで購読後に一覧とタグが反映される() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1), testBookmark(2))
        repository.tags.value = listOf(Tag(10, "kotlin"))
        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value.isLoading)

        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(1L, 2L), state.bookmarks.map { it.id })
        assertEquals(listOf("kotlin"), state.allTags.map { it.name })
    }

    @Test
    fun 検索語の入力は300msデバウンスされてから検索が走る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple pie"), testBookmark(2, "banana"))
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        repository.searchCalls.clear()

        viewModel.onSearchQueryChange("app")
        advanceTimeBy(299)
        runCurrent()
        assertTrue("デバウンス中は検索しない", repository.searchCalls.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("app" to null), repository.searchCalls)
        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })
    }

    @Test
    fun 連続入力では最後の検索語だけで検索する() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"))
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        repository.searchCalls.clear()

        viewModel.onSearchQueryChange("a")
        advanceTimeBy(100)
        viewModel.onSearchQueryChange("ap")
        advanceTimeBy(100)
        viewModel.onSearchQueryChange("app")
        advanceUntilIdle()

        assertEquals(listOf("app" to null), repository.searchCalls)
    }

    @Test
    fun 検索語を空に戻すと検索せず即座に全件表示に戻る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"), testBookmark(2, "banana"))
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.bookmarks.size)
        repository.searchCalls.clear()

        viewModel.onSearchQueryChange("")
        runCurrent()

        assertTrue(repository.searchCalls.isEmpty())
        assertEquals(2, viewModel.uiState.value.bookmarks.size)
    }

    @Test
    fun タグ選択で該当タグの購読に切り替わる() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1).copy(tags = listOf("10")),
            testBookmark(2)
        )
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.onTagSelected(10L)
        advanceUntilIdle()

        assertEquals(listOf(10L), repository.observedTagIds)
        assertEquals(10L, viewModel.uiState.value.selectedTagId)
        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })
    }

    @Test
    fun 削除と復元がリポジトリに伝わる() = runTest {
        val target = testBookmark(1)
        repository.bookmarks.value = listOf(target)
        val viewModel = createViewModel()

        viewModel.deleteBookmark(target)
        advanceUntilIdle()
        assertEquals(listOf(target), repository.deleted)

        viewModel.restoreBookmark(target)
        advanceUntilIdle()
        assertEquals(listOf(target), repository.restored)
    }

    @Test
    fun タイトルが空白のテキスト保存は無視される() = runTest {
        val viewModel = createViewModel()
        var called = false

        viewModel.saveTextBookmark("   ", "body") { called = true }
        advanceUntilIdle()

        assertTrue(!called)
        assertTrue(repository.bookmarks.value.isEmpty())
    }
}
