package com.unchunks.echomark.ui.bookmark

import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.ui.common.SearchHighlights
import kotlinx.coroutines.Dispatchers
import com.unchunks.echomark.domain.bookmark.model.AttachmentError
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeAttachmentRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeCategoryRepository
import com.unchunks.echomark.testing.FakeTagRepository
import com.unchunks.echomark.testing.testBookmark
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
import com.unchunks.echomark.ui.common.SelectedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.MatchField
import com.unchunks.echomark.domain.bookmark.model.SearchSuggestion
import org.junit.Assert.assertNull
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
    private val categories = FakeCategoryRepository()
    private val recentlyDeleted = RecentlyDeletedBookmarks()
    private val attachments = FakeAttachmentRepository()
    private val settings = FakeAppSettingsRepository()
    private val highlights = SearchHighlights()
    private fun createViewModel() = BookmarkViewModel(repository, tagRepository, categories, recentlyDeleted, attachments, settings, TestDispatcherProvider(Dispatchers.Unconfined), highlights)

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
    fun 選んだ並び順は保存されアプリを開き直しても保つ() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1, title = "banana").copy(createdAt = 100),
            testBookmark(2, title = "Apple").copy(createdAt = 300)
        )
        val first = createViewModel()
        start(first)
        advanceUntilIdle()
        first.onSortOrderChange(BookmarkSortOrder.TITLE)
        advanceUntilIdle()
        assertEquals(BookmarkSortOrder.TITLE, settings.bookmarkSortOrderFlow.value)

        // プロセスが破棄された・アプリを開き直したあと(ViewModel を作り直す)も、選んだ順で出す
        val reopened = createViewModel()
        start(reopened)
        advanceUntilIdle()

        val state = reopened.uiState.value
        assertEquals(BookmarkSortOrder.TITLE, state.sortOrder)
        assertEquals(listOf(2L, 1L), state.bookmarks.map { it.id })
    }

    @Test
    fun 絞り込みやタグや検索を変えても並び順は変わらない() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, title = "b"), testBookmark(2, title = "a"))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSortOrderChange(BookmarkSortOrder.OLDEST)
        advanceUntilIdle()

        viewModel.onFilterChange(BookmarkFilter.FAVORITES)
        viewModel.onTagToggled(10L)
        viewModel.onSearchActiveChange(true)
        viewModel.onSearchActiveChange(false)
        viewModel.showTag(11L)
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(BookmarkSortOrder.OLDEST, viewModel.uiState.value.sortOrder)
    }

    @Test
    fun タグを選ぶとそのタグで絞り込み選択中のタグが先頭に来る() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(tags = listOf("11")), testBookmark(2))
        tagRepository.tags.value = listOf(TagWithCount(10, "a", 1), TagWithCount(11, "b", 1))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.onTagToggled(11L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(11L), state.selectedTagIds)
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
        assertEquals(listOf("b", "a"), state.allTags.map { it.name })
    }

    @Test
    fun タグ管理画面から渡されたタグで絞り込み検索モードを閉じる() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(tags = listOf("10")), testBookmark(2))
        tagRepository.tags.value = listOf(TagWithCount(10, "a", 1))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchActiveChange(true)
        advanceUntilIdle()

        viewModel.showTag(10L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(10L), state.selectedTagIds)
        assertFalse("検索モードは閉じる", state.isSearchActive)
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
    }

    @Test
    fun 検索語の入力は短くデバウンスされてからキーワード検索が走り意味検索は少し待つ() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple pie"), testBookmark(2, "banana"))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        repository.searchCalls.clear()

        viewModel.onSearchQueryChange("app")
        advanceTimeBy(119)
        runCurrent()
        assertTrue("デバウンス中は検索しない", repository.keywordSearchCalls.isEmpty())
        assertTrue("入力中は検索中の表示", viewModel.uiState.value.isSearching)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("キーワードの結果はすぐ出す", listOf("app"), repository.keywordSearchCalls)
        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })
        assertTrue("意味検索を待つ間は検索中の表示", viewModel.uiState.value.isSearching)
        assertTrue("入力が落ち着くまで検索語を埋め込まない", repository.prepareCalls.isEmpty())

        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("app"), repository.prepareCalls)
        assertEquals(listOf("app"), repository.searchCalls)
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

        assertEquals(listOf("app"), repository.searchCalls)
    }

    @Test
    fun 打っている間は検索語を埋め込まず止まったときだけ埋め込む() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.onSearchQueryChange("a")
        advanceTimeBy(200)
        viewModel.onSearchQueryChange("ap")
        advanceTimeBy(200)
        viewModel.onSearchQueryChange("app")
        advanceTimeBy(200)
        runCurrent()
        assertTrue("打っている間は埋め込まない", repository.prepareCalls.isEmpty())
        assertEquals("キーワードは打つたびに更新する", listOf("a", "ap", "app"), repository.keywordSearchCalls.distinct())

        advanceUntilIdle()
        assertEquals(listOf("app"), repository.prepareCalls)
    }

    @Test
    fun 検索結果にはどこに一致したかが付く() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1, "apple pie").copy(content = "baking notes about apple pie and cream"),
            testBookmark(2, "fruit").copy(content = "an apple a day")
        )
        repository.semanticOnlyIds = setOf(2L)
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("apple"), state.searchTerms)
        val first = state.searchMatches.getValue(1L)
        assertEquals(listOf(MatchField.TITLE, MatchField.CONTENT), first.fields)
        assertNull("タイトルで見える語は本文の一節を出さない", first.snippet)
        // 意味だけで見つかったもの(Fake は本文を見ない)でも、本文に語があれば一致として説明できる
        assertEquals(listOf(MatchField.CONTENT), state.searchMatches.getValue(2L).fields)
        assertTrue(state.searchMatches.getValue(2L).snippet != null)
    }

    @Test
    fun 検索中は検索語を詳細画面に知らせ閉じると消える() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.onSearchQueryChange("apple pie")
        advanceUntilIdle()
        viewModel.onOpenBookmark(1L)
        assertEquals(listOf("apple", "pie"), highlights.termsFor(1L).first())
        assertEquals("ほかのブックマークでは強調しない", emptyList<String>(), highlights.termsFor(2L).first())

        viewModel.onSearchActiveChange(false)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), highlights.termsFor(1L).first())
    }

    @Test
    fun 検索の操作をすると検索語を履歴に残し_空なら残さない() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("  ")
        viewModel.onSearchSubmitted()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), settings.searchHistoryFlow.value)

        viewModel.onSearchQueryChange("apple　pie")
        viewModel.onSearchSubmitted()
        advanceUntilIdle()
        assertEquals(listOf("apple pie"), settings.searchHistoryFlow.value)
    }

    @Test
    fun 検索結果を開いたら履歴に残し_検索していなければ残さない() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        viewModel.onOpenBookmark(1L)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), settings.searchHistoryFlow.value)

        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()
        viewModel.onOpenBookmark(1L)
        advanceUntilIdle()
        assertEquals(listOf("apple"), settings.searchHistoryFlow.value)
    }

    @Test
    fun 検索欄が空なら最近の検索を候補に出し_選ぶとその語で検索する() = runTest {
        settings.searchHistoryFlow.value = listOf("apple", "banana")
        repository.bookmarks.value = listOf(testBookmark(1, "apple"), testBookmark(2, "banana"))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchActiveChange(true)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(listOf(SearchSuggestion.History("apple"), SearchSuggestion.History("banana")), state.suggestions)
        val revision = state.queryRevision

        viewModel.onSuggestionSelected(SearchSuggestion.History("banana"))
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals("banana", state.searchQuery)
        assertEquals(listOf(2L), state.bookmarks.map { it.id })
        assertTrue("検索欄が入力中の文字を取り込み直せるよう、書き換えた回数を進める", state.queryRevision > revision)
        // 選んだ語は履歴の先頭に来る
        assertEquals(listOf("banana", "apple"), settings.searchHistoryFlow.value)
    }

    @Test
    fun 入力中の語に合うタグの候補を選ぶとその語を絞り込みに置き換える() = runTest {
        tagRepository.tags.value = listOf(TagWithCount(10, "kotlin", 2), TagWithCount(11, "swift", 1))
        repository.bookmarks.value = listOf(
            testBookmark(1, "memo a").copy(tags = listOf("10")),
            testBookmark(2, "memo b").copy(tags = listOf("11"))
        )
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("memo kot")
        advanceUntilIdle()

        val suggestion = viewModel.uiState.value.suggestions.single()
        assertEquals(SearchSuggestion.Tag(10, "kotlin"), suggestion)

        viewModel.onSuggestionSelected(suggestion)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("memo ", state.searchQuery)
        assertEquals(setOf(10L), state.selectedTagIds)
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
        // 選んだタグは、もう候補に出さない
        assertTrue(state.suggestions.none { it is SearchSuggestion.Tag })
    }

    @Test
    fun 履歴を1件消す_すべて消す() = runTest {
        settings.searchHistoryFlow.value = listOf("a", "b", "c")
        val viewModel = createViewModel()
        start(viewModel)

        viewModel.onHistoryRemoved("b")
        advanceUntilIdle()
        assertEquals(listOf("a", "c"), settings.searchHistoryFlow.value)

        viewModel.onHistoryCleared()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), settings.searchHistoryFlow.value)
    }

    @Test
    fun 種類と期間の条件は検索結果にも効く() = runTest {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        repository.bookmarks.value = listOf(
            testBookmark(1, "apple link").copy(type = BookmarkType.URL, createdAt = now - day),
            testBookmark(2, "apple note").copy(type = BookmarkType.TEXT, createdAt = now - day),
            testBookmark(3, "apple old link").copy(type = BookmarkType.URL, createdAt = now - 60 * day)
        )
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())

        viewModel.onTypeToggled(BookmarkType.URL)
        advanceUntilIdle()
        assertEquals(setOf(1L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())
        assertEquals(setOf(BookmarkType.URL), viewModel.uiState.value.selectedTypes)

        viewModel.onPeriodToggled(CreatedPeriod.PAST_MONTH)
        advanceUntilIdle()
        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })
        assertTrue(viewModel.uiState.value.isNarrowed)

        viewModel.onClearTypeAndPeriod()
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())
        assertFalse(viewModel.uiState.value.isNarrowed)

        // すべて解除でも種類と期間が外れる
        viewModel.onTypeToggled(BookmarkType.TEXT)
        viewModel.onPeriodToggled(CreatedPeriod.PAST_WEEK)
        viewModel.onClearFilters()
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())
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
    fun 検索語の埋め込みを待つ間もキーワードの結果を先に出す() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"), testBookmark(2, "fruit"))
        repository.semanticOnlyIds = setOf(2L)
        val gate = CompletableDeferred<Unit>()
        repository.prepareGate = gate
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()

        // 埋め込み(モデルの読み込み・AI 処理との順番待ち)が終わらなくても、キーワードで見つかったものは出す
        var state = viewModel.uiState.value
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
        assertTrue("意味検索の結果を待っている間は検索中の表示", state.isSearching)
        assertTrue("準備中はキーワードのみとは言わない", state.semanticSearchAvailable)
        assertEquals(ListEmptyKind.NONE, state.emptyKind)

        gate.complete(Unit)
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals(setOf(1L, 2L), state.bookmarks.map { it.id }.toSet())
        assertEquals(setOf(2L), state.semanticMatchIds)
        assertFalse(state.isSearching)
    }

    @Test
    fun 検索中にDBが変わっても検索語を埋め込み直さない() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"))
        val gate = CompletableDeferred<Unit>()
        repository.prepareGate = gate
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()

        // AI 処理などで DB が変わり続けても、埋め込みの待ちはやり直さず、キーワードの結果は新しくする
        repository.bookmarks.value = repository.bookmarks.value + testBookmark(2, "apple pie")
        advanceUntilIdle()
        assertEquals(setOf(1L, 2L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())

        gate.complete(Unit)
        repository.bookmarks.value = repository.bookmarks.value + testBookmark(3, "apple tart")
        advanceUntilIdle()

        assertEquals(listOf("apple"), repository.prepareCalls)
        assertEquals(setOf(1L, 2L, 3L), viewModel.uiState.value.bookmarks.map { it.id }.toSet())
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun 埋め込みが使えなければキーワードだけで検索する() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1, "apple"), testBookmark(2, "banana"))
        repository.semanticAvailable = false
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
        assertFalse(state.semanticSearchAvailable)
        assertFalse(state.isSearching)
        // 使えないと分かったら、意味検索込みの検索(埋め込みをもう一度試す)はしない
        assertTrue(repository.searchCalls.isEmpty())
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

    @Test
    fun 起動直後の操作が検索なら検索モードを開く() = runTest {
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.handleLaunchAction(ListLaunchAction.SEARCH)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSearchActive)
    }

    @Test
    fun 起動直後の操作が追加なら追加シートを開く要求を1回だけ出す() = runTest {
        val viewModel = createViewModel()
        start(viewModel)
        val requests = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.addSheetRequests.toList(requests) }
        advanceUntilIdle()
        assertTrue(requests.isEmpty())

        viewModel.handleLaunchAction(ListLaunchAction.ADD_BOOKMARK)
        advanceUntilIdle()

        assertEquals(1, requests.size)
        assertFalse(viewModel.uiState.value.isSearchActive)
    }

    @Test
    fun 選んだファイルを1件ずつ保存して結果をまとめて知らせる() = runTest {
        attachments.failures["content://p/broken.mp4"] = AttachmentError.ReadFailed
        val viewModel = createViewModel()
        val messages = start(viewModel)
        val files = listOf("content://p/a.jpg", "content://p/broken.mp4", "content://p/c.jpg")
            .map { SelectedFile(it, "image/jpeg", it.substringAfterLast('/'), 10L) }

        viewModel.save(NewBookmarkInput.Files(files, title = "使われない"))
        advanceUntilIdle()

        assertEquals(listOf("a", "c"), repository.bookmarks.value.map { it.title })
        assertEquals(BookmarkType.IMAGE, repository.bookmarks.value.first().type)
        assertEquals(
            listOf(
                BookmarkListMessage.SavingFiles(3),
                BookmarkListMessage.FilesSaved(firstId = 1L, savedCount = 2, failedCount = 1)
            ),
            messages
        )
    }

    @Test
    fun 一件のファイルは入力したタイトルで保存しすべて失敗したら理由を知らせる() = runTest {
        val viewModel = createViewModel()
        val messages = start(viewModel)

        viewModel.save(NewBookmarkInput.Files(listOf(SelectedFile("content://p/x.jpg", "image/jpeg", "x.jpg", 1L)), "旅の写真"))
        attachments.failures["content://p/y.jpg"] = AttachmentError.TooLarge(1024)
        viewModel.save(NewBookmarkInput.Files(listOf(SelectedFile("content://p/y.jpg", "image/jpeg", "y.jpg", 1L))))
        advanceUntilIdle()

        assertEquals("旅の写真", repository.bookmarks.value.single().title)
        assertEquals(BookmarkListMessage.SaveFailed(AttachmentError.TooLarge(1024).userMessage), messages.last())
    }


    @Test
    fun タグを複数選ぶと_すべて含むは両方を持つものだけ_どれかを含むは和集合になる() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1).copy(tags = listOf("10", "11")),
            testBookmark(2).copy(tags = listOf("10")),
            testBookmark(3).copy(tags = listOf("11")),
            testBookmark(4)
        )
        tagRepository.tags.value = listOf(TagWithCount(10, "a", 2), TagWithCount(11, "b", 2))
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()

        viewModel.onTagToggled(10L)
        viewModel.onTagToggled(11L)
        advanceUntilIdle()
        assertEquals(setOf(10L, 11L), viewModel.uiState.value.selectedTagIds)
        assertEquals(TagMatch.ALL, viewModel.uiState.value.tagMatch)
        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })

        viewModel.onTagMatchChange(TagMatch.ANY)
        advanceUntilIdle()
        assertEquals(TagMatch.ANY, viewModel.uiState.value.tagMatch)
        assertEquals(listOf(3L, 2L, 1L), viewModel.uiState.value.bookmarks.map { it.id })

        // もう一度選ぶと外れる
        viewModel.onTagToggled(10L)
        advanceUntilIdle()
        assertEquals(setOf(11L), viewModel.uiState.value.selectedTagIds)
        assertEquals(listOf(3L, 1L), viewModel.uiState.value.bookmarks.map { it.id })
    }

    @Test
    fun カテゴリは件数のあるものだけ並べ_選んだものは件数が無くても先頭に残る() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1).copy(category = "技術"),
            testBookmark(2).copy(category = "旅行"),
            testBookmark(3)
        )
        categories.entries.value = listOf(
            CategoryEntry("旅行", "出かける計画", CategoryKind.USER, 1, id = 1),
            CategoryEntry("技術", "技術の話題", CategoryKind.BUILT_IN, 1),
            CategoryEntry("ニュース", "時事", CategoryKind.BUILT_IN, 0)
        )
        val viewModel = createViewModel()
        start(viewModel)
        advanceUntilIdle()
        assertEquals(listOf("旅行", "技術"), viewModel.uiState.value.allCategories)

        viewModel.onCategoryToggled("技術")
        viewModel.onCategoryToggled("ニュース")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf("技術", "ニュース"), state.selectedCategories)
        // どれかのカテゴリ(技術)に当てはまるもの。選んだカテゴリは先頭へ。件数 0 でも選択中は残る
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
        assertEquals(setOf("技術", "ニュース"), state.allCategories.take(2).toSet())
        assertTrue("ニュース" in state.allCategories)
    }

    @Test
    fun すべて解除はタグとカテゴリと組み合わせ方を外し_お気に入りや検索は変えない() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1, title = "apple").copy(tags = listOf("10", "11"), category = "技術", isFavorite = true),
            testBookmark(2, title = "apple pie").copy(isFavorite = true),
            testBookmark(3).copy(tags = listOf("10"))
        )
        tagRepository.tags.value = listOf(TagWithCount(10, "a", 2), TagWithCount(11, "b", 1))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onFilterChange(BookmarkFilter.FAVORITES)
        viewModel.onSearchQueryChange("apple")
        viewModel.onTagToggled(10L)
        viewModel.onTagToggled(11L)
        viewModel.onTagMatchChange(TagMatch.ANY)
        viewModel.onCategoryToggled("技術")
        advanceUntilIdle()
        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })
        assertTrue(viewModel.uiState.value.isNarrowed)

        viewModel.onClearFilters()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(emptySet<Long>(), state.selectedTagIds)
        assertEquals(emptySet<String>(), state.selectedCategories)
        assertEquals(TagMatch.ALL, state.tagMatch)
        assertFalse(state.isNarrowed)
        // お気に入りと検索語はそのまま: お気に入りで「apple」を含む 1 と 2
        assertEquals(BookmarkFilter.FAVORITES, state.filter)
        assertTrue(state.isSearchActive)
        assertEquals(listOf(2L, 1L), state.bookmarks.map { it.id }.sortedDescending().let { it })
    }

    @Test
    fun 検索は絞り込みの条件を知らず_一覧の絞り込みとの積で結果を出す() = runTest {
        repository.bookmarks.value = listOf(
            testBookmark(1, title = "apple").copy(tags = listOf("10")),
            testBookmark(2, title = "apple pie")
        )
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onTagToggled(10L)
        viewModel.onSearchQueryChange("apple")
        advanceUntilIdle()

        // 検索には検索語だけを渡し、絞り込み(タグ)に合うものだけを出す
        assertEquals(listOf("apple"), repository.searchCalls.distinct())
        assertEquals(listOf(1L), viewModel.uiState.value.bookmarks.map { it.id })
    }

    @Test
    fun 削除されたタグは選択から外して見せる() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(tags = listOf("10")), testBookmark(2))
        tagRepository.tags.value = listOf(TagWithCount(10, "a", 1))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onTagToggled(10L)
        advanceUntilIdle()
        assertEquals(setOf(10L), viewModel.uiState.value.selectedTagIds)

        tagRepository.tags.value = emptyList()
        advanceUntilIdle()

        assertEquals(emptySet<Long>(), viewModel.uiState.value.selectedTagIds)
        assertFalse(viewModel.uiState.value.isNarrowed)
    }

    @Test
    fun タグ管理から開いたタグは今のタグとカテゴリの条件を置き換える() = runTest {
        repository.bookmarks.value = listOf(testBookmark(1).copy(tags = listOf("10")), testBookmark(2).copy(tags = listOf("11")))
        tagRepository.tags.value = listOf(TagWithCount(10, "a", 1), TagWithCount(11, "b", 1))
        val viewModel = createViewModel()
        start(viewModel)
        viewModel.onTagToggled(11L)
        viewModel.onCategoryToggled("技術")
        viewModel.onTagMatchChange(TagMatch.ANY)
        advanceUntilIdle()

        viewModel.showTag(10L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf(10L), state.selectedTagIds)
        assertEquals(emptySet<String>(), state.selectedCategories)
        assertEquals(TagMatch.ALL, state.tagMatch)
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
    }
}
