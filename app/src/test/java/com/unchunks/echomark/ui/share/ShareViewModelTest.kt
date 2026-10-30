package com.unchunks.echomark.ui.share

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.model.AttachmentError
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.testing.FakeAttachmentRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.ui.common.SelectedFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeBookmarkRepository()
    private val attachments = FakeAttachmentRepository()
    private val link = SharedContent(url = "https://example.com/a", text = "https://example.com/a", tentativeTitle = "https://example.com/a")

    @Test
    fun URLを保存してタグを付け保存済みになる() = runTest {
        val viewModel = ShareViewModel(repository, attachments)

        viewModel.save(link, title = "", memo = "あとで", tagsInput = "#Android, あとで読む  Android")
        advanceUntilIdle()

        assertEquals(ShareSaveStatus.Saved(isDuplicate = false), viewModel.status.value)
        assertEquals(Triple("https://example.com/a", null, "あとで"), repository.savedUrls.single())
        assertEquals(listOf("Android", "あとで読む"), repository.bookmarks.value.single().tags)
    }

    @Test
    fun 同じURLは重複として知らせる() = runTest {
        repository.saveUrlBookmark("https://example.com/a", null, null)
        val viewModel = ShareViewModel(repository, attachments)

        viewModel.save(link, title = "", memo = "", tagsInput = "")
        advanceUntilIdle()

        assertEquals(ShareSaveStatus.Saved(isDuplicate = true), viewModel.status.value)
    }

    @Test
    fun URLが無ければテキストとしてメモを追記して保存する() = runTest {
        val viewModel = ShareViewModel(repository, attachments)
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
        val viewModel = ShareViewModel(repository, attachments)

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

    private fun fileShare(vararg uris: String, text: String = "") = SharedContent(
        url = null,
        text = text,
        tentativeTitle = "",
        files = uris.map { SelectedFile(it, "image/jpeg", it.substringAfterLast('/'), 100L) }
    )

    @Test
    fun 共有されたファイルを取り込んでブックマークにしタグを付ける() = runTest {
        attachments.files["content://p/report.pdf"] =
            StoredAttachment("attachments/r.pdf", "application/pdf", "report.pdf", 2048L)
        val viewModel = ShareViewModel(repository, attachments)

        viewModel.save(fileShare("content://p/report.pdf"), title = "", memo = "会議で使う", tagsInput = "仕事")
        advanceUntilIdle()

        assertEquals(ShareSaveStatus.Saved(isDuplicate = false, savedCount = 1, failedCount = 0), viewModel.status.value)
        val saved = repository.bookmarks.value.single()
        assertEquals(BookmarkType.PDF, saved.type)
        assertEquals("report", saved.title)
        assertEquals("会議で使う", saved.content)
        assertEquals("attachments/r.pdf", saved.filePath)
        assertEquals(listOf("仕事"), saved.tags)
    }

    @Test
    fun 一件のファイルは入力したタイトルを使う() = runTest {
        val viewModel = ShareViewModel(repository, attachments)

        viewModel.save(fileShare("content://p/IMG_0001.jpg"), title = "夕焼け", memo = "", tagsInput = "")
        advanceUntilIdle()

        assertEquals("夕焼け", repository.bookmarks.value.single().title)
    }

    @Test
    fun 複数のファイルはそれぞれ保存し保存できなかった数を知らせる() = runTest {
        attachments.failures["content://p/b.jpg"] = AttachmentError.TooLarge(200L * 1024 * 1024)
        val viewModel = ShareViewModel(repository, attachments)

        viewModel.save(fileShare("content://p/a.jpg", "content://p/b.jpg", "content://p/c.jpg"), title = "無視される", memo = "", tagsInput = "旅行")
        advanceUntilIdle()

        assertEquals(ShareSaveStatus.Saved(isDuplicate = false, savedCount = 2, failedCount = 1), viewModel.status.value)
        assertEquals(listOf("a", "c"), repository.bookmarks.value.map { it.title })
        assertTrue(repository.bookmarks.value.all { it.tags == listOf("旅行") })
    }

    @Test
    fun すべて保存できなければ理由を出して入力に戻る() = runTest {
        attachments.failures["content://p/a.jpg"] = AttachmentError.InsufficientStorage
        val viewModel = ShareViewModel(repository, attachments)

        viewModel.save(fileShare("content://p/a.jpg"), title = "", memo = "", tagsInput = "")
        advanceUntilIdle()

        assertEquals(ShareSaveStatus.Failed(AttachmentError.InsufficientStorage.userMessage), viewModel.status.value)
        assertTrue(repository.bookmarks.value.isEmpty())
    }


    @Test
    fun 入力が止まってからタグを提案し_続けて入力すると前の提案は取り消す() = runTest {
        repository.suggestions = listOf("Android", "Compose")
        val viewModel = ShareViewModel(repository, attachments)

        viewModel.updateSuggestions(link, title = "Compose", memo = "")
        viewModel.updateSuggestions(link, title = "Compose の速度", memo = "")
        assertEquals(emptyList<String>(), viewModel.suggestedTags.value)
        advanceUntilIdle()

        assertEquals(listOf("Android", "Compose"), viewModel.suggestedTags.value)
        // 取り消した方は呼ばれない。内容は、入力したタイトル・共有された URL をまとめたもの
        assertEquals(1, repository.suggestCalls.size)
        assertTrue("Compose の速度" in repository.suggestCalls.single())
        assertTrue("https://example.com/a" in repository.suggestCalls.single())
    }

    @Test
    fun 提案に失敗しても空にするだけで保存には関係しない() = runTest {
        val viewModel = ShareViewModel(repository, attachments)
        repository.suggestFailure = IllegalStateException("埋め込めない")

        viewModel.updateSuggestions(link, title = "", memo = "")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.suggestedTags.value)
        viewModel.save(link, title = "", memo = "", tagsInput = "")
        advanceUntilIdle()
        assertEquals(ShareSaveStatus.Saved(isDuplicate = false), viewModel.status.value)
    }

    @Test
    fun 提案のタグを入力欄の末尾に足す() {
        assertEquals("Android", appendTag("", "Android"))
        assertEquals("あとで読む, Android", appendTag("あとで読む", "Android"))
        assertEquals("あとで読む, Android", appendTag("あとで読む,", "Android"))
        assertEquals("あとで読む、 Android", appendTag("あとで読む、 ", "Android"))
        assertEquals("a b, Android", appendTag("a b  ", "Android"))
    }
}
