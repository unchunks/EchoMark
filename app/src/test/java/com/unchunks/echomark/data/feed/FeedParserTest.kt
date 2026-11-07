package com.unchunks.echomark.data.feed

import com.unchunks.echomark.domain.subscription.SubscriptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** フィード(RSS 2.0・RSS 1.0・Atom・YouTube)の読み取り。 */
class FeedParserTest {

    private val feedUrl = "https://example.com/feed.xml"

    private fun parse(xml: String, url: String = feedUrl) = checkNotNull(FeedParser.parse(xml, url)) { "フィードとして読めなかった" }

    @Test
    fun RSS2はタイトル_サイト_記事を読む() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>開発ブログ</title>
                <link>https://example.com/</link>
                <item>
                  <title>記事 &amp; その1</title>
                  <link>https://example.com/posts/1</link>
                  <guid isPermaLink="false">post-1</guid>
                  <pubDate>Tue, 03 Jun 2008 11:05:30 GMT</pubDate>
                </item>
                <item>
                  <title><![CDATA[CDATA の <題名>]]></title>
                  <link>/posts/2</link>
                  <dc:date>2023-04-05T12:34:56Z</dc:date>
                </item>
              </channel>
            </rss>
            """.trimIndent()
        )

        assertEquals(SubscriptionType.RSS, feed.type)
        assertEquals("開発ブログ", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals(2, feed.items.size)
        val first = feed.items[0]
        assertEquals("記事 & その1", first.title)
        assertEquals("https://example.com/posts/1", first.url)
        assertEquals("post-1", first.key)
        assertEquals(1_212_491_130_000L, first.publishedAt)
        val second = feed.items[1]
        // 相対 URL はフィードの URL で解決する。guid が無ければ URL が目印
        assertEquals("https://example.com/posts/2", second.url)
        assertEquals("https://example.com/posts/2", second.key)
        assertEquals(1_680_698_096_000L, second.publishedAt)
    }

    @Test
    fun feedburnerの元のURLがあればそれを使う() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:feedburner="http://rssnamespace.org/feedburner/ext/1.0"><channel><title>t</title>
              <item><title>a</title><link>https://feedproxy.example/r/abc</link><feedburner:origLink>https://example.com/real</feedburner:origLink></item>
            </channel></rss>
            """.trimIndent()
        )

        assertEquals("https://example.com/real", feed.items.single().url)
    }

    @Test
    fun RSS1_0のRDFも読む() {
        val feed = parse(
            """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://example.com/"><title>RDF のサイト</title><link>https://example.com/</link></channel>
              <item rdf:about="https://example.com/a"><title>RDF の記事</title><link>https://example.com/a</link><dc:date>2023-04-05T12:34:56+09:00</dc:date></item>
            </rdf:RDF>
            """.trimIndent()
        )

        assertEquals("RDF のサイト", feed.title)
        assertEquals("https://example.com/a", feed.items.single().url)
        assertEquals(1_680_665_696_000L, feed.items.single().publishedAt)
    }

    @Test
    fun Atomは代替リンクと公開日時を読む() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom のブログ</title>
              <link rel="self" href="https://example.com/atom.xml"/>
              <link rel="alternate" href="https://example.com/"/>
              <entry>
                <title>最初の記事</title>
                <link rel="enclosure" href="https://example.com/a.mp3"/>
                <link rel="alternate" type="text/html" href="https://example.com/e/1"/>
                <id>tag:example.com,2023:1</id>
                <published>2023-04-05T12:34:56Z</published>
                <updated>2023-04-06T00:00:00Z</updated>
              </entry>
              <entry>
                <title>リンクの rel が無い</title>
                <link href="https://example.com/e/2"/>
                <id>tag:example.com,2023:2</id>
                <updated>2023-04-07T00:00:00Z</updated>
              </entry>
            </feed>
            """.trimIndent()
        )

        assertEquals("Atom のブログ", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals(listOf("https://example.com/e/1", "https://example.com/e/2"), feed.items.map { it.url })
        assertEquals("tag:example.com,2023:1", feed.items[0].key)
        assertEquals(1_680_698_096_000L, feed.items[0].publishedAt)
        // 公開日時が無ければ更新日時
        assertEquals(1_680_825_600_000L, feed.items[1].publishedAt)
    }

    @Test
    fun YouTubeのフィードは動画のページのURLを作り_YouTubeとして扱う() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
              <title>あるチャンネル</title>
              <link rel="alternate" href="https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"/>
              <entry>
                <id>yt:video:AAAAAAAAAAA</id>
                <yt:videoId>AAAAAAAAAAA</yt:videoId>
                <title>動画のタイトル</title>
                <link rel="alternate" href="https://www.youtube.com/watch?v=AAAAAAAAAAA"/>
                <published>2023-04-05T12:34:56+00:00</published>
                <media:group><media:title>動画のタイトル</media:title></media:group>
              </entry>
              <entry>
                <id>yt:video:BBBBBBBBBBB</id>
                <yt:videoId>BBBBBBBBBBB</yt:videoId>
                <title>リンクの無い動画</title>
              </entry>
            </feed>
            """.trimIndent(),
            url = "https://www.youtube.com/feeds/videos.xml?channel_id=UCabcdefghijklmnopqrstuv"
        )

        assertEquals(SubscriptionType.YOUTUBE, feed.type)
        assertEquals("あるチャンネル", feed.title)
        assertEquals(
            listOf("https://www.youtube.com/watch?v=AAAAAAAAAAA", "https://www.youtube.com/watch?v=BBBBBBBBBBB"),
            feed.items.map { it.url }
        )
        assertEquals("yt:video:AAAAAAAAAAA", feed.items[0].key)
    }

    @Test
    fun httpとhttps以外の記事のURLは捨てる() {
        val feed = parse(
            """
            <rss version="2.0"><channel><title>t</title>
              <item><title>a</title><link>javascript:alert(1)</link></item>
              <item><title>b</title><link>file:///data/data/com.unchunks.echomark/databases/echomark.db</link></item>
              <item><title>c</title><link>ftp://example.com/x</link></item>
              <item><title>d</title><link>https://exa mple.com/x</link></item>
              <item><title>e</title><link>https://example.com/ok</link></item>
              <item><title>f</title></item>
            </channel></rss>
            """.trimIndent()
        )

        assertEquals(listOf("https://example.com/ok"), feed.items.map { it.url })
    }

    @Test
    fun タイトルのタグと余分な空白を取り除いて長さを切る() {
        val long = "あ".repeat(500)
        val feed = parse(
            """
            <rss version="2.0"><channel><title>  サイト
               の名前 </title>
              <item><title>&lt;b&gt;太字&lt;/b&gt;   の題名</title><link>https://example.com/1</link></item>
              <item><title>$long</title><link>https://example.com/2</link></item>
            </channel></rss>
            """.trimIndent()
        )

        assertEquals("サイト の名前", feed.title)
        assertEquals("太字 の題名", feed.items[0].title)
        assertEquals(300, feed.items[1].title!!.length)
    }

    @Test
    fun 記事は先頭から上限の件数までしか読まない() {
        val items = (1..FeedParser.MAX_ITEMS_PER_FEED + 50).joinToString("") { "<item><title>$it</title><link>https://example.com/$it</link></item>" }

        val feed = parse("<rss version=\"2.0\"><channel><title>t</title>$items</channel></rss>")

        assertEquals(FeedParser.MAX_ITEMS_PER_FEED, feed.items.size)
        assertEquals("https://example.com/1", feed.items.first().url)
    }

    @Test
    fun 外部の実体を取りに行かない() {
        // XXE の試み。実体は展開されず(取りに行かず)、読み取りは止まらない
        val feed = parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE rss [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <rss version="2.0"><channel><title>&xxe;</title>
              <item><title>a</title><link>https://example.com/1</link></item>
            </channel></rss>
            """.trimIndent()
        )

        assertTrue(!feed.title.contains("root:"))
        assertEquals("https://example.com/1", feed.items.single().url)
    }

    @Test
    fun HTMLやフィードでないXMLは読まない() {
        assertNull(FeedParser.parse("<!DOCTYPE html><html><head><title>x</title></head><body>ページ</body></html>", feedUrl))
        assertNull(FeedParser.parse("<?xml version=\"1.0\"?><sitemapindex><sitemap/></sitemapindex>", feedUrl))
        assertNull(FeedParser.parse("", feedUrl))
        assertNull(FeedParser.parse("ただの文章", feedUrl))
    }

    @Test
    fun タイトルが無いフィードはホスト名をタイトルにする() {
        val feed = parse("<rss version=\"2.0\"><channel><item><title>a</title><link>https://example.com/1</link></item></channel></rss>")

        assertEquals("example.com", feed.title)
    }

    @Test
    fun 文字コードをXMLの宣言から判定する() {
        val xml = "<?xml version=\"1.0\" encoding=\"Shift_JIS\"?><rss version=\"2.0\"><channel><title>日本語のタイトル</title></channel></rss>"

        val feed = FeedParser.parse(xml.toByteArray(charset("Shift_JIS")), null, feedUrl)

        assertNotNull(feed)
        assertEquals("日本語のタイトル", feed!!.title)
    }

    @Test
    fun 日時はRFC822_ISO_タイムゾーンの略称を読む() {
        assertEquals(1_212_491_130_000L, FeedParser.parseFeedDate("Tue, 03 Jun 2008 11:05:30 GMT"))
        assertEquals(1_212_491_130_000L, FeedParser.parseFeedDate("Tue, 03 Jun 2008 20:05:30 +0900"))
        assertEquals(1_212_491_100_000L, FeedParser.parseFeedDate("3 Jun 2008 11:05 GMT"))
        assertEquals(1_680_698_096_000L, FeedParser.parseFeedDate("2023-04-05T12:34:56Z"))
        assertEquals(1_680_698_096_000L, FeedParser.parseFeedDate("2023-04-05T12:34:56.000+00:00"))
        assertNull(FeedParser.parseFeedDate("昨日"))
        assertNull(FeedParser.parseFeedDate(""))
        assertNull(FeedParser.parseFeedDate(null))
    }

    @Test
    fun URLの解決はhttpとhttpsの絶対URLだけを返す() {
        assertEquals("https://example.com/a/b", FeedParser.resolve("https://example.com/a/", "b"))
        assertEquals("https://other.example/x", FeedParser.resolve("https://example.com/", "https://other.example/x"))
        assertNull(FeedParser.resolve("https://example.com/", "mailto:a@example.com"))
        assertNull(FeedParser.resolve("https://example.com/", "data:text/html,x"))
        assertNull(FeedParser.resolve("https://example.com/", ""))
    }
}
