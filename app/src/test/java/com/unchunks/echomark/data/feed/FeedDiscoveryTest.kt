package com.unchunks.echomark.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedDiscoveryTest {

    private val channelId = "UCabcdefghijklmnopqrstuv"

    @Test
    fun 入力はスキームを補い_使えない形は断る() {
        assertEquals("https://example.com/blog", FeedDiscovery.normalizeInput("  example.com/blog "))
        assertEquals("http://example.com/feed", FeedDiscovery.normalizeInput("http://example.com/feed"))
        assertNull(FeedDiscovery.normalizeInput(""))
        assertNull(FeedDiscovery.normalizeInput("ただの文章"))
        assertNull(FeedDiscovery.normalizeInput("ftp://example.com/x"))
        assertNull(FeedDiscovery.normalizeInput("javascript:alert(1)"))
        assertNull(FeedDiscovery.normalizeInput("https://exa mple.com"))
        assertNull(FeedDiscovery.normalizeInput("localhost"))
        // スキームを自分で書いた入力は受ける(ローカルのアドレスは、取得するときに断る)
        assertEquals("http://localhost:8080/feed", FeedDiscovery.normalizeInput("http://localhost:8080/feed"))
    }

    @Test
    fun チャンネルIDだけの入力はチャンネルのURLにする() {
        assertEquals("https://www.youtube.com/channel/$channelId", FeedDiscovery.normalizeInput(channelId))
    }

    @Test
    fun YouTubeのチャンネルと再生リストからフィードのURLを作る() {
        val feed = "https://www.youtube.com/feeds/videos.xml"
        assertEquals("$feed?channel_id=$channelId", FeedDiscovery.youtubeFeedUrl("https://www.youtube.com/channel/$channelId"))
        assertEquals("$feed?channel_id=$channelId", FeedDiscovery.youtubeFeedUrl("https://youtube.com/channel/$channelId/videos"))
        assertEquals("$feed?channel_id=$channelId", FeedDiscovery.youtubeFeedUrl("https://m.youtube.com/channel/$channelId?si=x"))
        assertEquals("$feed?playlist_id=PLabcdefghijklmnop", FeedDiscovery.youtubeFeedUrl("https://www.youtube.com/playlist?list=PLabcdefghijklmnop"))
        assertEquals("$feed?channel_id=$channelId", FeedDiscovery.youtubeFeedUrl("$feed?channel_id=$channelId"))
    }

    @Test
    fun ハンドルのページや他のサイトではフィードのURLを作らない() {
        assertNull(FeedDiscovery.youtubeFeedUrl("https://www.youtube.com/@someone"))
        assertNull(FeedDiscovery.youtubeFeedUrl("https://www.youtube.com/watch?v=AAAAAAAAAAA"))
        assertNull(FeedDiscovery.youtubeFeedUrl("https://example.com/channel/$channelId"))
        assertNull(FeedDiscovery.youtubeFeedUrl("https://notyoutube.com/channel/$channelId"))
        assertNull(FeedDiscovery.youtubeFeedUrl("https://www.youtube.com/channel/UCshort"))
    }

    @Test
    fun HTMLのlinkからRSSとAtomのフィードを探し_相対URLは解決する() {
        val html = """
            <html><head>
              <link rel="stylesheet" href="/style.css">
              <link rel="alternate" type="text/html" href="/ja/">
              <link rel="alternate" type="application/atom+xml" title="Atom" href="/atom.xml">
              <link rel="alternate" type="application/rss+xml" title="RSS" href="/rss.xml">
            </head><body></body></html>
        """.trimIndent()

        assertEquals("https://example.com/atom.xml", FeedDiscovery.feedUrlFromHtml(html, "https://example.com/blog/"))
    }

    @Test
    fun フィードのリンクが無い_またはhttp以外なら探せない() {
        assertNull(FeedDiscovery.feedUrlFromHtml("<html><head></head></html>", "https://example.com/"))
        assertNull(
            FeedDiscovery.feedUrlFromHtml(
                "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"javascript:alert(1)\">", "https://example.com/"
            )
        )
        // 最初のリンクが使えなければ、次のものを使う
        assertEquals(
            "https://example.com/ok.xml",
            FeedDiscovery.feedUrlFromHtml(
                "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"javascript:x\"><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/ok.xml\">",
                "https://example.com/"
            )
        )
    }

    @Test
    fun YouTubeのページのHTMLからチャンネルIDを探す() {
        val html = "...\"channelId\":\"$channelId\",\"title\":\"x\"..."

        assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId", FeedDiscovery.youtubeFeedUrlFromHtml(html))
        assertNull(FeedDiscovery.youtubeFeedUrlFromHtml("<html></html>"))
    }
}
