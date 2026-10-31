package com.unchunks.echomark.ui.importer

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.importer.ImportFormat
import com.unchunks.echomark.domain.importer.ImportParseResult
import com.unchunks.echomark.domain.importer.ImportedLink
import com.unchunks.echomark.domain.importer.LinkImportException
import com.unchunks.echomark.domain.importer.LinkImportSource
import com.unchunks.echomark.testing.FakeBookmarkRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkImportViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeBookmarkRepository()
    private val source = FakeLinkImportSource()

    private val withFolder = ImportParseResult(
        ImportFormat.HTML,
        listOf(
            ImportedLink("https://example.com/a", "A", folder = "読み物"),
            ImportedLink("https://example.com/b", "B", tags = listOf("x"))
        )
    )

    private fun createViewModel() = LinkImportViewModel(source, repository)

    @Test
    fun ファイルを読むと確認に進み_フォルダがあればタグにする選択を最初から入れる() = runTest {
        source.result = withFolder
        val viewModel = createViewModel()

        viewModel.onFilePicked("content://x/export.html")
        runCurrent()

        val preview = viewModel.state.value as LinkImportState.Preview
        assertEquals(2, preview.parsed.links.size)
        assertTrue(preview.folderAsTag)
        assertEquals(listOf("content://x/export.html"), source.read)
    }

    @Test
    fun フォルダが無ければタグにする選択は入れない() = runTest {
        source.result = withFolder.copy(links = withFolder.links.map { it.copy(folder = null) })
        val viewModel = createViewModel()

        viewModel.onFilePicked("content://x/export.csv")
        runCurrent()

        assertFalse((viewModel.state.value as LinkImportState.Preview).folderAsTag)
    }

    @Test
    fun 読めないファイルは理由つきの失敗にする() = runTest {
        source.failure = LinkImportException("このファイルの形式には対応していません")
        val viewModel = createViewModel()

        viewModel.onFilePicked("content://x/notes.txt")
        runCurrent()

        assertEquals(LinkImportState.Failed("このファイルの形式には対応していません"), viewModel.state.value)
    }

    @Test
    fun 取り込めるリンクが無ければ失敗にする() = runTest {
        source.result = ImportParseResult(ImportFormat.CSV, emptyList(), invalidCount = 3)
        val viewModel = createViewModel()

        viewModel.onFilePicked("content://x/empty.csv")
        runCurrent()

        assertTrue(viewModel.state.value is LinkImportState.Failed)
    }

    @Test
    fun 予想外の失敗も一般的な失敗にして止まらない() = runTest {
        source.unexpected = IllegalStateException("boom")
        val viewModel = createViewModel()

        viewModel.onFilePicked("content://x/a.html")
        runCurrent()

        assertTrue(viewModel.state.value is LinkImportState.Failed)
    }

    @Test
    fun 取り込むを押すと選んだ内容で保存して結果を出す() = runTest {
        source.result = withFolder
        val viewModel = createViewModel()
        viewModel.onFilePicked("content://x/export.html")
        runCurrent()

        viewModel.setFolderAsTag(false)
        viewModel.startImport()
        runCurrent()

        val done = viewModel.state.value as LinkImportState.Done
        assertEquals(2, done.result.added)
        assertEquals(false, repository.importedFolderAsTag)
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), repository.importedLinks.map { it.url })
    }

    @Test
    fun 確認の前や保存中は取り込みを始め直さない() = runTest {
        val viewModel = createViewModel()

        viewModel.startImport()
        assertEquals(LinkImportState.Idle, viewModel.state.value)
        viewModel.setFolderAsTag(true)
        assertEquals(LinkImportState.Idle, viewModel.state.value)
    }

    @Test
    fun 閉じると元に戻る() = runTest {
        source.result = withFolder
        val viewModel = createViewModel()
        viewModel.onFilePicked("content://x/export.html")
        runCurrent()

        viewModel.dismiss()

        assertEquals(LinkImportState.Idle, viewModel.state.value)
    }

}

private class FakeLinkImportSource : LinkImportSource {
    var result: ImportParseResult? = null
    var failure: LinkImportException? = null
    var unexpected: Exception? = null
    val read = mutableListOf<String>()

    override suspend fun read(uri: String): ImportParseResult {
        read += uri
        failure?.let { throw it }
        unexpected?.let { throw it }
        return checkNotNull(result)
    }
}
