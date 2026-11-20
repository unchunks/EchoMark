package com.unchunks.echomark.data.websearch

import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.components.websearch.SearchUserAgents
import com.unchunks.echomark.data.network.OfflineModeException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Brave Search API のクライアント。本物の API には接続せず、ローカルのサーバーで確かめる。 */
class BraveSearchClientTest {

    private lateinit var server: MockWebServer
    private val gate = FakeNetworkGate()
    private val keys = FakeApiKeyRepository().apply { webSearchKey.value = "test-key" }
    private var now = 0L
    private val limiter = WebSearchRateLimiter(minIntervalMillis = 0, maxWaitMillis = 5_000, clock = { now })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = BraveSearchClient(
        NetworkModule.baseOkHttpClient(gate),
        keys,
        TestDispatcherProvider(Dispatchers.Unconfined),
        SearchUserAgents(app = "EchoMark/test"),
        limiter,
        server.url("/res/v1/web/search")
    )

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body)
        .build()

    private val twoResults = """
        {"type":"search","web":{"type":"search","results":[
          {"title":"Kotlin <strong>コルーチン</strong>入門","url":"https://www.example.com/a","description":"非同期 &amp; 並行の<strong>説明</strong>"},
          {"title":"二つ目","url":"https://docs.example.org/b","description":"概要"},
          {"title":"同じURL","url":"https://www.example.com/a","description":"重複"},
          {"title":"スキームが違う","url":"ftp://example.com/c","description":"除く"},
          {"title":"","url":"https://example.net/d","description":"タイトルが無い"}
        ]}}
    """.trimIndent()

    @Test
    fun キーをヘッダーに入れて検索し_結果をプレーンテキストで返す() = runBlocking<Unit> {
        server.enqueue(json(twoResults))

        val results = client().search("Kotlin のコルーチン", 5)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("test-key", request.headers["X-Subscription-Token"])
        assertEquals("application/json", request.headers["Accept"])
        val url = request.url
        assertEquals("/res/v1/web/search", url.encodedPath)
        assertEquals("Kotlin のコルーチン", url.queryParameter("q"))
        assertEquals("5", url.queryParameter("count"))
        assertEquals("false", url.queryParameter("text_decorations"))
        // 日本語の質問は、日本のページを探す
        assertEquals("JP", url.queryParameter("country"))
        assertEquals("jp", url.queryParameter("search_lang"))
        // キーは URL に入れない
        assertFalse(url.toString().contains("test-key"))

        assertEquals(2, results.size)
        assertEquals("Kotlin コルーチン入門", results[0].title)
        assertEquals("非同期 & 並行の説明", results[0].description)
        assertEquals("example.com", results[0].domain)
        assertEquals("docs.example.org", results[1].domain)
    }

    @Test
    fun 英語の質問では国と言語を指定しない() = runBlocking<Unit> {
        server.enqueue(json(twoResults))

        client().search("kotlin coroutines", 3)

        val url = server.takeRequest().url
        assertNull(url.queryParameter("country"))
        assertNull(url.queryParameter("search_lang"))
    }

    @Test
    fun 件数は上限の20件までにする() = runBlocking<Unit> {
        server.enqueue(json(twoResults))

        client().search("q", 50)

        assertEquals("20", server.takeRequest().url.queryParameter("count"))
    }

    @Test
    fun キーが無ければ通信せずに知らせる() = runBlocking<Unit> {
        keys.webSearchKey.value = null

        assertThrows<WebSearchException.KeyMissing> { client().search("Kotlin", 3) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun 認証の失敗はキーが無効として知らせる() = runBlocking<Unit> {
        server.enqueue(json("""{"type":"ErrorResponse","error":{"code":"UNAUTHORIZED","status":401}}""", code = 401))

        assertThrows<WebSearchException.InvalidKey> { client().search("Kotlin", 3) }
    }

    @Test
    fun キーが無効のコードの422もキーが無効として知らせる() = runBlocking<Unit> {
        server.enqueue(
            json("""{"type":"ErrorResponse","error":{"id":"x","status":422,"code":"SUBSCRIPTION_TOKEN_INVALID","detail":"..."}}""", code = 422)
        )

        assertThrows<WebSearchException.InvalidKey> { client().search("Kotlin", 3) }
    }

    @Test
    fun 一秒あたりの上限を超えたら_次の検索をヘッダーの秒数まで待たせる() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("X-RateLimit-Limit", "1, 2000")
                .addHeader("X-RateLimit-Remaining", "0, 1500")
                .addHeader("X-RateLimit-Reset", "2, 1419704")
                .body("{}")
                .build()
        )
        val client = client()

        val error = assertThrows<WebSearchException.RateLimited> { client.search("Kotlin", 3) }
        assertEquals(2L, error.retryAfterSeconds)

        // 待ち時間(2秒)が上限(5秒)より短いので、次の検索はその分だけ待ってから送る
        server.enqueue(json(twoResults))
        now = 2_000
        client.search("Kotlin", 3)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun 月の上限に達したら_それを知らせる() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("X-RateLimit-Remaining", "1, 0")
                .addHeader("X-RateLimit-Reset", "1, 86400")
                .body("{}")
                .build()
        )

        assertThrows<WebSearchException.QuotaExceeded> { client().search("Kotlin", 3) }
    }

    @Test
    fun サーバーの失敗はHTTPの番号を添えて知らせる() = runBlocking<Unit> {
        server.enqueue(json("{}", code = 503))

        val error = assertThrows<WebSearchException.Failed> { client().search("Kotlin", 3) }
        assertTrue(error.message!!, error.message!!.contains("503"))
    }

    @Test
    fun 大きすぎる応答は読まずに失敗にする() = runBlocking<Unit> {
        server.enqueue(json("{\"web\":{\"results\":[]},\"pad\":\"" + "x".repeat(1024 * 1024 + 10) + "\"}"))

        assertThrows<WebSearchException.Failed> { client().search("Kotlin", 3) }
    }

    @Test
    fun 形の壊れた応答は失敗にする() = runBlocking<Unit> {
        server.enqueue(json("<html>not json</html>"))

        assertThrows<WebSearchException.Failed> { client().search("Kotlin", 3) }
    }

    @Test
    fun オフラインモードでは通信しない() = runBlocking<Unit> {
        gate.offline = true

        assertThrows<OfflineModeException> { client().search("Kotlin", 3) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun キーの確認は入力中のキーを使い_保存したキーは変えない() = runBlocking<Unit> {
        server.enqueue(json(twoResults))

        client().testKey("typed-key")

        assertEquals("typed-key", server.takeRequest().headers["X-Subscription-Token"])
        assertEquals("test-key", keys.webSearchKey.value)
    }

    @Test
    fun 空の検索語では通信しない() = runBlocking<Unit> {
        val results = client().search("   ", 3)

        assertTrue(results.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun レート制限のヘッダーを読む() {
        val parsed = BraveRateLimitHeaders.parse("0, 10", "1, 3600")
        assertFalse(parsed.monthlyExhausted)
        assertEquals(1L, parsed.secondsUntilNextRequest)
        assertTrue(BraveRateLimitHeaders.parse("1, 0", null).monthlyExhausted)
        assertNull(BraveRateLimitHeaders.parse(null, null).secondsUntilNextRequest)
        // 長すぎる待ち時間は 60 秒までにする
        assertEquals(60L, BraveRateLimitHeaders.parse(null, "999, 1").secondsUntilNextRequest)
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
