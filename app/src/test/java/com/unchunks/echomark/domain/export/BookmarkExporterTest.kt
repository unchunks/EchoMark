package com.unchunks.echomark.domain.export

import com.unchunks.echomark.data.importer.LinkImportParser
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BookmarkExporterTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    /** 2026-10-01 09:00 (JST) */
    private val saved = 1_790_812_800_000L

    private fun url(
        id: Long,
        title: String,
        uri: String = "https://example.com/$id",
        summary: String? = null,
        tags: List<String> = emptyList(),
        siteName: String? = null,
        category: String? = null,
        favorite: Boolean = false
    ) = Bookmark(
        id = id, type = BookmarkType.URL, title = title, contentUri = uri, summary = summary, tags = tags,
        siteName = siteName, category = category, isFavorite = favorite, createdAt = saved, lastAccessedAt = saved,
        content = "リンク先の記事の全文(書き出さない)"
    )

    private fun text(id: Long, title: String, content: String) = Bookmark(
        id = id, type = BookmarkType.TEXT, title = title, content = content, createdAt = saved, lastAccessedAt = saved
    )

    private fun render(format: ExportFormat, items: List<ExportItem>, title: String = "旅行の計画", description: String? = null) =
        BookmarkExporter.render(format, title, description, items, exportedAt = saved + 1000, zone = zone)

    // ---- Markdown ----

    @Test
    fun Markdownはコレクションの名前と説明と件数を冒頭に書く() {
        val doc = render(ExportFormat.MARKDOWN, listOf(ExportItem(url(1, "記事"))), description = "夏の旅行で使う")

        assertTrue(doc.text.startsWith("# 旅行の計画\n\n夏の旅行で使う\n\n> 2026/10/1 に EchoMark から書き出し(1件)\n"))
        assertEquals(1, doc.exported)
        assertEquals(0, doc.skipped)
    }

    @Test
    fun Markdownはリンクつきの見出しにタグ_要約_出どころを書く() {
        val doc = render(
            ExportFormat.MARKDOWN,
            listOf(
                ExportItem(
                    url(
                        1, "Compose の記事", uri = "https://example.com/a", summary = "再コンポーズを減らす方法。",
                        tags = listOf("Android", "Jetpack Compose"), siteName = "Tech Blog", category = "技術", favorite = true
                    )
                )
            )
        )

        assertTrue(doc.text, doc.text.contains("## [Compose の記事](https://example.com/a)\n"))
        assertTrue(doc.text, doc.text.contains("2026/10/1 に保存 · Tech Blog · 技術 · お気に入り"))
        // タグ名の空白は「_」にして、1つのタグとして読めるようにする
        assertTrue(doc.text, doc.text.contains("#Android #Jetpack_Compose"))
        assertTrue(doc.text, doc.text.contains("再コンポーズを減らす方法。"))
    }

    @Test
    fun Markdownはリンク先の記事の全文を書かず_自分で書いたテキストは中身を書く() {
        val doc = render(
            ExportFormat.MARKDOWN,
            listOf(ExportItem(url(1, "記事")), ExportItem(text(2, "読書メモ", "情報は編集して知識になる。")))
        )

        assertFalse(doc.text.contains("リンク先の記事の全文"))
        assertTrue(doc.text, doc.text.contains("## 読書メモ\n"))
        assertTrue(doc.text, doc.text.contains("情報は編集して知識になる。"))
        assertEquals(2, doc.exported)
    }

    @Test
    fun Markdownはノートを引用つきで書く() {
        val note = BookmarkNote(1, 1, "あとで調べる", quote = "一行目\n二行目", createdAt = saved)
        val plain = BookmarkNote(2, 1, "ただのノート", createdAt = saved)

        val doc = render(ExportFormat.MARKDOWN, listOf(ExportItem(url(1, "記事"), listOf(note, plain))))

        assertTrue(doc.text, doc.text.contains("### ノート\n\n> 一行目\n> 二行目\n\nあとで調べる\n\nただのノート\n"))
    }

    @Test
    fun Markdownの見出しとリンクは記号と括弧を崩さない() {
        val doc = render(
            ExportFormat.MARKDOWN,
            listOf(ExportItem(url(1, "[重要] *強調* と_下線_\n改行", uri = "https://example.com/a b(1)")))
        )

        assertTrue(doc.text, doc.text.contains("## [\\[重要\\] \\*強調\\* と\\_下線\\_ 改行](https://example.com/a%20b%281%29)"))
    }

    // ---- ブラウザのブックマーク ----

    @Test
    fun ブラウザのブックマークはNetscape形式でフォルダの中にリンクを並べる() {
        val doc = render(
            ExportFormat.BROWSER_HTML,
            listOf(ExportItem(url(1, "記事 A", uri = "https://example.com/a", summary = "要約", tags = listOf("kotlin", "android"))))
        )

        assertTrue(doc.text.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        assertTrue(doc.text, doc.text.contains("<DT><H3>旅行の計画</H3>"))
        assertTrue(doc.text, doc.text.contains("<A HREF=\"https://example.com/a\" ADD_DATE=\"1790812800\" TAGS=\"kotlin,android\">記事 A</A>"))
        assertTrue(doc.text, doc.text.contains("<DD>要約"))
    }

    @Test
    fun ブラウザのブックマークはリンクの無いものを飛ばして数える() {
        val doc = render(
            ExportFormat.BROWSER_HTML,
            listOf(ExportItem(url(1, "記事")), ExportItem(text(2, "メモ", "本文")), ExportItem(url(3, "ファイル", uri = "content://x/1")))
        )

        assertEquals(1, doc.exported)
        assertEquals(2, doc.skipped)
        assertFalse(doc.text.contains("メモ"))
    }

    @Test
    fun ブラウザのブックマークはHTMLの記号を文字として書く() {
        val doc = render(
            ExportFormat.BROWSER_HTML,
            listOf(ExportItem(url(1, "A & B <tag> \"q\"", uri = "https://example.com/?a=1&b=2", tags = listOf("a,b", "c")))),
            title = "R&D <計画>"
        )

        assertTrue(doc.text, doc.text.contains("<H3>R&amp;D &lt;計画&gt;</H3>"))
        assertTrue(doc.text, doc.text.contains("HREF=\"https://example.com/?a=1&amp;b=2\""))
        assertTrue(doc.text, doc.text.contains(">A &amp; B &lt;tag&gt; &quot;q&quot;</A>"))
        // タグの名前に含まれるコンマは空白にして、区切りと混ざらないようにする
        assertTrue(doc.text, doc.text.contains("TAGS=\"a b,c\""))
    }

    @Test
    fun 書き出したブラウザのブックマークは取り込みの読み取りで同じリンクに戻る() {
        val items = listOf(
            ExportItem(url(1, "記事 A & B", uri = "https://example.com/a?x=1&y=2", summary = "要約の文章", tags = listOf("kotlin", "android"))),
            ExportItem(url(2, "記事 C", uri = "https://example.org/c"))
        )

        val doc = render(ExportFormat.BROWSER_HTML, items)
        val parsed = LinkImportParser.parse(doc.text)!!

        assertEquals(2, parsed.links.size)
        val first = parsed.links.first { it.url == "https://example.com/a?x=1&y=2" }
        assertEquals("記事 A & B", first.title)
        assertEquals(listOf("kotlin", "android"), first.tags)
        assertEquals(saved, first.savedAt)
        assertEquals("要約の文章", first.note)
        assertEquals("旅行の計画", first.folder)
        assertEquals("記事 C", parsed.links.first { it.url == "https://example.org/c" }.title)
    }

    // ---- ファイル名 ----

    @Test
    fun ファイル名は使えない文字を置き換え_拡張子を付ける() {
        assertEquals("旅行の計画.md", BookmarkExporter.fileName("旅行の計画", ExportFormat.MARKDOWN))
        assertEquals("a_b_c_.html", BookmarkExporter.fileName("a/b:c?", ExportFormat.BROWSER_HTML))
    }

    @Test
    fun ファイル名は空や長すぎるものを整える() {
        assertEquals("EchoMark.md", BookmarkExporter.fileName("  ", ExportFormat.MARKDOWN))
        assertEquals("EchoMark.md", BookmarkExporter.fileName("...", ExportFormat.MARKDOWN))
        val long = BookmarkExporter.fileName("あ".repeat(200), ExportFormat.MARKDOWN)
        assertEquals(60 + ".md".length, long.length)
    }
}
