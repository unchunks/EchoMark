package com.unchunks.echomark.data.remote

import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UrlContentFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: UrlContentFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = UrlContentFetcher(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun HTMLを取得してタイトルと本文を抽出する() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><head><title>テスト</title></head><body><article>記事本文</article></body></html>")
                .build()
        )

        val result = fetcher.fetch(server.url("/page").toString())

        val content = result.getOrThrow()
        assertEquals("テスト", content.title)
        assertEquals("記事本文", content.text)
        assertTrue(server.takeRequest().headers["User-Agent"]!!.contains("EchoMark"))
    }

    @Test
    fun HTTPエラーはfailureになる() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())

        val result = fetcher.fetch(server.url("/missing").toString())

        assertTrue(result.isFailure)
    }

    @Test
    fun HTML以外のContentTypeはfailureになる() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build()
        )

        val result = fetcher.fetch(server.url("/api").toString())

        assertTrue(result.isFailure)
    }

    @Test
    fun httpでもhttpsでもないスキームはfailureになる() = runBlocking {
        val result = fetcher.fetch("ftp://example.com/file")

        assertTrue(result.isFailure)
    }
}
