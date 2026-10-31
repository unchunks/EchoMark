package com.unchunks.echomark.ui.snapshot

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.snapshot.PageSnapshotRepository
import com.unchunks.echomark.domain.snapshot.StoredSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PageSnapshotViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakePageSnapshotRepository()
    private val link = Bookmark(
        id = 7, type = BookmarkType.URL, contentUri = "https://example.com/a", title = "記事", createdAt = 1, lastAccessedAt = 1
    )

    private fun tempFile(name: String) = File.createTempFile(name, ".tmp").also { it.deleteOnExit() }

    @Test
    fun リンクのブックマークで始めると読み込み中になる() {
        val viewModel = PageSnapshotViewModel(repository)

        viewModel.start(link)

        assertEquals(SnapshotOperation.Capturing(7, "https://example.com/a"), viewModel.operation.value)
    }

    @Test
    fun リンクでないブックマークでは始めない() {
        val viewModel = PageSnapshotViewModel(repository)

        viewModel.start(link.copy(type = BookmarkType.TEXT, contentUri = null))
        assertEquals(SnapshotOperation.Idle, viewModel.operation.value)

        viewModel.start(link.copy(contentUri = "file:///data/data/x"))
        assertEquals(SnapshotOperation.Idle, viewModel.operation.value)
    }

    @Test
    fun 作っている最中に重ねて始めない() {
        val viewModel = PageSnapshotViewModel(repository)

        viewModel.start(link)
        viewModel.start(link.copy(id = 8, contentUri = "https://example.com/b"))

        assertEquals(SnapshotOperation.Capturing(7, "https://example.com/a"), viewModel.operation.value)
    }

    @Test
    fun できたコピーを保存して知らせる() = runTest {
        val viewModel = PageSnapshotViewModel(repository)
        val messages = mutableListOf<SnapshotMessage>()
        backgroundScope.launch { viewModel.messages.toList(messages) }
        val mhtml = tempFile("snap-a")
        val pdf = tempFile("snap-b")

        viewModel.start(link)
        viewModel.onCaptured(mhtml, pdf)
        runCurrent()

        assertEquals(listOf(Triple(7L, mhtml, pdf)), repository.saved)
        assertEquals(listOf<SnapshotMessage>(SnapshotMessage.Saved(hasPdf = true)), messages)
        assertEquals(SnapshotOperation.Idle, viewModel.operation.value)
    }

    @Test
    fun PDFが無いコピーはPDFなしとして知らせる() = runTest {
        val viewModel = PageSnapshotViewModel(repository)
        val messages = mutableListOf<SnapshotMessage>()
        backgroundScope.launch { viewModel.messages.toList(messages) }

        viewModel.start(link)
        viewModel.onCaptured(tempFile("snap-a"), null)
        runCurrent()

        assertEquals(listOf<SnapshotMessage>(SnapshotMessage.Saved(hasPdf = false)), messages)
    }

    @Test
    fun 保存に失敗したら一時ファイルを消して失敗を知らせる() = runTest {
        repository.failure = IllegalStateException("容量不足")
        val viewModel = PageSnapshotViewModel(repository)
        val messages = mutableListOf<SnapshotMessage>()
        backgroundScope.launch { viewModel.messages.toList(messages) }
        val mhtml = tempFile("snap-a")
        val pdf = tempFile("snap-b")

        viewModel.start(link)
        viewModel.onCaptured(mhtml, pdf)
        runCurrent()

        assertTrue(messages.single() is SnapshotMessage.Failed)
        assertFalse(mhtml.exists())
        assertFalse(pdf.exists())
        assertEquals(SnapshotOperation.Idle, viewModel.operation.value)
    }

    @Test
    fun 作っていないときに届いた一時ファイルは保存せず消す() = runTest {
        val viewModel = PageSnapshotViewModel(repository)
        val mhtml = tempFile("snap-a")

        viewModel.onCaptured(mhtml, null)
        runCurrent()

        assertTrue(repository.saved.isEmpty())
        assertFalse(mhtml.exists())
    }

    @Test
    fun 読み込めなかったら理由を知らせて元に戻る() = runTest {
        val viewModel = PageSnapshotViewModel(repository)
        val messages = mutableListOf<SnapshotMessage>()
        backgroundScope.launch { viewModel.messages.toList(messages) }

        viewModel.start(link)
        viewModel.onFailed("ページを読み込めませんでした")
        runCurrent()

        assertEquals(listOf<SnapshotMessage>(SnapshotMessage.Failed("ページを読み込めませんでした")), messages)
        assertEquals(SnapshotOperation.Idle, viewModel.operation.value)
    }

    @Test
    fun やめたら元に戻る() {
        val viewModel = PageSnapshotViewModel(repository)

        viewModel.start(link)
        viewModel.cancel()

        assertEquals(SnapshotOperation.Idle, viewModel.operation.value)
    }

    @Test
    fun コピーを消すと知らせる() = runTest {
        val viewModel = PageSnapshotViewModel(repository)
        val messages = mutableListOf<SnapshotMessage>()
        backgroundScope.launch { viewModel.messages.toList(messages) }

        viewModel.remove(7)
        runCurrent()

        assertEquals(listOf(7L), repository.removed)
        assertEquals(listOf<SnapshotMessage>(SnapshotMessage.Removed), messages)
    }
}

private class FakePageSnapshotRepository : PageSnapshotRepository {
    val saved = mutableListOf<Triple<Long, File, File?>>()
    val removed = mutableListOf<Long>()
    var failure: Exception? = null

    override suspend fun save(bookmarkId: Long, mhtml: File, pdf: File?): StoredSnapshot {
        failure?.let { throw it }
        saved += Triple(bookmarkId, mhtml, pdf)
        return StoredSnapshot("attachments/x.mhtml", pdf?.let { "attachments/x.pdf" }, savedAt = 1L)
    }

    override suspend fun remove(bookmarkId: Long) {
        removed += bookmarkId
    }

    override fun fileOf(path: String?): File? = null
}
