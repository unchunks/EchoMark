package com.unchunks.echomark.data.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ResourceParsersTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("discover/$name")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    // --- はてなブックマーク ---

    @Test
    fun はてなのRSSから候補を読む() {
        val candidates = HatenaRss.parse(fixture("hatena.xml"))

        // チャンネル自体は候補にならず、item だけが並ぶ
        assertEquals(3, candidates.size)
        val first = candidates[0]
        assertEquals("https://qiita.com/example/items/aaaaaaaaaaaaaaaaaaaa", first.url)
        assertEquals("Kotlinコルーチンの仕組みを理解する - Qiita", first.title)
        assertEquals("Qiita", first.siteName)
        assertEquals(412L, first.popularity)
        assertEquals("2025-10-23T10:02:17Z", first.publishedAt)
        assertEquals(ResourceSource.ARTICLE, first.source)
        assertTrue(first.snippet.startsWith("はじめに Kotlin のコルーチンは"))
        assertNull(first.thumbnailUrl)
    }

    @Test
    fun はてなのタグから_あとで読む_を除く() {
        val first = HatenaRss.parse(fixture("hatena.xml")).first()

        assertEquals(listOf("テクノロジー", "Kotlin", "Android"), first.tags)
    }

    @Test
    fun はてなの画像と実体参照を読む() {
        val second = HatenaRss.parse(fixture("hatena.xml"))[1]

        assertEquals("Flow & Channel 入門 | Zenn", second.title)
        assertEquals("Zenn", second.siteName)
        assertEquals("https://res.cloudinary.com/zenn/image/upload/example.png", second.thumbnailUrl)
        assertEquals(131L, second.popularity)
        assertTrue(second.snippet.startsWith("Flow & Channel を使った"))
    }

    @Test
    fun はてなでタイトルに屋号が無ければURLのホストをサイト名にする() {
        val third = HatenaRss.parse(fixture("hatena.xml"))[2]

        assertEquals("blog.example.co.jp", third.siteName)
        assertEquals("", third.snippet)
        assertEquals(emptyList<String>(), third.tags)
    }

    @Test
    fun はてなは件数を制限しURLの重複とタイトルの無い項目を除く() {
        val xml = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"
              xmlns:hatena="http://www.hatena.ne.jp/info/xmlns#">
            <item rdf:about="https://a.example/1"><title>one</title><link>https://a.example/1</link></item>
            <item rdf:about="https://a.example/1"><title>one again</title><link>https://a.example/1</link></item>
            <item rdf:about="https://a.example/2"><title></title><link>https://a.example/2</link></item>
            <item rdf:about="javascript:alert(1)"><title>bad</title></item>
            <item rdf:about="https://a.example/3"><title>three</title></item>
            <item rdf:about="https://a.example/4"><title>four</title></item>
            </rdf:RDF>
        """.trimIndent()

        assertEquals(listOf("one", "three", "four"), HatenaRss.parse(xml).map { it.title })
        assertEquals(listOf("one", "three"), HatenaRss.parse(xml, limit = 2).map { it.title })
    }

    @Test
    fun はてなの概要は300文字までにする() {
        val xml = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/">
            <item rdf:about="https://a.example/1"><title>long</title><description>${"あ".repeat(500)}</description></item>
            </rdf:RDF>
        """.trimIndent()

        assertEquals(300, HatenaRss.parse(xml).single().snippet.length)
    }

    // --- Wikipedia ---

    @Test
    fun Wikipediaの検索結果から候補を読む() {
        val candidates = WikipediaSearchJson.parse(fixture("wikipedia.json"))

        // タイトルが空の項目は除かれる
        assertEquals(2, candidates.size)
        val first = candidates[0]
        assertEquals("コルーチン", first.title)
        assertEquals("https://ja.wikipedia.org/wiki/%E3%82%B3%E3%83%AB%E3%83%BC%E3%83%81%E3%83%B3", first.url)
        assertEquals("Wikipedia", first.siteName)
        assertEquals(ResourceSource.ENCYCLOPEDIA, first.source)
        assertEquals("2023-08-28T02:36:27Z", first.publishedAt)
        assertNull(first.popularity)
    }

    @Test
    fun Wikipediaの概要からタグと実体参照を除く() {
        val candidates = WikipediaSearchJson.parse(fixture("wikipedia.json"))

        assertTrue(candidates[0].snippet.startsWith("コルーチン（英: co-routine）とはプログラミングの構造の一種。"))
        assertFalse(candidates[0].snippet.contains("<"))
        assertEquals("Kotlin \"Hello\" & コルーチン <tag> をサポートする", candidates[1].snippet)
    }

    @Test
    fun Wikipediaのタイトルの空白はアンダースコアにする() {
        assertEquals(
            "https://ja.wikipedia.org/wiki/Kotlin_(%E3%83%97%E3%83%AD%E3%82%B0%E3%83%A9%E3%83%9F%E3%83%B3%E3%82%B0%E8%A8%80%E8%AA%9E)",
            WikipediaSearchJson.parse(fixture("wikipedia.json"))[1].url
        )
        assertEquals("https://ja.wikipedia.org/wiki/A_B", WikipediaSearchJson.articleUrl("A B"))
    }

    @Test
    fun Wikipediaの結果が無い応答は空になる() {
        assertEquals(emptyList<ResourceCandidate>(), WikipediaSearchJson.parse("""{"batchcomplete":"","query":{"search":[]}}"""))
        assertEquals(emptyList<ResourceCandidate>(), WikipediaSearchJson.parse("{}"))
    }

    // --- YouTube ---

    @Test
    fun YouTubeの検索結果からvideoRendererだけを候補にする() {
        val candidates = YouTubeSearchJson.parse(fixture("youtube_search.json"))

        // 広告・ショート・チャンネル・IDが不正なもの・重複は入らない
        assertEquals(listOf("B11F95_AO6k", "abcdefghijk", "live1234567"), candidates.map { it.url.substringAfter("v=") })
        val first = candidates[0]
        assertEquals("https://www.youtube.com/watch?v=B11F95_AO6k", first.url)
        assertEquals("【はじめてのKotlin】15分で作るアプリ", first.title)
        assertEquals("Howcang", first.siteName)
        assertEquals(24553L, first.popularity)
        assertEquals("3 年前", first.publishedAt)
        assertEquals("https://i.ytimg.com/vi/B11F95_AO6k/hqdefault.jpg", first.thumbnailUrl)
        assertEquals("Kotlin で コルーチン を使う方法を説明します。", first.snippet)
        assertEquals(ResourceSource.VIDEO, first.source)
    }

    @Test
    fun YouTubeの説明が無い動画は長さと再生回数を概要にする() {
        val second = YouTubeSearchJson.parse(fixture("youtube_search.json"))[1]

        assertEquals("長さ 10:26 ・ 1,826回視聴", second.snippet)
        assertEquals(1826L, second.popularity)
    }

    @Test
    fun YouTubeの再生回数がrunsで来ても数字を読む() {
        val live = YouTubeSearchJson.parse(fixture("youtube_search.json"))[2]

        assertEquals(123L, live.popularity)
        assertEquals("Live Channel", live.siteName)
    }

    @Test
    fun YouTubeは件数を制限する() {
        assertEquals(2, YouTubeSearchJson.parse(fixture("youtube_search.json"), limit = 2).size)
    }

    @Test
    fun YouTubeの再生回数の文字列から数字を取り出す() {
        assertEquals(1826L, YouTubeSearchJson.viewsOf("1,826回視聴"))
        assertEquals(12L, YouTubeSearchJson.viewsOf("12 views"))
        assertNull(YouTubeSearchJson.viewsOf("視聴なし"))
    }

    @Test
    fun YouTubeの応答の形が違うときは例外にする() {
        try {
            YouTubeSearchJson.parse("""{"error":{"code":400}}""")
            fail("例外が投げられるはず")
        } catch (e: IOException) {
            // 期待どおり
        }
    }

    // --- arXiv ---

    @Test
    fun arXivのAtomから候補を読む() {
        val candidates = ArxivAtom.parse(fixture("arxiv.xml"))

        assertEquals(2, candidates.size)
        val first = candidates[0]
        // フィード自体の title は候補にならず、http の ID は https になる
        assertEquals("https://arxiv.org/abs/2308.10548v1", first.url)
        // 改行を含むタイトルは空白を整える
        assertEquals("Typing Composable Coroutines", first.title)
        assertEquals("arXiv", first.siteName)
        assertEquals("2023-08-21T08:04:07Z", first.publishedAt)
        assertEquals(ResourceSource.PAPER, first.source)
        assertEquals(listOf("cs.PL"), first.tags)
        assertEquals(
            "Qiqi Gu, Wei Ke: Coroutine, as a powerful programming construct, is widely used in " +
                "asynchronous applications to replace thread-based programming or the callback hell.",
            first.snippet
        )
    }

    @Test
    fun arXivの著者が多いときは3人までにして_ほか_を付ける() {
        val second = ArxivAtom.parse(fixture("arxiv.xml"))[1]

        assertEquals(listOf("cs.PL", "cs.SC"), second.tags)
        assertTrue(second.snippet.startsWith("Qiqi Jason Gu, Lixue Liu, Wei Ke ほか: Coroutines are"))
    }

    @Test
    fun arXivは件数を制限する() {
        assertEquals(1, ArxivAtom.parse(fixture("arxiv.xml"), limit = 1).size)
    }
}
