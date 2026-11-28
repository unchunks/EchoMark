package com.unchunks.echomark.data.websearch

import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.components.web.OfflineModeException
import com.unchunks.components.websearch.BraveSearchClient
import com.unchunks.components.websearch.SearchUserAgents
import com.unchunks.echomark.di.NetworkModule
import com.unchunks.echomark.domain.websearch.WebSearchException
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeNetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Web 検索の、EchoMark のキーの保存先と失敗の知らせのつなぎ。本物の API には接続せず、ローカルのサーバーで確かめる。
 * API の呼び出しそのもの(検索語・ヘッダー・応答の読み取り・回数の制限)は、部品の web-search の BraveSearchClientTest で確かめる。
 */
class BraveWebSearchClientTest {

    private lateinit var server: MockWebServer
    private val gate = FakeNetworkGate()
    private val keys = FakeApiKeyRepository().apply { webSearchKey.value = "test-key" }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = BraveWebSearchClient(
        BraveSearchClient(
            NetworkModule.baseOkHttpClient(gate),
            TestDispatcherProvider(Dispatchers.Unconfined),
            SearchUserAgents(app = "EchoMark/test"),
            server.url("/res/v1/web/search")
        ),
        keys
    )

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body)
        .build()

    @Test
    fun 保存したキーで検索し_結果を返す() = runBlocking<Unit> {
        server.enqueue(json("""{"web":{"results":[{"title":"題","url":"https://www.example.com/a","description":"概要"}]}}"""))

        val results = client().search("Kotlin のコルーチン", 5)

        assertEquals("test-key", server.takeRequest().headers["X-Subscription-Token"])
        assertEquals(1, results.size)
        assertEquals("題", results[0].title)
        assertEquals("概要", results[0].description)
        assertEquals("example.com", results[0].domain)
    }

    @Test
    fun キーが無ければ通信せずに知らせる() = runBlocking<Unit> {
        keys.webSearchKey.value = null

        assertThrows<WebSearchException.KeyMissing> { client().search("Kotlin", 3) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun 部品の失敗を画面に出す失敗にする() = runBlocking<Unit> {
        server.enqueue(json("""{"error":{"code":"UNAUTHORIZED"}}""", code = 401))
        assertThrows<WebSearchException.InvalidKey> { client().search("Kotlin", 3) }

        server.enqueue(
            MockResponse.Builder().code(429).addHeader("X-RateLimit-Remaining", "1, 0").addHeader("X-RateLimit-Reset", "1, 86400")
                .body("{}").build()
        )
        assertThrows<WebSearchException.QuotaExceeded> { client().search("Kotlin", 3) }

        server.enqueue(json("{}", code = 503))
        val failed = assertThrows<WebSearchException.Failed> { client().search("Kotlin", 3) }
        assertTrue(failed.message!!, failed.message!!.contains("503"))
    }

    @Test
    fun 一秒あたりの上限は待つ秒数を添えて知らせる() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("X-RateLimit-Remaining", "0, 1500").addHeader("X-RateLimit-Reset", "2, 1419704")
                .body("{}").build()
        )

        val error = assertThrows<WebSearchException.RateLimited> { client().search("Kotlin", 3) }
        assertEquals(2L, error.retryAfterSeconds)
    }

    @Test
    fun オフラインモードでは通信しない() = runBlocking<Unit> {
        gate.offline = true

        assertThrows<OfflineModeException> { client().search("Kotlin", 3) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun キーの確認は入力中のキーを使い_保存したキーは変えない() = runBlocking<Unit> {
        server.enqueue(json("""{"web":{"results":[]}}"""))

        client().testKey("typed-key")

        assertEquals("typed-key", server.takeRequest().headers["X-Subscription-Token"])
        assertEquals("test-key", keys.webSearchKey.value)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName} but was ${e::class.simpleName}", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }
}
