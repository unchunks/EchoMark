package com.unchunks.echomark.data.feed

import com.unchunks.components.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** フィードの取得(安全のための制限・条件付きの取得・リダイレクト)。 */
class FeedFetcherTest {

    private lateinit var server: MockWebServer

    /** テストのサーバーはローカル(127.0.0.1)にあるため、アドレスの制限を外して取得の動きだけを確かめる */
    private lateinit var fetcher: FeedFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = FeedFetcher(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined)) { true }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun body(response: FeedResponse): FeedBody = (response as FeedResponse.Body).body

    @Test
    fun 本文とETagとLastModifiedと最終URLを返す() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/rss+xml; charset=utf-8")
                .addHeader("ETag", "\"abc\"")
                .addHeader("Last-Modified", "Tue, 03 Jun 2008 11:05:30 GMT")
                .body("<rss/>")
                .build()
        )

        val result = body(fetcher.fetch(server.url("/feed.xml").toString()))

        assertEquals("<rss/>", result.text())
        assertEquals("\"abc\"", result.etag)
        assertEquals("Tue, 03 Jun 2008 11:05:30 GMT", result.lastModified)
        assertEquals(server.url("/feed.xml").toString(), result.finalUrl)
        assertEquals("utf-8", result.charsetName?.lowercase())
    }

    @Test
    fun 前回の値があれば条件付きで取得し_変わっていなければ本文を取らない() = runBlocking {
        server.enqueue(MockResponse.Builder().code(304).build())

        val response = fetcher.fetch(server.url("/feed.xml").toString(), etag = "\"abc\"", lastModified = "Tue, 03 Jun 2008 11:05:30 GMT")

        assertTrue(response is FeedResponse.NotModified)
        val request = server.takeRequest()
        assertEquals("\"abc\"", request.headers["If-None-Match"])
        assertEquals("Tue, 03 Jun 2008 11:05:30 GMT", request.headers["If-Modified-Since"])
    }

    @Test
    fun 条件を付けない取得では条件のヘッダーを送らない() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<rss/>").build())

        fetcher.fetch(server.url("/feed.xml").toString())

        val request = server.takeRequest()
        assertNull(request.headers["If-None-Match"])
        assertNull(request.headers["If-Modified-Since"])
        assertNotNull(request.headers["User-Agent"])
    }

    @Test
    fun リダイレクトを追って最終URLを返す() = runBlocking {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/moved.xml").build())
        server.enqueue(MockResponse.Builder().body("<rss/>").build())

        val result = body(fetcher.fetch(server.url("/feed.xml").toString()))

        assertEquals(server.url("/moved.xml").toString(), result.finalUrl)
    }

    @Test
    fun リダイレクトが多すぎれば失敗する() {
        repeat(FeedFetcher.MAX_REDIRECTS + 2) {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/loop").build())
        }

        val error = runCatching { runBlocking { fetcher.fetch(server.url("/loop").toString()) } }.exceptionOrNull()

        assertTrue(error is FeedFetchException)
    }

    @Test
    fun http以外へのリダイレクトは追わない() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "file:///etc/passwd").build())

        val error = runCatching { runBlocking { fetcher.fetch(server.url("/feed.xml").toString()) } }.exceptionOrNull()

        assertTrue(error is FeedFetchException)
    }

    @Test
    fun 大きすぎる本文は失敗する_Content_Lengthがあっても無くても() {
        val big = "a".repeat(FeedFetcher.MAX_BODY_BYTES + 1)
        server.enqueue(MockResponse.Builder().body(big).build())
        server.enqueue(MockResponse.Builder().chunkedBody(big, 4096).build())

        repeat(2) {
            val error = runCatching { runBlocking { fetcher.fetch(server.url("/big.xml").toString()) } }.exceptionOrNull()
            assertTrue(error is FeedFetchException)
            assertTrue(error!!.message!!.contains("大きすぎる"))
        }
    }

    @Test
    fun 上限ちょうどの本文は読める() = runBlocking {
        server.enqueue(MockResponse.Builder().body("a".repeat(FeedFetcher.MAX_BODY_BYTES)).build())

        val result = body(fetcher.fetch(server.url("/limit.xml").toString()))

        assertEquals(FeedFetcher.MAX_BODY_BYTES, result.bytes.size)
    }

    @Test
    fun サーバーのエラーは失敗にする() {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(404).build())

        repeat(2) {
            val error = runCatching { runBlocking { fetcher.fetch(server.url("/x").toString()) } }.exceptionOrNull()
            assertTrue(error is FeedFetchException)
        }
    }

    @Test
    fun http以外のURLは取得しない() {
        val error = runCatching { runBlocking { fetcher.fetch("file:///etc/passwd") } }.exceptionOrNull()

        assertTrue(error is FeedFetchException)
    }

    @Test
    fun ローカルネットワークのアドレスには接続しない() {
        // 制限を外していない本番の設定。127.0.0.1 のサーバーには接続できない
        val production = FeedFetcher(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined))
        server.enqueue(MockResponse.Builder().body("<rss/>").build())

        val error = runCatching { runBlocking { production.fetch(server.url("/feed.xml").toString()) } }.exceptionOrNull()

        assertTrue(error is FeedFetchException)
        assertEquals(0, server.requestCount)
    }
}
