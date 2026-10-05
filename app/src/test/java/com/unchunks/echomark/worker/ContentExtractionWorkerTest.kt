package com.unchunks.echomark.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.extract.ContentExtractor
import com.unchunks.echomark.domain.extract.ExtractedContent
import com.unchunks.echomark.domain.extract.ExtractionSource
import com.unchunks.echomark.testing.FakeBookmarkRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 取り出したテキストを本文に保存するワーカー(取り出し自体はフェイク)。 */
@RunWith(AndroidJUnit4::class)
class ContentExtractionWorkerTest {

    private lateinit var context: Context
    private val repository = FakeBookmarkRepository()
    private val extractor = FakeContentExtractor()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "attachments").mkdirs()
        File(context.filesDir, "attachments/doc.pdf").writeBytes(byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        File(context.filesDir, "attachments").deleteRecursively()
    }

    private class FakeContentExtractor : ContentExtractor {
        var result: ExtractedContent? = null
        var failure: Exception? = null
        var longRunning = false
        val calls = mutableListOf<Triple<String, String?, ContentKind>>()

        override suspend fun extract(file: File, mimeType: String?, kind: ContentKind): ExtractedContent? {
            calls += Triple(file.name, mimeType, kind)
            failure?.let { throw it }
            return result
        }

        override suspend fun isLongRunning(file: File, mimeType: String?, kind: ContentKind): Boolean = longRunning
    }

    private fun pdfBookmark(
        title: String = "doc.pdf",
        content: String? = null,
        filePath: String? = "attachments/doc.pdf"
    ) = Bookmark(
        id = 1L,
        type = BookmarkType.PDF,
        title = title,
        content = content,
        createdAt = 0L,
        lastAccessedAt = 0L,
        filePath = filePath,
        mimeType = "application/pdf",
        fileName = "doc.pdf"
    )

    private fun runWorker(bookmarkId: Long = 1L): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<ContentExtractionWorker>(context)
            .setInputData(workDataOf(ContentExtractionWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = ContentExtractionWorker(appContext, workerParameters, repository, extractor)
            })
            .build()
            .doWork()
    }

    private fun saved(): Bookmark = repository.bookmarks.value.single()

    @Test
    fun 取り出したテキストをメモの後ろに保存し_ファイル名のままのタイトルを置き換える() {
        repository.bookmarks.value = listOf(pdfBookmark(content = "あとで読む"))
        extractor.result = ExtractedContent.of("本文です", ExtractionSource.PDF_TEXT, title = "年次報告書", pageCount = 3)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals("あとで読む\n\n--- PDF の本文 ---\n本文です", saved().content)
        assertEquals("年次報告書", saved().title)
        assertEquals(Triple("doc.pdf", "application/pdf", ContentKind.DOCUMENT), extractor.calls.single())
    }

    @Test
    fun 再実行しても二重に追記しない_編集したタイトルは残す() {
        repository.bookmarks.value = listOf(pdfBookmark(title = "自分で付けた題", content = "メモ"))
        extractor.result = ExtractedContent.of("本文", ExtractionSource.PDF_TEXT, title = "メタデータの題")

        runWorker()
        runWorker()

        assertEquals("メモ\n\n--- PDF の本文 ---\n本文", saved().content)
        assertEquals("自分で付けた題", saved().title)
    }

    @Test
    fun 取り出せなければ本文を変えずに成功で終える() {
        repository.bookmarks.value = listOf(pdfBookmark(content = "メモ"))
        extractor.result = null

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals("メモ", saved().content)
    }

    @Test
    fun 取り出しの例外でもワーカーは落ちない() {
        repository.bookmarks.value = listOf(pdfBookmark(content = null))
        extractor.failure = IllegalStateException("boom")

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertNull(saved().content)
    }

    @Test
    fun ファイルが無い_ファイルの無いブックマーク_見つからないときは何もしない() {
        repository.bookmarks.value = listOf(pdfBookmark(filePath = "attachments/missing.pdf"))
        extractor.result = ExtractedContent.of("本文", ExtractionSource.PDF_TEXT)

        assertEquals(ListenableWorker.Result.success(), runWorker())
        repository.bookmarks.value = listOf(pdfBookmark(filePath = null))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(ListenableWorker.Result.success(), runWorker(bookmarkId = 99L))

        assertTrue(extractor.calls.isEmpty())
        assertNull(saved().content)
    }

    @Test
    fun 長い処理はフォアグラウンドにしてから取り出す() {
        repository.bookmarks.value = listOf(pdfBookmark())
        extractor.longRunning = true
        extractor.result = ExtractedContent.of("文字起こし", ExtractionSource.TRANSCRIPT)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals("--- 文字起こし ---\n文字起こし", saved().content)
    }

    @Test
    fun タイトルの置き換えは未編集のときだけ() {
        val extracted = ExtractedContent.of("x", ExtractionSource.PDF_TEXT, title = "題")!!

        assertEquals("題", ContentExtractionWorker.titleAfterExtraction(pdfBookmark(title = ""), extracted))
        assertEquals("題", ContentExtractionWorker.titleAfterExtraction(pdfBookmark(title = "doc"), extracted))
        assertEquals("doc.pdf 改", ContentExtractionWorker.titleAfterExtraction(pdfBookmark(title = "doc.pdf 改"), extracted))
        assertEquals(
            "doc.pdf",
            ContentExtractionWorker.titleAfterExtraction(pdfBookmark(), ExtractedContent.of("x", ExtractionSource.PDF_TEXT)!!)
        )
    }
}
