package com.unchunks.echomark.data.remote

import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** リンク先が PDF・画像・音声・動画のとき、本体をダウンロードして保存するテスト。 */
class UrlContentFetcherDownloadTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: UrlContentFetcher

    /** 保存を頼まれた内容(MIME タイプ・ファイル名・中身) */
    private val saved = mutableListOf<Triple<String, String?, ByteArray>>()
    private val sink = DownloadSink { body, mimeType, fileName, _ ->
        val bytes = body.readBytes()
        saved += Triple(mimeType, fileName, bytes)
        StoredAttachment("attachments/test.bin", mimeType, fileName ?: "file", bytes.size.toLong())
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // テストのサーバーはローカル(127.0.0.1)にあるため、アドレスの制限を外して内容の処理を確かめる
        fetcher = UrlContentFetcher(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined)) { true }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun binaryResponse(contentType: String, body: ByteArray, disposition: String? = null) =
        MockResponse.Builder()
            .addHeader("Content-Type", contentType)
            .apply { if (disposition != null) addHeader("Content-Disposition", disposition) }
            .body(okio.Buffer().write(body))
            .build()

    @Test
    fun PDFならダウンロードして保存しファイル名をタイトルにする() = runBlocking {
        val pdf = "%PDF-1.7 test".toByteArray()
        // 1回目で種類を確かめ、2回目(時間制限の長い接続)で本体を取る
        server.enqueue(binaryResponse("application/pdf", pdf))
        server.enqueue(binaryResponse("application/pdf", pdf))

        val content = fetcher.fetch(server.url("/papers/research.pdf").toString(), sink).getOrThrow()

        assertEquals("research", content.title)
        assertEquals("", content.text)
        assertEquals("application/pdf", content.file!!.mimeType)
        assertEquals("application/pdf", saved.single().first)
        assertEquals("research.pdf", saved.single().second)
        assertArrayEquals(pdf, saved.single().third)
    }

    @Test
    fun ContentDispositionのファイル名を使う() = runBlocking {
        val disposition = "attachment; filename=\"fallback.mp3\"; filename*=UTF-8''%E4%BC%9A%E8%AD%B0.mp3"
        server.enqueue(binaryResponse("audio/mpeg", byteArrayOf(1, 2, 3)))
        server.enqueue(binaryResponse("audio/mpeg", byteArrayOf(1, 2, 3), disposition))

        val content = fetcher.fetch(server.url("/download?id=1").toString(), sink).getOrThrow()

        assertEquals("会議.mp3", saved.single().second)
        assertEquals("会議", content.title)
    }

    @Test
    fun octetStreamはURLの拡張子から種類を決める() = runBlocking {
        server.enqueue(binaryResponse("application/octet-stream", byteArrayOf(5)))
        server.enqueue(binaryResponse("application/octet-stream", byteArrayOf(5)))

        val content = fetcher.fetch(server.url("/files/photo.JPG").toString(), sink).getOrThrow()

        assertEquals("image/jpeg", content.file!!.mimeType)
    }

    @Test
    fun 保存先を渡さなければHTML以外は失敗のまま() = runBlocking {
        server.enqueue(binaryResponse("application/pdf", byteArrayOf(1)))

        val result = fetcher.fetch(server.url("/a.pdf").toString())

        assertTrue(result.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun 保存できない種類は保存先があっても失敗() = runBlocking {
        server.enqueue(binaryResponse("application/zip", byteArrayOf(1)))

        val result = fetcher.fetch(server.url("/a.zip").toString(), sink)

        assertTrue(result.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun 大きすぎるファイルはダウンロードしない() = runBlocking {
        val huge = MockResponse.Builder()
            .addHeader("Content-Type", "video/mp4")
            .body(okio.Buffer().write(byteArrayOf(1)))
            // 本体を読む前に、宣言された大きさで断る
            .setHeader("Content-Length", (200L * 1024 * 1024).toString())
            .build()
        server.enqueue(binaryResponse("video/mp4", byteArrayOf(1)))
        server.enqueue(huge)

        val result = fetcher.fetch(server.url("/movie.mp4").toString(), sink)

        assertTrue(result.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun リダイレクトの先がPDFでもダウンロードする() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/real.pdf").build())
        server.enqueue(binaryResponse("application/pdf", byteArrayOf(1)))
        server.enqueue(binaryResponse("application/pdf", byteArrayOf(1)))

        val content = fetcher.fetch(server.url("/short").toString(), sink).getOrThrow()

        assertEquals("real", content.title)
        server.takeRequest()
        server.takeRequest()
        // ダウンロードし直すのはリダイレクトを追った後の URL
        assertEquals("/real.pdf", server.takeRequest().url.encodedPath)
    }

    @Test
    fun ファイル名とMIMEタイプの推定() {
        val url = "https://example.com/a/b/%E8%B3%87%E6%96%99.pdf".toHttpUrl()
        assertEquals("資料.pdf", UrlContentFetcher.fileNameOf(null, url))
        assertEquals("x.pdf", UrlContentFetcher.fileNameOf("inline; filename=\"../x.pdf\"", url))
        assertNull(UrlContentFetcher.fileNameOf(null, "https://example.com/".toHttpUrl()))
        assertEquals("application/pdf", UrlContentFetcher.fileMimeType("binary/octet-stream".toMediaType(), url))
        assertEquals("image/png", UrlContentFetcher.fileMimeType("image/png".toMediaType(), url))
    }
}
