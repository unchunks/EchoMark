package com.unchunks.echomark.domain.podcast

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 台本の決まった部分(出典の紹介を含む)と、要約の読み上げ。 */
class PodcastTemplatesTest {

    private fun bookmark(
        type: BookmarkType = BookmarkType.URL,
        title: String = "Kotlin 2.3 の新機能 | Zenn",
        url: String? = "https://zenn.dev/articles/a",
        siteName: String? = "Zenn",
        summary: String? = "K2 が既定になった。ビルドも速い。",
        content: String? = "本文"
    ) = Bookmark(id = 7, type = type, contentUri = url, title = title, siteName = siteName, summary = summary, content = content, createdAt = 0, lastAccessedAt = 0)

    @Test
    fun ブックマークから材料を作り_タイトルの後ろのサイト名を除く() {
        val source = PodcastTemplates.sourceOf(bookmark(), index = 2, excerptChars = 1)
        assertEquals("Kotlin 2.3 の新機能", source.title)
        assertEquals("Zenn", source.sourceName)
        assertEquals("記事", source.kindLabel)
        assertEquals("本", source.excerpt)
        assertEquals(2, source.index)
    }

    @Test
    fun サイト名が無ければドメインを出典にする() {
        val source = PodcastTemplates.sourceOf(bookmark(siteName = null, url = "https://www.youtube.com/watch?v=x", title = "動画"), 1, 100)
        assertEquals("youtube.com", source.sourceName)
        assertEquals("動画", source.kindLabel)
    }

    @Test
    fun メモには出典が無い() {
        val source = PodcastTemplates.sourceOf(bookmark(type = BookmarkType.TEXT, url = null, siteName = null, title = "買い物"), 1, 100)
        assertNull(source.sourceName)
        assertEquals("1件目は、保存したメモ「買い物」です。", PodcastTemplates.attribution(source, total = 3).text)
    }

    @Test
    fun 出典の紹介() {
        val source = PodcastTemplates.sourceOf(bookmark(), 2, 100)
        assertEquals("2件目は、Zennの記事「Kotlin 2.3 の新機能」です。", PodcastTemplates.attribution(source, total = 3).text)
        assertEquals("紹介するのは、Zennの記事「Kotlin 2.3 の新機能」です。", PodcastTemplates.attribution(source.copy(index = 1), total = 1).text)
        assertEquals(2, PodcastTemplates.attribution(source, 3).sourceIndex)
    }

    @Test
    fun 始まりと終わり() {
        val sources = listOf(PodcastTemplates.sourceOf(bookmark(), 1, 0), PodcastTemplates.sourceOf(bookmark(siteName = "Qiita"), 2, 0))
        assertEquals(1, PodcastTemplates.opening(PodcastFormat.SOLO, sources).size)
        assertEquals(
            listOf(PodcastSpeaker.HOST, PodcastSpeaker.GUEST),
            PodcastTemplates.opening(PodcastFormat.DUO, sources).map { it.speaker }
        )
        assertTrue(PodcastTemplates.opening(PodcastFormat.SOLO, sources).single().text.contains("2件を紹介します"))
        val closing = PodcastTemplates.closing(PodcastFormat.SOLO, sources).single().text
        assertTrue(closing, closing.startsWith("出典は、Zenn、Qiitaでした。"))
    }

    @Test
    fun 題名() {
        val first = PodcastTemplates.sourceOf(bookmark(), 1, 0)
        assertEquals("Kotlin 2.3 の新機能", PodcastTemplates.episodeTitle(listOf(first)))
        assertEquals("Kotlin 2.3 の新機能 ほか2件", PodcastTemplates.episodeTitle(listOf(first, first, first)))
    }

    @Test
    fun 要約の読み上げは文ごとに語り手が読む() {
        val source = PodcastTemplates.sourceOf(bookmark(summary = "- K2 が既定になった。\n- ビルドも速い"), 1, 100)
        assertEquals(
            listOf(ScriptLine(PodcastSpeaker.HOST, "K2 が既定になった。", 1), ScriptLine(PodcastSpeaker.HOST, "ビルドも速い", 1)),
            PodcastTemplates.summaryRead(source)
        )
        val empty = PodcastTemplates.sourceOf(bookmark(summary = null, content = null), 1, 100)
        assertEquals("このブックマークには、まだ要約がありません。", PodcastTemplates.summaryRead(empty).single().text)
    }

    @Test
    fun 長いタイトルは切る() {
        val title = PodcastTemplates.spokenTitle("あ".repeat(100), null)
        assertEquals(60, title.length)
        assertTrue(title.endsWith("…"))
    }
}
