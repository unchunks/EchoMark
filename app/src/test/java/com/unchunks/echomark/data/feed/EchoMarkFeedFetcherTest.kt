package com.unchunks.echomark.data.feed

import com.unchunks.components.feed.FeedFetchException
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.di.NetworkModule
import com.unchunks.echomark.testing.FakeNetworkGate
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

/** EchoMark のフィードの取得(名乗る名前とオフラインモードの文言)。取得の決まりそのものは部品の feed の FeedFetcherTest で確かめる。 */
class EchoMarkFeedFetcherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun EchoMarkの名前を名乗る() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("<rss/>").build())

        echoMarkFeedFetcher(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined)) { true }.fetch(server.url("/feed.xml").toString())

        assertEquals("Mozilla/5.0 (Android) EchoMark feed reader", server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun オフラインモードなら設定の場所を添えて知らせる() {
        val offline = NetworkModule.baseOkHttpClient(FakeNetworkGate(offline = true))
        val fetcher = echoMarkFeedFetcher(offline, TestDispatcherProvider(Dispatchers.Unconfined)) { true }

        val error = runCatching { runBlocking { fetcher.fetch(server.url("/feed.xml").toString()) } }.exceptionOrNull()

        assertTrue(error is FeedFetchException)
        assertEquals("オフラインモードのため取得しませんでした。設定の「オフラインモード」をオフにしてください", error!!.message)
        assertEquals(0, server.requestCount)
    }
}
