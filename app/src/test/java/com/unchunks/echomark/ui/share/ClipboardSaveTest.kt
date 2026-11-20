package com.unchunks.echomark.ui.share

import com.unchunks.echomark.ui.bookmark.ClipboardContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** タイルから保存するクリップボードの中身 → 共有シートの内容。 */
class ClipboardSaveTest {

    @Test
    fun URLはリンクとして題名があれば仮タイトルにする() {
        val shared = ClipboardContent.Url("https://example.com/a", "面白い記事").toSharedContent()!!

        assertEquals("https://example.com/a", shared.url)
        assertEquals("面白い記事", shared.tentativeTitle)
    }

    @Test
    fun 題名の無いURLは仮タイトルがURLになる() {
        val shared = ClipboardContent.Url("https://example.com/a").toSharedContent()!!

        assertEquals("https://example.com/a", shared.tentativeTitle)
    }

    @Test
    fun テキストはメモとして最初の行を仮タイトルにする() {
        val shared = ClipboardContent.Text("買うもの\n牛乳と卵").toSharedContent()!!

        assertNull(shared.url)
        assertEquals("買うもの\n牛乳と卵", shared.text)
        assertEquals("買うもの", shared.tentativeTitle)
        assertTrue(shared.entries.isEmpty())
    }

    @Test
    fun URLだけが並んだテキストは1件ずつのリンクにする() {
        val shared = ClipboardContent.Text("https://example.com/a\nhttps://example.com/b").toSharedContent()!!

        assertTrue(shared.hasMultipleEntries)
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), shared.entries.map { it.url })
    }

    @Test
    fun 画像はファイルとして保存する() {
        val shared = ClipboardContent.Image("content://clip/1", "image/png").toSharedContent()!!

        assertEquals("content://clip/1", shared.files.single().uri)
        assertEquals("image/png", shared.files.single().mimeType)
    }

    @Test
    fun 何も無いときは保存する内容が無い() {
        assertNull(ClipboardContent.Empty.toSharedContent())
    }
}
