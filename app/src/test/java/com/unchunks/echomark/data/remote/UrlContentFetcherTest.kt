package com.unchunks.echomark.data.remote

import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // テストのサーバーはローカル(127.0.0.1)にあるため、アドレスの制限を外して内容の処理を確かめる
        fetcher = UrlContentFetcher(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined)) { true }
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

    @Test
    fun リダイレクトを追ってページを取得する() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/moved").build())
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><head><title>移動先</title></head><body><article>本文</article></body></html>")
                .build()
        )

        val content = fetcher.fetch(server.url("/old").toString()).getOrThrow()

        assertEquals("移動先", content.title)
        assertEquals("/moved", server.takeRequest().let { server.takeRequest() }.url.encodedPath)
    }

    @Test
    fun リダイレクトが続きすぎるとfailureになる() = runBlocking {
        repeat(10) { server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/loop").build()) }

        val result = fetcher.fetch(server.url("/loop").toString())

        assertTrue(result.isFailure)
    }

    @Test
    fun ローカルネットワークのアドレスには接続しない() = runBlocking {
        val restricted = UrlContentFetcher(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html></html>").build())

        // ホスト名(DNS で解決する)と IP アドレスの直接指定の両方
        listOf("http://localhost:${server.port}/", "http://127.0.0.1:${server.port}/", server.url("/").toString())
            .forEach { url -> assertTrue(url, restricted.fetch(url).isFailure) }
        assertEquals(0, server.requestCount)
    }

    // ---- YouTube の字幕 ----

    private fun youtubeFetcher() = UrlContentFetcher(
        OkHttpClient(),
        TestDispatcherProvider(Dispatchers.Unconfined),
        isAllowedAddress = { true },
        youtubePlayerEndpoint = server.url("/youtubei/v1/player")
    )

    private val youtubePage = """
        <html><head>
          <meta property="og:site_name" content="YouTube">
          <meta property="og:title" content="サンプル動画">
          <meta property="og:description" content="切り詰められた説明">
        </head><body>
          <script>var ytInitialPlayerResponse = {"videoDetails":{"videoId":"abc123XYZ","title":"サンプル動画","author":"チャンネル名","shortDescription":"説明文","keywords":[]}};</script>
        </body></html>
    """.trimIndent()

    private fun html(body: String) =
        MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

    private fun json(body: String) =
        MockResponse.Builder().addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

    private fun playerResponse(baseUrl: String, kind: String? = null): MockResponse {
        val track = JSONObject().put("baseUrl", baseUrl).put("languageCode", "ja")
        if (kind != null) track.put("kind", kind)
        return json("""{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[$track]}}}""")
    }

    @Test
    fun YouTubeは字幕を取得して説明文のあとに入れる() = runBlocking {
        server.enqueue(html(youtubePage))
        server.enqueue(playerResponse("/api/timedtext?v=abc123XYZ&lang=ja", kind = "asr"))
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/xml; charset=UTF-8")
                .body("""<timedtext format="3"><body><p t="0" d="1">今日は字幕の話です</p><p t="1" d="1">まとめ</p></body></timedtext>""")
                .build()
        )

        val content = youtubeFetcher().fetch(server.url("/watch?v=abc123XYZ").toString()).getOrThrow()

        assertEquals("チャンネル: チャンネル名\n\n説明文\n\n字幕(自動生成):\n今日は字幕の話です\nまとめ", content.text)
        assertNull(content.description)

        server.takeRequest()
        val player = server.takeRequest()
        assertEquals("POST", player.method)
        assertEquals("/youtubei/v1/player", player.url.encodedPath)
        val body = JSONObject(player.body!!.utf8())
        assertEquals("abc123XYZ", body.getString("videoId"))
        assertEquals("ANDROID", body.getJSONObject("context").getJSONObject("client").getString("clientName"))
        assertEquals("/api/timedtext", server.takeRequest().url.encodedPath)
    }

    @Test
    fun 字幕を取得できなくても説明文だけで成功にする() = runBlocking {
        server.enqueue(html(youtubePage))
        server.enqueue(MockResponse.Builder().code(500).build())

        val content = youtubeFetcher().fetch(server.url("/watch?v=abc123XYZ").toString()).getOrThrow()

        assertEquals("チャンネル: チャンネル名\n\n説明文", content.text)
    }

    @Test
    fun 字幕が無い動画は説明文だけ() = runBlocking {
        server.enqueue(html(youtubePage))
        server.enqueue(json("""{"playabilityStatus":{"status":"OK"}}"""))

        val content = youtubeFetcher().fetch(server.url("/watch?v=abc123XYZ").toString()).getOrThrow()

        assertEquals("チャンネル: チャンネル名\n\n説明文", content.text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun 字幕が空で返ってきたら説明文だけ() = runBlocking {
        server.enqueue(html(youtubePage))
        server.enqueue(playerResponse("/api/timedtext?v=abc123XYZ&lang=ja"))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("").build())

        val content = youtubeFetcher().fetch(server.url("/watch?v=abc123XYZ").toString()).getOrThrow()

        assertEquals("チャンネル: チャンネル名\n\n説明文", content.text)
    }

    @Test
    fun 字幕のURLがYouTube以外を指していたら取得しない() = runBlocking {
        server.enqueue(html(youtubePage))
        server.enqueue(playerResponse("https://evil.example.com/api/timedtext?v=abc123XYZ"))

        val content = youtubeFetcher().fetch(server.url("/watch?v=abc123XYZ").toString()).getOrThrow()

        assertEquals("チャンネル: チャンネル名\n\n説明文", content.text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun YouTube以外のページでは字幕を取りにいかない() = runBlocking {
        server.enqueue(html("<html><head><title>ブログ</title></head><body><article>本文</article></body></html>"))

        val content = youtubeFetcher().fetch(server.url("/post").toString()).getOrThrow()

        assertEquals("本文", content.text)
        assertEquals(1, server.requestCount)
    }
}
