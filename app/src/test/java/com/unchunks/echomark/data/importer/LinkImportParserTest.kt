package com.unchunks.echomark.data.importer

import com.unchunks.echomark.domain.importer.ImportFormat
import com.unchunks.echomark.domain.importer.MAX_IMPORT_LINKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 他のサービスの書き出しファイルの読み取り(実際の書き出しの形に近いサンプルで確かめる)。 */
class LinkImportParserTest {

    private fun parse(text: String) = checkNotNull(LinkImportParser.parse(text)) { "認識できなかった" }

    // ---- HTML ----

    private val chromeHtml = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <!-- This is an automatically generated file. -->
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><H3 ADD_DATE="1600000000" LAST_MODIFIED="1600000100" PERSONAL_TOOLBAR_FOLDER="true">ブックマーク バー</H3>
            <DL><p>
                <DT><A HREF="https://example.com/top" ADD_DATE="1600000200" ICON="data:image/png;base64,AAAA">トップのページ</A>
                <DT><H3 ADD_DATE="1600000300">読み物</H3>
                <DL><p>
                    <DT><A HREF="https://example.com/read?a=1&amp;b=2" ADD_DATE="1600000400">記事 A</A>
                    <DD>あとで読む理由のメモ
                    <DT><A HREF="https://example.com/read2" ADD_DATE="1600000500">記事 B</A>
                </DL><p>
                <DT><A HREF="javascript:alert(1)" ADD_DATE="1600000600">ブックマークレット</A>
            </DL><p>
            <DT><H3 ADD_DATE="1600000700">その他のブックマーク</H3>
            <DL><p>
                <DT><A HREF="https://example.com/other" ADD_DATE="1600000800">その他の1件</A>
            </DL><p>
        </DL><p>
    """.trimIndent()

    @Test
    fun ブラウザのHTMLはフォルダ_日付_メモを読み取る() {
        val result = parse(chromeHtml)

        assertEquals(ImportFormat.HTML, result.format)
        assertEquals(
            listOf(
                "https://example.com/top", "https://example.com/read?a=1&b=2", "https://example.com/read2", "https://example.com/other"
            ),
            result.links.map { it.url }
        )
        val byUrl = result.links.associateBy { it.url }
        // 自動で作られるフォルダ(ブックマーク バー・その他のブックマーク)はフォルダ名にしない
        assertNull(byUrl.getValue("https://example.com/top").folder)
        assertNull(byUrl.getValue("https://example.com/other").folder)
        assertEquals("読み物", byUrl.getValue("https://example.com/read2").folder)
        assertEquals("記事 A", byUrl.getValue("https://example.com/read?a=1&b=2").title)
        assertEquals("あとで読む理由のメモ", byUrl.getValue("https://example.com/read?a=1&b=2").note)
        assertNull(byUrl.getValue("https://example.com/read2").note)
        // ADD_DATE は秒
        assertEquals(1_600_000_200_000L, byUrl.getValue("https://example.com/top").savedAt)
        // javascript: のブックマークレットは読み飛ばす
        assertEquals(1, result.invalidCount)
        assertTrue(result.hasFolders)
    }

    @Test
    fun FirefoxのHTMLはタグとマイクロ秒の日付を読み取る() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
                <DT><H3 ADD_DATE="1600000000000000">ブックマーク ツールバー</H3>
                <DL><p>
                    <DT><A HREF="https://example.org/p" ADD_DATE="1600000200000000" TAGS="Kotlin, 設計">Firefox の1件</A>
                </DL><p>
            </DL>
        """.trimIndent()

        val link = parse(html).links.single()

        assertEquals(listOf("Kotlin", "設計"), link.tags)
        assertEquals(1_600_000_200_000L, link.savedAt)
        assertEquals("Firefox の1件", link.title)
    }

    @Test
    fun PocketのHTMLは未読とアーカイブの見出しで状態を分ける() {
        val html = """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><title>Pocket Export</title></head><body>
            <h1>Unread</h1>
            <ul>
              <li><a href="https://example.com/unread" time_added="1600000000" tags="web,design">未読の記事</a></li>
            </ul>
            <h1>Read Archive</h1>
            <ul>
              <li><a href="https://example.com/done" time_added="1500000000" tags="">読み終えた記事</a></li>
            </ul>
            </body></html>
        """.trimIndent()

        val links = parse(html).links.associateBy { it.url }

        assertFalse(links.getValue("https://example.com/unread").archived)
        assertEquals(listOf("web", "design"), links.getValue("https://example.com/unread").tags)
        assertEquals(1_600_000_000_000L, links.getValue("https://example.com/unread").savedAt)
        assertTrue(links.getValue("https://example.com/done").archived)
        assertTrue(links.getValue("https://example.com/done").tags.isEmpty())
    }

    @Test
    fun 同じURLは1件にまとめてタグを足し合わせる() {
        val html = """
            <DL>
              <DT><A HREF="https://example.com/a" TAGS="x">一つ目</A>
              <DT><A HREF="https://example.com/a" TAGS="y,x">二つ目</A>
            </DL>
        """.trimIndent()

        val link = parse(html).links.single()

        assertEquals("一つ目", link.title)
        assertEquals(listOf("x", "y"), link.tags)
    }

    // ---- CSV ----

    @Test
    fun RaindropのCSVはメモ_タグ_フォルダ_作成日_お気に入りを読み取る() {
        val csv = "id,title,note,excerpt,url,folder,tags,description,created,cover,highlights,favorite\n" +
            "1,\"Compose, 入門\",\"自分のメモ\n二行目\",ページの抜粋,https://example.com/compose,開発,\"android, compose\",,2023-04-05T12:34:56.000Z,,,true\n" +
            "2,無題,,,https://example.com/two,Unsorted,,,2023-05-01T00:00:00.000Z,,,false\n"

        val result = parse(csv)

        assertEquals(ImportFormat.CSV, result.format)
        val first = result.links[0]
        assertEquals("Compose, 入門", first.title)
        assertEquals("自分のメモ\n二行目", first.note)
        assertEquals(listOf("android", "compose"), first.tags)
        assertEquals("開発", first.folder)
        assertEquals(1_680_698_096_000L, first.savedAt)
        assertTrue(first.favorite)
        assertFalse(result.links[1].favorite)
        assertEquals("Unsorted", result.links[1].folder)
    }

    @Test
    fun PocketのCSVは状態がアーカイブなら読了として読む() {
        val csv = "title,url,time_added,tags,status\n" +
            "未読,https://example.com/u,1600000000,a|b,unread\n" +
            "読了,https://example.com/r,1600000100,,archive\n"

        val links = parse(csv).links

        assertEquals(listOf("a", "b"), links[0].tags)
        assertFalse(links[0].archived)
        assertTrue(links[1].archived)
        assertEquals(1_600_000_100_000L, links[1].savedAt)
    }

    @Test
    fun InstapaperのCSVはFolderを状態として読み_自分のフォルダは残す() {
        val csv = "URL,Title,Selection,Folder,Timestamp\n" +
            "https://example.com/1,未読の記事,気になった一文,Unread,1600000000\n" +
            "https://example.com/2,読了の記事,,Archive,1600000100\n" +
            "https://example.com/3,自分のフォルダ,,レシピ,1600000200\n"

        val links = parse(csv).links

        assertEquals("気になった一文", links[0].note)
        assertNull(links[0].folder)
        assertFalse(links[0].archived)
        assertTrue(links[1].archived)
        assertNull(links[1].folder)
        assertEquals("レシピ", links[2].folder)
    }

    @Test
    fun タブ区切りと先頭のBOMも読める() {
        val tsv = "﻿url\ttitle\ttags\nhttps://example.com/a\tタイトル\tx,y\n"

        val link = parse(tsv).links.single()

        assertEquals("タイトル", link.title)
        assertEquals(listOf("x", "y"), link.tags)
    }

    @Test
    fun URLの列が無いCSVは認識しない() {
        assertNull(LinkImportParser.parse("名前,年齢\n太郎,20\n"))
    }

    @Test
    fun http以外や空のURLの行は読み飛ばして数える() {
        val csv = "url,title\nhttps://example.com/ok,OK\nftp://example.com/x,FTP\n,空\nnot a url,変\nfile:///etc/passwd,ファイル\n"

        val result = parse(csv)

        assertEquals(listOf("https://example.com/ok"), result.links.map { it.url })
        assertEquals(4, result.invalidCount)
    }

    @Test
    fun 件数が上限を超えたら先頭から上限までにして知らせる() {
        val csv = buildString {
            append("url\n")
            repeat(MAX_IMPORT_LINKS + 5) { append("https://example.com/$it\n") }
        }

        val result = parse(csv)

        assertEquals(MAX_IMPORT_LINKS, result.links.size)
        assertTrue(result.truncated)
        assertEquals("https://example.com/0", result.links.first().url)
    }

    // ---- JSON ----

    @Test
    fun PinboardのJSONはdescriptionがタイトルでextendedがメモ_タグは空白区切り() {
        val json = """
            [
              {"href":"https://example.com/p","description":"Pinboard のタイトル","extended":"メモ","meta":"abc","hash":"def",
               "time":"2023-04-05T12:34:56Z","shared":"no","toread":"yes","tags":"kotlin android  設計"}
            ]
        """.trimIndent()

        val result = parse(json)

        assertEquals(ImportFormat.JSON, result.format)
        val link = result.links.single()
        assertEquals("Pinboard のタイトル", link.title)
        assertEquals("メモ", link.note)
        assertEquals(listOf("kotlin", "android", "設計"), link.tags)
        assertEquals(1_680_698_096_000L, link.savedAt)
    }

    @Test
    fun 一般的なJSONはurlとtitleを持つ要素の配列を読む() {
        val json = """{"bookmarks":[{"url":"https://example.com/g","title":"一般","tags":["a","b"],"created_at":"1600000000","starred":"true"}]}"""

        val link = parse(json).links.single()

        assertEquals("一般", link.title)
        assertEquals(listOf("a", "b"), link.tags)
        assertEquals(1_600_000_000_000L, link.savedAt)
        assertTrue(link.favorite)
    }

    @Test
    fun 壊れたJSONや配列の無いJSONは認識しない() {
        assertNull(LinkImportParser.parse("[{\"href\":"))
        assertNull(LinkImportParser.parse("{\"name\":\"x\"}"))
    }

    // ---- 認識できないもの ----

    @Test
    fun 空や認識できない文章は認識しない() {
        assertNull(LinkImportParser.parse(""))
        assertNull(LinkImportParser.parse("   \n"))
        assertNull(LinkImportParser.parse("ただの文章です。"))
    }

    @Test
    fun HTMLにリンクが無ければ空の結果になる() {
        val result = LinkImportParser.parse("<html><body><p>リンクはありません</p></body></html>")

        assertNotNull(result)
        assertTrue(result!!.links.isEmpty())
    }

    // ---- 部品 ----

    @Test
    fun 日時は秒_ミリ秒_マイクロ秒_ISOを見分ける() {
        assertEquals(1_600_000_000_000L, LinkImportParser.parseTime("1600000000"))
        assertEquals(1_600_000_000_000L, LinkImportParser.parseTime("1600000000000"))
        assertEquals(1_600_000_000_000L, LinkImportParser.parseTime("1600000000000000"))
        assertEquals(1_680_698_096_000L, LinkImportParser.parseTime("2023-04-05T12:34:56Z"))
        assertEquals(1_680_698_096_000L, LinkImportParser.parseTime("2023-04-05T21:34:56+09:00"))
        assertEquals(1_680_698_096_000L, LinkImportParser.parseTime("2023-04-05 12:34:56"))
        assertNull(LinkImportParser.parseTime("0"))
        assertNull(LinkImportParser.parseTime("昨日"))
        assertNull(LinkImportParser.parseTime(null))
    }

    @Test
    fun タグはカンマ_縦棒_セミコロンで区切り_先頭のシャープと重複と長すぎるものを整える() {
        // タグの中の空白は残し、前後の空白は cleanTags が除く
        assertEquals(listOf("a b", "c", "d", "  e"), LinkImportParser.splitTags("a b,c|d;  e"))
        assertEquals(listOf("a b", "c", "d", "e"), LinkImportParser.cleanTags(LinkImportParser.splitTags("a b,c|d;  e")))
        assertEquals(listOf("a", "b", "c"), LinkImportParser.splitTags("a  b\tc", spaceSeparated = true))
        assertEquals(listOf("x", "y"), LinkImportParser.cleanTags(listOf("#x", " y ", "x", "", "#")))
        assertEquals(40, LinkImportParser.cleanTags(listOf("あ".repeat(100))).single().length)
        assertEquals(10, LinkImportParser.cleanTags((1..20).map { "t$it" }).size)
    }

    @Test
    fun URLはhttpとhttpsだけで空白を含まない() {
        assertEquals("https://example.com/a", LinkImportParser.normalizeImportUrl("  https://example.com/a "))
        assertNull(LinkImportParser.normalizeImportUrl("https://exa mple.com"))
        assertNull(LinkImportParser.normalizeImportUrl("https://"))
        assertNull(LinkImportParser.normalizeImportUrl("mailto:a@example.com"))
        assertNull(LinkImportParser.normalizeImportUrl("https://example.com/" + "a".repeat(2100)))
    }

    @Test
    fun 自動で作られるフォルダの名前を見分ける() {
        assertTrue(LinkImportParser.isGenericFolder("Bookmarks bar"))
        assertTrue(LinkImportParser.isGenericFolder(" ブックマーク バー "))
        assertTrue(LinkImportParser.isGenericFolder("Other Bookmarks"))
        assertFalse(LinkImportParser.isGenericFolder("レシピ"))
    }
}
