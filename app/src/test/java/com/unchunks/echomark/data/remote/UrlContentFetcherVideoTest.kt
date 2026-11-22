package com.unchunks.echomark.data.remote

import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.video.hasVideoTranscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** PeerTube の字幕(公開の API)と、動画のサムネイルの保存(ローカルのサーバーで、本物の通信はしない) */
class UrlContentFetcherVideoTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: UrlContentFetcher
    private val savedBytes = mutableListOf<ByteArray>()

    private val sink = DownloadSink { body, mimeType, fileName, _ ->
        val bytes = body.readBytes()
        savedBytes += bytes
        StoredAttachment("attachments/thumb.jpg", mimeType, fileName ?: "file", bytes.size.toLong())
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

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private fun peerTube(captions: String, captionFile: MockResponse? = null, details: String = DETAILS) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/w/$VIDEO_ID" -> MockResponse.Builder()
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .body("<html><head><meta property=\"og:title\" content=\"ページの題\"></head><body></body></html>")
                    .build()
                "/api/v1/videos/$VIDEO_ID" -> json(details)
                "/api/v1/videos/$VIDEO_ID/captions" -> json(captions)
                "/lazy-static/video-captions/ja.vtt" ->
                    captionFile ?: MockResponse.Builder().addHeader("Content-Type", "text/vtt").body(VTT).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }

    @Test
    fun PeerTubeの動画はAPIの説明と字幕を本文にする() = runBlocking {
        peerTube(
            captions = """{"total":2,"data":[
                {"language":{"id":"en","label":"English"},"automaticallyGenerated":false,"fileUrl":"/lazy-static/video-captions/en.vtt"},
                {"language":{"id":"ja","label":"Japanese"},"automaticallyGenerated":true,"captionPath":"/lazy-static/video-captions/ja.vtt"}
            ]}"""
        )

        val content = fetcher.fetch(server.url("/w/$VIDEO_ID").toString()).getOrThrow()

        assertEquals("動画の名前", content.title)
        assertNull(content.description)
        assertTrue(content.text, content.text.startsWith("チャンネル: チャンネル名\n\n動画の説明"))
        // 日本語の字幕を選ぶ(自動生成でも、言語を優先する)
        assertTrue(content.text, content.text.contains("字幕(自動生成):\nこんにちは\nPeerTube の話です"))
        assertTrue(content.text.endsWith("キーワード: 動画, テスト"))
        assertTrue(hasVideoTranscript(content.text))
        assertEquals("チャンネル: チャンネル名\n\n動画の説明\n\nキーワード: 動画, テスト", content.textWithoutTranscript)
        assertEquals(server.url("/lazy-static/thumbnails/large.jpg").toString(), content.imageUrl)
        assertEquals("PeerTube", content.siteName)
    }

    @Test
    fun PeerTubeの字幕が無ければ説明だけを本文にする() = runBlocking {
        peerTube(captions = """{"total":0,"data":[]}""")

        val content = fetcher.fetch(server.url("/w/$VIDEO_ID").toString()).getOrThrow()

        assertEquals("チャンネル: チャンネル名\n\n動画の説明\n\nキーワード: 動画, テスト", content.text)
        assertFalse(hasVideoTranscript(content.text))
        assertNull(content.textWithoutTranscript)
    }

    @Test
    fun PeerTubeの答えでなければページの内容のまま() = runBlocking {
        // 同じパスの形の、PeerTube でないサイト(API が HTML や 404 を返す)
        peerTube(captions = "{}", details = """{"message":"not found"}""")

        val content = fetcher.fetch(server.url("/w/$VIDEO_ID").toString()).getOrThrow()

        assertEquals("ページの題", content.title)
        assertFalse(content.text.contains("字幕"))
    }

    @Test
    fun サムネイルの画像を保存する() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/real.jpg").build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(JPEG)).build())

        val stored = fetcher.downloadImage(server.url("/thumb").toString(), sink).getOrThrow()

        assertEquals("image/jpeg", stored.mimeType)
        assertEquals(JPEG.toList(), savedBytes.single().toList())
        assertEquals("/thumb", server.takeRequest().url.encodedPath)
        assertEquals("/real.jpg", server.takeRequest().url.encodedPath)
    }

    @Test
    fun 画像でないものと中に処理を書ける形式は保存しない() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html></html>").build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/svg+xml").body("<svg/>").build())

        assertTrue(fetcher.downloadImage(server.url("/a").toString(), sink).isFailure)
        assertTrue(fetcher.downloadImage(server.url("/b").toString(), sink).isFailure)
        assertTrue(savedBytes.isEmpty())
    }

    @Test
    fun 大きすぎるサムネイルは保存しない() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(ByteArray(2048))).build())

        assertTrue(fetcher.downloadImage(server.url("/big.png").toString(), sink, maxBytes = 1024).isFailure)
        assertTrue(savedBytes.isEmpty())
    }

    private companion object {
        const val VIDEO_ID = "9c9de5e8xQNXhHR4Fvu8Qa"
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val DETAILS = """
            {"uuid":"9c9de5e8-89c4-4c5c-8b8a-3f1c2e2b1a77","shortUUID":"$VIDEO_ID","name":"動画の名前",
             "description":"動画の説明","isLive":false,"downloadEnabled":false,
             "channel":{"displayName":"チャンネル名"},"account":{"displayName":"投稿者"},
             "tags":["動画","テスト"],
             "thumbnails":[{"fileUrl":"/lazy-static/thumbnails/small.jpg","width":280},{"fileUrl":"/lazy-static/thumbnails/large.jpg","width":850}],
             "thumbnailPath":"/lazy-static/thumbnails/old.jpg"}
        """.trimIndent()
        val VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nこんにちは\n\n00:00:02.000 --> 00:00:04.000\nPeerTube の話です\n"
    }
}
