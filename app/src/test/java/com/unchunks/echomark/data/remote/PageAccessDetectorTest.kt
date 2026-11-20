package com.unchunks.echomark.data.remote

import com.unchunks.echomark.domain.bookmark.model.PageAccessLimit
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 有料・会員限定・ログインが要る・ボット対策のページの見分け方。 */
class PageAccessDetectorTest {

    @Test
    fun 有料の文言があり本文が短ければ有料() {
        val page = "記事の冒頭の数行です。 続きを読むには有料会員登録が必要です。"

        assertEquals(PageAccessLimit.PAYWALL, PageAccessDetector.detect("記事", page, extractedChars = 400))
        assertEquals(
            PageAccessLimit.PAYWALL,
            PageAccessDetector.detect("Article", "Intro paragraph. Subscribe to continue reading.", extractedChars = 300)
        )
    }

    @Test
    fun 空白の入り方が違っても文言を見つける() {
        assertEquals(
            PageAccessLimit.PAYWALL,
            PageAccessDetector.detect(null, "Please  Subscribe\n to   continue reading", extractedChars = 100)
        )
    }

    @Test
    fun 全文がある記事に会員限定の文言があるだけなら限定とは見なさない() {
        val page = "会員限定記事のラベル付きの長い記事です。" + "本文。".repeat(1000)

        assertNull(PageAccessDetector.detect("記事", page, extractedChars = 5000))
    }

    @Test
    fun ログインが要る文言はログインが必要() {
        assertEquals(
            PageAccessLimit.LOGIN_REQUIRED,
            PageAccessDetector.detect("記事", "冒頭です。ログインして続きを読む", extractedChars = 200)
        )
        assertEquals(
            PageAccessLimit.LOGIN_REQUIRED,
            PageAccessDetector.detect("Post", "Teaser. Sign in to continue reading.", extractedChars = 200)
        )
    }

    @Test
    fun ボット対策のページは本文が取れていない() {
        assertEquals(PageAccessLimit.BLOCKED, PageAccessDetector.detect("Just a moment...", "Enable JavaScript and cookies to continue", 40))
        assertEquals(PageAccessLimit.BLOCKED, PageAccessDetector.detect("Access Denied", "You don't have permission", 30))
        assertEquals(PageAccessLimit.BLOCKED, PageAccessDetector.detect("403 Forbidden", "", 0))
        // 長い本文の記事が「Access Denied」という語を含んでいても対象外(セキュリティの解説記事など)
        assertNull(PageAccessDetector.detect("認証の解説", "エラー Access Denied の対処。" + "説明。".repeat(500), extractedChars = 3000))
    }

    @Test
    fun 構造化データの有料指定は本文が短いときだけ有料() {
        val markup = PageAccessDetector.Markup(structuredPaywall = true)

        assertEquals(PageAccessLimit.PAYWALL, PageAccessDetector.detect("記事", "冒頭", extractedChars = 800, markup = markup))
        assertNull(PageAccessDetector.detect("記事", "全文", extractedChars = 5000, markup = markup))
    }

    @Test
    fun ペイウォールのクラス名は本文がごく短いときだけ有料() {
        val markup = PageAccessDetector.Markup(paywallElement = true)

        assertEquals(PageAccessLimit.PAYWALL, PageAccessDetector.detect("記事", "冒頭", extractedChars = 900, markup = markup))
        // 全文のあるページに計測用のペイウォールの要素が残っているだけなら有料にしない
        assertNull(PageAccessDetector.detect("記事", "全文", extractedChars = 3000, markup = markup))
    }

    @Test
    fun 通常のページは制限なし() {
        assertNull(PageAccessDetector.detect("記事", "普通の短い記事です。", extractedChars = 200))
    }

    @Test
    fun ページから目印を拾う() {
        val structured = Jsoup.parse(
            """<html><head><script type="application/ld+json">{"@type":"NewsArticle","isAccessibleForFree": "False"}</script></head><body></body></html>"""
        )
        val element = Jsoup.parse("""<html><body><div class="article-body paywall-gate">冒頭</div></body></html>""")
        val plain = Jsoup.parse(
            """<html><head><script type="application/ld+json">{"@type":"Article","isAccessibleForFree":true}</script></head><body><p>本文</p></body></html>"""
        )

        assertTrue(PageAccessDetector.markupOf(structured).structuredPaywall)
        assertTrue(PageAccessDetector.markupOf(element).paywallElement)
        assertFalse(PageAccessDetector.markupOf(plain).structuredPaywall)
        assertFalse(PageAccessDetector.markupOf(plain).paywallElement)
    }
}
