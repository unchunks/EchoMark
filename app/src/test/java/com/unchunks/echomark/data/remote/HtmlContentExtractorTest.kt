package com.unchunks.echomark.data.remote

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlContentExtractorTest {

    private fun extract(html: String) = HtmlContentExtractor.extract(Jsoup.parse(html))

    @Test
    fun ogタイトルと説明をtitleやmetaより優先する() {
        val result = extract(
            """
            <html><head>
              <title>ページタイトル</title>
              <meta property="og:title" content="OGタイトル">
              <meta property="og:description" content="OG説明">
              <meta name="description" content="通常の説明">
            </head><body><p>本文</p></body></html>
            """.trimIndent()
        )
        assertEquals("OGタイトル", result.title)
        assertEquals("OG説明", result.description)
    }

    @Test
    fun ogが無ければtitleとmeta_descriptionを使う() {
        val result = extract(
            """
            <html><head>
              <title>  ページタイトル  </title>
              <meta name="description" content=" 通常の説明 ">
            </head><body>本文</body></html>
            """.trimIndent()
        )
        assertEquals("ページタイトル", result.title)
        assertEquals("通常の説明", result.description)
    }

    @Test
    fun タイトルも説明も無ければnull() {
        val result = extract("<html><body><p>本文だけ</p></body></html>")
        assertNull(result.title)
        assertNull(result.description)
        assertEquals("本文だけ", result.text)
    }

    @Test
    fun 空白だけのmetaは無視してフォールバックする() {
        val result = extract(
            """
            <html><head><title>T</title><meta property="og:title" content="  "></head><body></body></html>
            """.trimIndent()
        )
        assertEquals("T", result.title)
    }

    @Test
    fun ノイズ要素は本文から除去される() {
        val result = extract(
            """
            <html><body>
              <header>ヘッダー</header>
              <nav>ナビ</nav>
              <script>var x = 1;</script>
              <style>.a { color: red }</style>
              <p>本文です</p>
              <aside>広告</aside>
              <footer>フッター</footer>
            </body></html>
            """.trimIndent()
        )
        assertEquals("本文です", result.text)
    }

    @Test
    fun articleがあればその中だけを本文にする() {
        val result = extract(
            """
            <html><body>
              <div>周辺テキスト</div>
              <main><p>mainの本文</p></main>
              <article><p>articleの本文</p></article>
            </body></html>
            """.trimIndent()
        )
        assertEquals("articleの本文", result.text)
    }

    @Test
    fun articleが無ければmainを使う() {
        val result = extract(
            """
            <html><body><div>周辺</div><main><p>mainの本文</p></main></body></html>
            """.trimIndent()
        )
        assertEquals("mainの本文", result.text)
    }

    @Test
    fun 本文は最大文字数で切り詰められる() {
        val long = "あ".repeat(HtmlContentExtractor.MAX_TEXT_CHARS + 100)
        val result = extract("<html><body><p>$long</p></body></html>")
        assertEquals(HtmlContentExtractor.MAX_TEXT_CHARS, result.text.length)
    }

    @Test
    fun 空のドキュメントでも落ちない() {
        val result = extract("")
        assertNull(result.title)
        assertNull(result.description)
        assertTrue(result.text.isEmpty())
        assertFalse(result.text.contains("null"))
    }

    @Test
    fun og画像とサイト名を取り出す() {
        val result = extract(
            """
            <html><head>
              <meta property="og:image" content="https://cdn.example.com/og.png">
              <meta property="og:site_name" content=" Example Blog ">
            </head><body></body></html>
            """.trimIndent()
        )
        assertEquals("https://cdn.example.com/og.png", result.imageUrl)
        assertEquals("Example Blog", result.siteName)
    }

    @Test
    fun 相対URLのog画像はページのURLを基準に絶対化する() {
        val document = Jsoup.parse(
            """<html><head><meta property="og:image" content="/img/cover.jpg"></head></html>""",
            "https://www.example.com/articles/1"
        )
        val result = HtmlContentExtractor.extract(document)
        assertEquals("https://www.example.com/img/cover.jpg", result.imageUrl)
    }

    @Test
    fun og画像が無ければtwitter画像を使いsecure_urlを優先する() {
        val twitter = extract(
            """<html><head><meta name="twitter:image" content="https://example.com/tw.png"></head></html>"""
        )
        assertEquals("https://example.com/tw.png", twitter.imageUrl)

        val secure = extract(
            """
            <html><head>
              <meta property="og:image" content="http://example.com/a.png">
              <meta property="og:image:secure_url" content="https://example.com/a.png">
            </head></html>
            """.trimIndent()
        )
        assertEquals("https://example.com/a.png", secure.imageUrl)
    }

    @Test
    fun 基準の無い相対URLやdata画像は使わずサイト名はapplication_nameで補う() {
        val result = extract(
            """
            <html><head>
              <meta property="og:image" content="data:image/png;base64,AAAA">
              <meta name="application-name" content="アプリ名">
            </head></html>
            """.trimIndent()
        )
        assertNull(result.imageUrl)
        assertEquals("アプリ名", result.siteName)

        val relative = extract("""<html><head><meta property="og:image" content="img/a.png"></head></html>""")
        assertNull(relative.imageUrl)
    }

    @Test
    fun og画像もサイト名も無ければnull() {
        val result = extract("<html><head><title>T</title></head><body>本文</body></html>")
        assertNull(result.imageUrl)
        assertNull(result.siteName)
    }
}
