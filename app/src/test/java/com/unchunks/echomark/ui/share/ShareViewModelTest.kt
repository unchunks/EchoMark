package com.unchunks.echomark.ui.share

import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeBookmarkRepository()
    private val link = SharedContent(url = "https://example.com/a", text = "https://example.com/a", tentativeTitle = "https://example.com/a")

    @Test
    fun URLを保存してタグを付け保存済みになる() = runTest {
        val viewModel = ShareViewModel(repository)

        viewModel.save(link, title = "", memo = "あとで", tagsInput = "#Android, あとで読む  Android")
        advanceUntilIdle()

        assertEquals(ShareSaveStatus.Saved(isDuplicate = false), viewModel.status.value)
        assertEquals(Triple("https://example.com/a", null, "あとで"), repository.savedUrls.single())
        assertEquals(listOf("Android", "あとで読む"), repository.bookmarks.value.single().tags)
    }

    @Test
    fun 同じURLは重複として知らせる() = runTest {
        repository.saveUrlBookmark("https://example.com/a", null, null)
        val viewModel = ShareViewModel(repository)

        viewModel.save(link, title = "", memo = "", tagsInput = "")
        advanceUntilIdle()

        assertEquals(ShareSaveStatus.Saved(isDuplicate = true), viewModel.status.value)
    }

    @Test
    fun URLが無ければテキストとしてメモを追記して保存する() = runTest {
        val viewModel = ShareViewModel(repository)
        val text = SharedContent(url = null, text = "共有された文章", tentativeTitle = "共有された文章")

        viewModel.save(text, title = "", memo = "感想", tagsInput = "")
        advanceUntilIdle()

        val saved = repository.bookmarks.value.single()
        assertEquals(BookmarkType.TEXT, saved.type)
        assertEquals("共有された文章", saved.title)
        assertEquals("共有された文章\n\n感想", saved.content)
    }

    @Test
    fun 保存済みのあとに押しても二重に保存しない() = runTest {
        val viewModel = ShareViewModel(repository)

        viewModel.save(link, "", "", "")
        viewModel.save(link, "", "", "")
        advanceUntilIdle()

        assertEquals(1, repository.savedUrls.size)
    }

    @Test
    fun タグ入力はカンマ読点空白で区切り先頭のシャープを除く() {
        assertEquals(listOf("a", "b", "c", "d"), ShareViewModel.parseTags(" #a, b、c　d ,, a"))
        assertEquals(emptyList<String>(), ShareViewModel.parseTags("   "))
    }
}
