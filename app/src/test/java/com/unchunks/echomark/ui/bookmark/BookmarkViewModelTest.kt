package com.unchunks.echomark.ui.bookmark

import androidx.lifecycle.SavedStateHandle
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeTagRepository
import com.unchunks.echomark.testing.MainDispatcherRule
import com.unchunks.echomark.testing.testBookmark
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeBookmarkRepository()
    private val tagRepository = FakeTagRepository()
    private val recentlyDeleted = RecentlyDeletedBookmarks()
    private val savedStateHandle = SavedStateHandle()

    private fun createViewModel() = BookmarkViewModel(repository, tagRepository, recentlyDeleted, savedStateHandle)

    /**
     * uiState を購読し、届いたメッセージを集める。
     * メッセージは送られた時点で受け取る(advanceUntilIdle は backgroundScope の処理を待たないため、Unconfined で集める)
     */
    private fun TestScope.start(viewModel: BookmarkViewModel): MutableList<BookmarkListMessage> {
        val messages = mutableListOf<BookmarkListMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun 初期状態はローディングで購読後に一覧と件数つきタグが反映される() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1), testBookmark(2))
        tagRepository.tags.value = listOf(TagWithCount(10, "kotlin", 2), TagWithCount(11, "unused", 0))
        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value.isLoading)

        start(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(setOf(1L, 2L), state.bookmarks.map { it.id }.toSet())
        // 件数 0 のタグは絞り込みチップに出さない
        assertEquals(listOf("kotlin"), state.allTags.map { it.name })
        assertEquals(2, state.totalCount)
    }

    @Test
    fun 絞り込みでアーカイブとお気に入りを切り替えられる() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1),
            testBookmark(2).copy(isArchived = true),
            testBookmark(3).copy(isFavorite = true)
        )
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        assertEquals(setOf(1L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())

        viewModel.onFilterChange(BookmarkFilter.ARCHIVED)
        advanceUntilIdle()
        assertEquals(listOf(2L), viewModel.uiState.value.bookmarks.map { it.id })

        viewModel.onFilterChange(BookmarkFilter.FAVORITES)
        advanceUntilIdle()
        assertEquals(listOf(3L), viewModel.uiState.value.bookmarks.map { it.id })
        assertEquals(BookmarkFilter.FAVORITES, viewModel.uiState.value.filter)
    }

    @Test
    fun 並べ替えを変えると順番が変わる() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1, title = "banana").copy(createdAt = 100),
            testBookmark(2, title = "Apple").copy(createdAt = 300),
            testBookmark(3, title = "cherry").copy(createdAt = 200)
        )
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        assertEquals(listOf(2L, 3L, 1L), viewModel.uiState.value.bookmarks.map { it.id })

        viewModel.onSortOrderChange(BookmarkSortOrder.OLDEST)
        advanceUntilIdle()
        assertEquals(listOf(1L, 3L, 2L), viewModel.uiState.value.bookmarks.map { it.id })

        viewModel.onSortOrderChange(BookmarkSortOrder.TITLE)
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L, 3L), viewModel.uiState.value.bookmarks.map { it.id })
    }

    @Test
    fun タグを選ぶとそのタグで絞り込み選択中のタグが先頭に来る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(tags = listOf("11")), testBookmark(2))
        tagRepository.tags.value = listOf(TagWithCount(10, "a", 1), TagWithCount(11, "b", 1))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.onTagSelected(11L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(11L, state.selectedTagId)
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
        assertEquals(listOf("b", "a"), state.allTags.map { it.name })
    }

    @Test
    fun タグ管理画面から渡されたタグで絞り込む() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(tags = listOf("10")), testBookmark(2))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchActiveChange(true)
        advanceUntilIdle()

        savedStateHandle[BookmarkViewModel.KEY_SELECT_TAG_ID] = 10L
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(10L, state.selectedTagId)
        assertFalse("検索モードは閉じる", state.isSearchActive)
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
    }

    @Test
    fun 検索語の入力は300msデバウンスされてから検索が走る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple pie"), testBookmark(2, "banana"))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        repository.searchCalls.clear()

        viewModel.onSearchQueryChange("app")
        advanceTimeBy(299)
        runCurrent()
        assertTrue("デバウンス中は検索しない", repository.searchCalls.isEmpty())
        assertTrue("入力中は検索中の表示", viewModel.uiState.value.isSearching)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("app" to null), repository.searchCalls)
        val state = viewModel.uiState.value
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
        assertFalse(state.isSearching)
        assertTrue(state.isShowingSearchResults)
    }

    @Test
    fun 連続入力では最後の検索語だけで検索する() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"))
        val viewModel = createViewModel()
        start(viewModel)
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
    fun 検索結果は絞り込みに従いアーカイブ済みを含めない() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1, "apple"),
            testBookmark(2, "apple archived").copy(isArchived = true)
        )
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()

        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })
    }

    @Test
    fun 意味検索の可否と意味だけで見つかったものが状態に出る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"), testBookmark(2, "fruit"))
        repository.semanticOnlyIds = setOf(2L)
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(1L, 2L), state.bookmarks.map { it.id }.toSet())
        assertEquals(setOf(2L), state.semanticMatchIds)
        assertTrue(state.semanticSearchAvailable)

        repository.semanticAvailable = false
        repository.semanticOnlyIds = emptySet()
        viewModel.onSearchQueryChange("banana")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.semanticSearchAvailable)
        assertEquals(ListEmptyKind.NO_SEARCH_RESULT, viewModel.uiState.value.emptyKind)
    }

    @Test
    fun 検索を閉じると検索語が消えて一覧に戻る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"), testBookmark(2, "banana"))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.bookmarks.size)
        repository.searchCalls.clear()

        viewModel.onSearchActiveChange(false)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(repository.searchCalls.isEmpty())
        assertFalse(state.isSearchActive)
        assertEquals("", state.searchQuery)
        assertEquals(2, state.bookmarks.size)
    }

    @Test
    fun 空状態の種類が状況で変わる() = runTest {
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        assertEquals(ListEmptyKind.FIRST_RUN, viewModel.uiState.value.emptyKind)

        repository.bookmarks.value = listOf(testBookmark(1).copy(isArchived = true))
        advanceUntilIdle()
        assertEquals(ListEmptyKind.ALL_ARCHIVED, viewModel.uiState.value.emptyKind)

        viewModel.onFilterChange(BookmarkFilter.FAVORITES)
        advanceUntilIdle()
        assertEquals(ListEmptyKind.NO_FAVORITES, viewModel.uiState.value.emptyKind)
    }

    @Test
    fun スワイプ削除は削除して取り消し用のメッセージを出し元に戻せる() = runTest {
        val target = testBookmark(1)
        repository.bookmarks.value = listOf(target)
        val viewModel = createViewModel()
        val messages = start(viewModel)

        viewModel.deleteBookmark(target)
        advanceUntilIdle()
        assertEquals(listOf(target), repository.deleted)
        assertEquals(listOf<BookmarkListMessage>(BookmarkListMessage.Deleted(target)), messages)

        viewModel.restoreBookmark(target)
        advanceUntilIdle()
        assertEquals(listOf(target), repository.restored)
    }

    @Test
    fun スワイプでアーカイブし取り消すと元に戻る() = runTest {
        val target = testBookmark(1)
        repository.bookmarks.value = listOf(target, testBookmark(2))
        val viewModel = createViewModel()
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.setArchived(target, archived = true)
        advanceUntilIdle()
        assertEquals(listOf(2L), viewModel.uiState.value.bookmarks.map { it.id })
        assertEquals(listOf<BookmarkListMessage>(BookmarkListMessage.ArchiveChanged(target, true)), messages)

        viewModel.undoArchive(target)
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())
    }

    @Test
    fun お気に入りを切り替えられる() = runTest {
        val target = testBookmark(1)
        repository.bookmarks.value = listOf(target)
        val viewModel = createViewModel()

        viewModel.toggleFavorite(target)
        advanceUntilIdle()

        assertTrue(repository.bookmarks.value.single().isFavorite)
    }

    @Test
    fun 詳細画面で削除されたものを受け取って取り消しのメッセージを出す() = runTest {
        val target = testBookmark(5)
        val viewModel = createViewModel()
        val messages = start(viewModel)
        advanceUntilIdle()

        recentlyDeleted.notifyDeleted(target)
        advanceUntilIdle()

        assertEquals(listOf<BookmarkListMessage>(BookmarkListMessage.Deleted(target)), messages)
    }

    @Test
    fun URLを保存すると正規化して保存結果を知らせ重複も伝える() = runTest {
        val viewModel = createViewModel()
        val messages = start(viewModel)

        viewModel.save(NewBookmarkInput.Link(url = " example.com/a ", title = "", memo = "あとで"))
        advanceUntilIdle()
        viewModel.save(NewBookmarkInput.Link(url = "https://example.com/a"))
        advanceUntilIdle()

        assertEquals("https://example.com/a", repository.savedUrls.first().first)
        assertEquals("あとで", repository.savedUrls.first().third)
        assertEquals(
            listOf<BookmarkListMessage>(
                BookmarkListMessage.Saved(1, isDuplicate = false, isLink = true),
                BookmarkListMessage.Saved(1, isDuplicate = true, isLink = true)
            ),
            messages
        )
    }

    @Test
    fun 形式の正しくないURLは保存せず失敗を知らせる() = runTest {
        val viewModel = createViewModel()
        val messages = start(viewModel)

        viewModel.save(NewBookmarkInput.Link(url = "hello"))
        advanceUntilIdle()

        assertTrue(repository.savedUrls.isEmpty())
        assertTrue(messages.single() is BookmarkListMessage.SaveFailed)
    }

    @Test
    fun メモはタイトルが空なら本文の1行目をタイトルにして保存する() = runTest {
        val viewModel = createViewModel()
        val messages = start(viewModel)

        viewModel.save(NewBookmarkInput.Note(text = "\n  買い物リスト \n牛乳", title = ""))
        advanceUntilIdle()

        val saved = repository.bookmarks.value.single()
        assertEquals("買い物リスト", saved.title)
        assertEquals(BookmarkType.TEXT, saved.type)
        assertEquals(BookmarkListMessage.Saved(saved.id, isDuplicate = false, isLink = false), messages.single())
    }

    @Test
    fun 長い1行目は切り詰めてタイトルにする() {
        val title = BookmarkViewModel.deriveTitle("あ".repeat(50))
        assertEquals(41, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun タイトルが空白のテキスト保存は無視される() = runTest {
        val viewModel = createViewModel()
        var called = false

        viewModel.saveTextBookmark("   ", "body") { called = true }
        advanceUntilIdle()

        assertFalse(called)
        assertTrue(repository.bookmarks.value.isEmpty())
    }

    @Test
    fun 今日の再発見はしばらく開いていないものを出し削除すると消える() = runTest {
        // lastAccessedAt = 0 は十分古い
        val stale = testBookmark(1)
        repository.bookmarks.value = listOf(stale, testBookmark(2, lastAccessedAt = System.currentTimeMillis()))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        assertEquals(listOf(1L), viewModel.uiState.value.rediscover.map { it.id })
        assertTrue(viewModel.uiState.value.showRediscover)

        viewModel.deleteBookmark(stale)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.rediscover.isEmpty())
    }
}
