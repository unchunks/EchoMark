package com.unchunks.echomark.data.remote

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本文の掃除(画面の部品・繰り返し・段落の区切り)。 */
class BodyCleanerTest {

    private fun extract(bodyHtml: String, maxChars: Int = 5000): String {
        val document = Jsoup.parse("<html><body>$bodyHtml</body></html>")
        return BodyCleaner.extractText(document.body(), maxChars)
    }

    @Test
    fun 段落の区切りを行として残す() {
        val text = extract("<article><h1>見出し</h1><p>1段落目です。</p><p>2段落目<br>改行あり</p><ul><li>項目A</li><li>項目B</li></ul></article>")

        assertEquals("見出し\n1段落目です。\n2段落目\n改行あり\n項目A\n項目B", text)
    }

    @Test
    fun 共有ボタン_関連記事_コメント_Cookieの案内の要素は除く() {
        val text = extract(
            """
            <article>
              <p>記事の本文です。</p>
              <div class="share-buttons"><a>ツイート</a><a>いいね</a></div>
              <div class="related-posts"><a>関連記事のタイトル</a></div>
              <section id="comments"><p>コメントです</p></section>
              <div class="cookie-banner">Cookie を使用しています。同意しますか?</div>
              <div class="adsbygoogle">広告</div>
              <p>記事の続きです。</p>
            </article>
            """.trimIndent()
        )

        assertEquals("記事の本文です。\n記事の続きです。", text)
    }

    @Test
    fun 非表示と役割がナビゲーションの要素は除く() {
        val text = extract(
            """
            <p>本文</p>
            <div hidden>隠れている</div>
            <div aria-hidden="true">読み上げない</div>
            <div role="navigation">メニュー項目</div>
            <div role="dialog">ダイアログ</div>
            """.trimIndent()
        )

        assertEquals("本文", text)
    }

    @Test
    fun 取り出す範囲を含む要素や名前が似ただけの要素は消さない() {
        // root(article)に付いた share のクラスや、「shared」「breadcrumbs-free」のような別の語は対象外
        val document = Jsoup.parse(
            """
            <html><body class="social-layout"><article class="share">
              <p>本文</p><div class="shared-note">共有された注釈</div><div class="read-more-text">読みものの続き</div>
            </article></body></html>
            """.trimIndent()
        )

        val text = BodyCleaner.extractText(document.selectFirst("article")!!, 5000)

        assertEquals("本文\n共有された注釈\n読みものの続き", text)
    }

    @Test
    fun 画面の部品の文言_著作権表示_記号だけの行を除く() {
        val text = BodyCleaner.cleanLines(
            """
            ログイン
            記事の本文です。
            シェア
            © 2026 Example Inc.
            Copyright 2026 Example
            |
            1 2 3
            続きを読む
            Skip to content
            """.trimIndent()
        )

        assertEquals("記事の本文です。", text)
    }

    @Test
    fun 同じ行の繰り返しは最初の1回だけ_短い行は繰り返してよい() {
        val text = BodyCleaner.cleanLines("メニューの長めの項目名\n本文\nメニューの長めの項目名\nはい\nはい")

        assertEquals("メニューの長めの項目名\n本文\nはい\nはい", text)
    }

    @Test
    fun 本文中のCookieという語だけでは除かない() {
        val text = BodyCleaner.cleanLines("Cookie は小さなテキストファイルで、ブラウザが保存する仕組みです。")

        assertEquals("Cookie は小さなテキストファイルで、ブラウザが保存する仕組みです。", text)
    }

    @Test
    fun preの中の改行は残し_それ以外の空白はまとめる() {
        val text = extract("<p>a   b\n  c</p><pre>line1\n  line2</pre>")

        assertEquals("a b c\nline1\n  line2".lines().map { it.trim() }.joinToString("\n"), text)
    }

    @Test
    fun 上限を超えた分は切る() {
        val text = extract("<p>" + "あ".repeat(100) + "</p>", maxChars = 30)

        assertEquals(30, text.length)
        assertFalse(text.contains("\n"))
        assertTrue(text.all { it == 'あ' })
    }
}
