package com.unchunks.echomark.data.remote

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLinksTest {

    private fun collect(html: String, limit: Int = PageLinks.MAX_LINKS) =
        PageLinks.collect(Jsoup.parse(html, "https://example.com/list/"), limit)

    @Test
    fun 相対リンクは取得した先を基準にした絶対URLにする() {
        val links = collect("<a href=\"../articles/1\">記事 1</a><a href=\"/top\">トップ</a>")

        assertEquals(
            listOf(
                PageLink("https://example.com/articles/1", "記事 1"),
                PageLink("https://example.com/top", "トップ")
            ),
            links
        )
    }

    @Test
    fun フラグメントだけが違うURLと_http以外のリンクと_文字の無いリンクは除く() {
        val links = collect(
            """
            <a href="/a#top">記事</a>
            <a href="/a#comments">記事のコメント</a>
            <a href="mailto:x@example.com">連絡</a>
            <a href="javascript:void(0)">開く</a>
            <a href="/img.png"><img src="x.png"></a>
            """.trimIndent()
        )

        assertEquals(listOf(PageLink("https://example.com/a", "記事")), links)
    }

    @Test
    fun 文字の空白は1つにまとめ_上限を超えたら打ち切る() {
        val links = collect(
            "<a href=\"/1\">  長い\n  文字 </a><a href=\"/2\">二</a><a href=\"/3\">三</a>",
            limit = 2
        )

        assertEquals(listOf(PageLink("https://example.com/1", "長い 文字"), PageLink("https://example.com/2", "二")), links)
    }
}
