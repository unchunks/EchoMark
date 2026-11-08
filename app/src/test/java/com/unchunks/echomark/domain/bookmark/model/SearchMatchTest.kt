package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {

    private fun bookmark(
        title: String = "タイトル",
        summary: String? = null,
        content: String? = null,
        tags: List<String> = emptyList(),
        contentUri: String? = null,
        fileName: String? = null,
        siteName: String? = null
    ) = Bookmark(
        type = BookmarkType.URL,
        title = title,
        summary = summary,
        content = content,
        tags = tags,
        contentUri = contentUri,
        fileName = fileName,
        siteName = siteName,
        createdAt = 0,
        lastAccessedAt = 0
    )

    private fun text(source: String, ranges: List<IntRange>) = ranges.map { source.substring(it.first, it.last + 1) }

    @Test
    fun 一致した場所を列挙する() {
        val match = SearchMatcher.explain(
            bookmark(
                title = "Kotlin 入門", summary = "言語の紹介", content = "kotlin の本文", tags = listOf("android", "kotlin"),
                contentUri = "https://example.com/kotlin", fileName = "kotlin.pdf", siteName = "Kotlin Blog"
            ),
            listOf("kotlin")
        )
        assertEquals(
            listOf(MatchField.TITLE, MatchField.CONTENT, MatchField.FILE_NAME, MatchField.URL, MatchField.SITE_NAME, MatchField.TAG),
            match.fields
        )
        assertEquals(listOf("kotlin"), match.tags)
        assertEquals("タイトル・本文・ファイル名・URL・サイト名・タグ「kotlin」", match.reason)
    }

    @Test
    fun どこにも一致しなければ意味だけで見つかったもの() {
        val match = SearchMatcher.explain(bookmark(title = "果物", content = "りんごの話"), listOf("apple"))
        assertTrue(match.isSemanticOnly)
        assertNull(match.snippet)
    }

    @Test
    fun 大文字小文字と全角半角を区別しない() {
        val match = SearchMatcher.explain(bookmark(title = "Compose の使い方"), listOf("ＣＯＭＰＯＳＥ"))
        assertEquals(listOf(MatchField.TITLE), match.fields)
        val source = "Compose の使い方 compose"
        assertEquals(listOf("Compose", "compose"), text(source, SearchMatcher.ranges(source, listOf("COMPOSE"))))
    }

    @Test
    fun 半角カナの検索語でも全角カナの本文に一致する() {
        val source = "今日はラーメンを食べた"
        val ranges = SearchMatcher.ranges(source, listOf("ﾗｰﾒﾝ"))
        assertEquals(listOf("ラーメン"), text(source, ranges))
    }

    @Test
    fun 全角英数字の本文を半角の語で探すと元の位置で返る() {
        val source = "ＲＥＡＣＴ入門 react"
        assertEquals(listOf("ＲＥＡＣＴ", "react"), text(source, SearchMatcher.ranges(source, listOf("react"))))
    }

    @Test
    fun 複数の語の範囲は重なれば1つにまとめる() {
        val source = "東京のラーメン屋"
        assertEquals(listOf("東京", "ラーメン"), text(source, SearchMatcher.ranges(source, listOf("東京", "ラーメン"))))
        assertEquals(listOf("東京のラーメン"), text(source, SearchMatcher.ranges(source, listOf("東京の", "のラーメン"))))
    }

    @Test
    fun 語が無ければ範囲も無い() {
        assertTrue(SearchMatcher.ranges("本文", emptyList()).isEmpty())
        assertTrue(SearchMatcher.ranges("", listOf("a")).isEmpty())
        assertTrue(SearchMatcher.ranges("本文", listOf("")).isEmpty())
    }

    @Test
    fun 範囲の数には上限がある() {
        val source = "a".repeat(1000)
        assertEquals("つながった一致は1つにまとめる", 1, SearchMatcher.ranges(source, listOf("a")).size)
        assertTrue(SearchMatcher.ranges("a ".repeat(1000), listOf("a")).size <= SearchMatcher.MAX_RANGES)
    }

    @Test
    fun タイトルと要約で見える語だけなら本文の一節は出さない() {
        val match = SearchMatcher.explain(
            bookmark(title = "ラーメン特集", summary = "東京の店", content = "東京のラーメンを巡る"),
            listOf("東京", "ラーメン")
        )
        assertNull(match.snippet)
    }

    @Test
    fun 見えない語が本文にあるときは前後を切り出して強調の範囲を付ける() {
        val body = "あ".repeat(100) + "ラーメンの名店を巡る旅" + "い".repeat(100)
        val match = SearchMatcher.explain(bookmark(title = "東京", content = body), listOf("東京", "ラーメン"))
        val snippet = match.snippet
        assertNotNull(snippet)
        snippet!!
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertEquals(listOf("ラーメン"), text(snippet.text, snippet.ranges))
        assertTrue(snippet.text.length < body.length)
    }

    @Test
    fun 本文の改行は空白にして切り出す() {
        val match = SearchMatcher.explain(bookmark(title = "x", content = "1行目\nkotlin の話\n3行目"), listOf("kotlin"))
        val snippet = match.snippet!!
        assertEquals("1行目 kotlin の話 3行目", snippet.text)
        assertEquals(listOf("kotlin"), text(snippet.text, snippet.ranges))
    }

    @Test
    fun サロゲートペアを含む本文でも範囲がずれない() {
        val source = "😀絵文字 kotlin 😀"
        assertEquals(listOf("kotlin"), text(source, SearchMatcher.ranges(source, listOf("kotlin"))))
    }
}
