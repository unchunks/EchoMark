package com.unchunks.echomark.data.discover

import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ResourceSearchClientsTest {

    private lateinit var server: MockWebServer
    private val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("discover/$name")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun ok(body: String, contentType: String) =
        MockResponse.Builder().addHeader("Content-Type", contentType).body(body).build()

    private fun assertFailsWithIOException(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("IOException が投げられるはず")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 500"))
        }
    }

    @Test
    fun はてなには検索語をパスに入れてRSSの条件を付けて取りに行く() = runBlocking {
        server.enqueue(ok(fixture("hatena.xml"), "application/rss+xml; charset=utf-8"))
        val client = HatenaBookmarkSearchClient(OkHttpClient(), dispatchers, server.url("/q/"))

        val result = client.search("Kotlin コルーチン/入門", limit = 2)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/q/Kotlin%20%E3%82%B3%E3%83%AB%E3%83%BC%E3%83%81%E3%83%B3%2F%E5%85%A5%E9%96%80", request.url.encodedPath)
        assertEquals("rss", request.url.queryParameter("mode"))
        assertEquals("text", request.url.queryParameter("target"))
        assertEquals("popular", request.url.queryParameter("sort"))
        assertEquals("3", request.url.queryParameter("users"))
        assertTrue(request.headers["User-Agent"]!!.contains("EchoMark"))
        assertEquals(2, result.size)
        assertEquals(ResourceSource.ARTICLE, client.source)
    }

    @Test
    fun はてなのHTTPエラーは例外になる() {
        server.enqueue(MockResponse.Builder().code(500).build())
        val client = HatenaBookmarkSearchClient(OkHttpClient(), dispatchers, server.url("/q/"))

        assertFailsWithIOException { client.search("Kotlin", 5) }
    }

    @Test
    fun Wikipediaには検索APIのパラメータを付けて取りに行く() = runBlocking {
        server.enqueue(ok(fixture("wikipedia.json"), "application/json; charset=utf-8"))
        val client = WikipediaSearchClient(OkHttpClient(), dispatchers, server.url("/w/api.php"))

        val result = client.search("コルーチン", limit = 5)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/w/api.php", request.url.encodedPath)
        assertEquals("query", request.url.queryParameter("action"))
        assertEquals("search", request.url.queryParameter("list"))
        assertEquals("コルーチン", request.url.queryParameter("srsearch"))
        assertEquals("json", request.url.queryParameter("format"))
        assertEquals("5", request.url.queryParameter("srlimit"))
        assertEquals("1", request.url.queryParameter("utf8"))
        assertEquals("snippet|timestamp", request.url.queryParameter("srprop"))
        assertEquals(2, result.size)
        assertEquals(ResourceSource.ENCYCLOPEDIA, client.source)
    }

    @Test
    fun WikipediaのHTTPエラーは例外になる() {
        server.enqueue(MockResponse.Builder().code(500).build())
        val client = WikipediaSearchClient(OkHttpClient(), dispatchers, server.url("/w/api.php"))

        assertFailsWithIOException { client.search("Kotlin", 5) }
    }

    @Test
    fun YouTubeにはJSONの本文をPOSTで送る() = runBlocking {
        server.enqueue(ok(fixture("youtube_search.json"), "application/json; charset=utf-8"))
        val client = YouTubeSearchClient(OkHttpClient(), dispatchers, server.url("/youtubei/v1/search"))

        val result = client.search("Kotlin コルーチン", limit = 2)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/youtubei/v1/search", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val body = JSONObject(request.body!!.utf8())
        assertEquals("Kotlin コルーチン", body.getString("query"))
        val context = body.getJSONObject("context").getJSONObject("client")
        assertEquals("WEB", context.getString("clientName"))
        assertEquals("ja", context.getString("hl"))
        assertEquals("JP", context.getString("gl"))
        assertEquals(2, result.size)
        assertEquals(ResourceSource.VIDEO, client.source)
    }

    @Test
    fun YouTubeのHTTPエラーは例外になる() {
        server.enqueue(MockResponse.Builder().code(500).build())
        val client = YouTubeSearchClient(OkHttpClient(), dispatchers, server.url("/youtubei/v1/search"))

        assertFailsWithIOException { client.search("Kotlin", 5) }
    }

    @Test
    fun arXivには単語ごとのallクエリで取りに行く() = runBlocking {
        server.enqueue(ok(fixture("arxiv.xml"), "application/atom+xml; charset=utf-8"))
        val client = ArxivSearchClient(OkHttpClient(), dispatchers, server.url("/api/query"))

        val result = client.search("kotlin  coroutine", limit = 2)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/query", request.url.encodedPath)
        assertEquals("all:kotlin AND all:coroutine", request.url.queryParameter("search_query"))
        assertEquals("0", request.url.queryParameter("start"))
        assertEquals("2", request.url.queryParameter("max_results"))
        assertEquals("relevance", request.url.queryParameter("sortBy"))
        assertEquals(2, result.size)
        assertEquals(ResourceSource.PAPER, client.source)
    }

    @Test
    fun arXivのHTTPエラーは例外になる() {
        server.enqueue(MockResponse.Builder().code(500).build())
        val client = ArxivSearchClient(OkHttpClient(), dispatchers, server.url("/api/query"))

        assertFailsWithIOException { client.search("Kotlin", 5) }
    }

    @Test
    fun 上限を超える大きな応答は例外になる() {
        // 3MB を超える応答は、途中で切れた JSON・XML として読まずに捨てる
        server.enqueue(ok("x".repeat(SearchSupport.MAX_RESPONSE_BYTES + 1), "application/json"))
        val client = WikipediaSearchClient(OkHttpClient(), dispatchers, server.url("/w/api.php"))

        try {
            runBlocking { client.search("Kotlin", 5) }
            fail("IOException が投げられるはず")
        } catch (e: IOException) {
            assertEquals("response too large", e.message)
        }
    }
}
