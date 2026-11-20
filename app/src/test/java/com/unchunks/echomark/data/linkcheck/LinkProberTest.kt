package com.unchunks.echomark.data.linkcheck

import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.linkcheck.LinkProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** リンク1件の確認(HEAD → GET の確かめ方・リダイレクト・ローカルネットワークへ接続しない守り)。 */
class LinkProberTest {

    private lateinit var server: MockWebServer

    /** テストのサーバーはローカル(127.0.0.1)にあるため、アドレスの制限を外して確認の動きだけを確かめる */
    private lateinit var prober: LinkProber

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prober = LinkProber(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined)) { true }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun url(path: String = "/page") = server.url(path).toString()

    private fun failed(result: LinkProbeResult): LinkProbeResult.Failed = result as LinkProbeResult.Failed

    @Test
    fun HEADで開けたら生きている_本文は取りに行かない() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())

        assertEquals(LinkProbeResult.Alive, prober.probe(url()))

        assertEquals(1, server.requestCount)
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun HEADを断るサイトはGETで確かめ直す_先頭の1バイトだけを頼む() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build()) // HEAD だけ 404 を返すサイト
        server.enqueue(MockResponse.Builder().code(200).body("x").build())

        assertEquals(LinkProbeResult.Alive, prober.probe(url()))

        assertEquals("HEAD", server.takeRequest().method)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("bytes=0-0", get.headers["Range"])
    }

    @Test
    fun HEADもGETも404なら見つからない() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(404).build())

        val result = failed(prober.probe(url()))

        assertFalse(result.networkLevel)
        assertTrue(result.reason, result.reason.contains("404"))
    }

    @Test
    fun ページが消えた応答とサイトの不具合も失敗() = runBlocking {
        server.enqueue(MockResponse.Builder().code(410).build())
        server.enqueue(MockResponse.Builder().code(410).build())
        assertTrue(prober.probe(url()) is LinkProbeResult.Failed)

        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(failed(prober.probe(url())).reason.contains("503"))
    }

    @Test
    fun ボットを断るだけの応答は生きているとみなす() = runBlocking {
        listOf(403, 429, 401).forEach { code ->
            server.enqueue(MockResponse.Builder().code(code).build()) // HEAD
            server.enqueue(MockResponse.Builder().code(code).build()) // GET
            assertEquals("HTTP $code", LinkProbeResult.Alive, prober.probe(url()))
        }
    }

    @Test
    fun リダイレクトを追って最後が開ければ生きている() = runBlocking {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/moved").build())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/final").build())
        server.enqueue(MockResponse.Builder().code(200).build())

        assertEquals(LinkProbeResult.Alive, prober.probe(url("/old")))

        assertEquals(listOf("/old", "/moved", "/final"), (1..3).map { server.takeRequest().url.encodedPath })
    }

    @Test
    fun リダイレクトの先が見つからなければ失敗() = runBlocking {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/gone").build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(404).build())

        assertTrue(failed(prober.probe(url("/old"))).reason.contains("404"))
    }

    @Test
    fun リダイレクトが多すぎるか移動先が無ければ失敗() = runBlocking {
        repeat(LinkProber.MAX_REDIRECTS + 1) {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/loop").build())
        }
        val loop = failed(prober.probe(url("/loop")))
        assertFalse(loop.networkLevel)
        assertTrue(loop.reason, loop.reason.contains("多すぎ"))

        server.enqueue(MockResponse.Builder().code(302).build())
        assertFalse(failed(prober.probe(url())).networkLevel)
    }

    @Test
    fun 別の種類のURLへのリダイレクトは追わずに失敗とする() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "ftp://example.com/file").build())

        assertTrue(prober.probe(url()) is LinkProbeResult.Failed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun 接続できなければ通信の失敗として返す() = runBlocking {
        val closedUrl = url()
        server.close()

        val result = failed(prober.probe(closedUrl))

        assertTrue(result.networkLevel)
    }

    @Test
    fun 存在しないドメインは通信の失敗として返す() = runBlocking {
        val result = failed(prober.probe("https://no-such-host.invalid/page"))

        assertTrue(result.networkLevel)
        assertEquals("サイトが見つかりません", result.reason)
    }

    @Test
    fun URLでないものとhttp以外は確認の対象にしない() = runBlocking {
        assertEquals(LinkProbeResult.NotCheckable, prober.probe("not a url"))
        assertEquals(LinkProbeResult.NotCheckable, prober.probe("ftp://example.com/file"))
        assertEquals(LinkProbeResult.NotCheckable, prober.probe("content://media/1"))
    }

    @Test
    fun ローカルネットワークのアドレスには接続せず_切れたとも言わない() = runBlocking {
        // 制限を外していない(本番と同じ)確認。127.0.0.1 のサーバーには接続しない
        val guarded = LinkProber(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined))
        server.enqueue(MockResponse.Builder().code(200).build())

        assertEquals(LinkProbeResult.NotCheckable, guarded.probe(url()))
        // 名前で指定しても同じ
        assertEquals(LinkProbeResult.NotCheckable, guarded.probe("http://localhost:${server.port}/page"))

        assertEquals(0, server.requestCount)
    }
}
