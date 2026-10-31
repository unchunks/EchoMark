package com.unchunks.echomark.ui.share

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 共有インテントからの取り出し(Robolectric)。 */
@RunWith(AndroidJUnit4::class)
class SharedContentParseTest {

    @Test
    fun 画像の共有はファイルとして受け取り添えられた文章も残す() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/1"))
            putExtra(Intent.EXTRA_TEXT, "きれいな夕焼け")
        }

        val shared = parseSharedContent(intent)!!

        assertEquals(listOf("content://media/external/images/1"), shared.files.map { it.uri })
        assertEquals("image/jpeg", shared.files.single().mimeType)
        assertEquals("きれいな夕焼け", shared.text)
        assertNull(shared.url)
    }

    @Test
    fun 複数の共有はすべてのファイルを受け取る() {
        val uris = arrayListOf(Uri.parse("content://p/1"), Uri.parse("content://p/2"), Uri.parse("content://p/1"))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        val shared = parseSharedContent(intent)!!

        assertEquals(listOf("content://p/1", "content://p/2"), shared.files.map { it.uri })
    }

    @Test
    fun テキストの共有は今までどおりURLやメモとして受け取る() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "記事 https://example.com/a")
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://p/preview.png"))
        }

        val shared = parseSharedContent(intent)!!

        assertTrue(shared.files.isEmpty())
        assertEquals("https://example.com/a", shared.url)
    }

    @Test
    fun テキストファイルだけの共有はファイルとして受け取る() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://p/notes.txt"))
        }

        assertEquals(1, parseSharedContent(intent)!!.files.size)
    }

    @Test
    fun fileスキームのファイルは受け取らない() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/com.unchunks.echomark/databases/echomark.db"))
        }

        assertNull(parseSharedContent(intent))
    }

    @Test
    fun 複数のテキストの共有は1件ずつのURLとメモとして受け取る() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putCharSequenceArrayListExtra(
                Intent.EXTRA_TEXT,
                arrayListOf("記事 https://example.com/a", "  ", "メモの文章\n二行目", "記事 https://example.com/a")
            )
        }

        val shared = parseSharedContent(intent)!!

        assertTrue(shared.hasMultipleEntries)
        assertTrue(shared.files.isEmpty())
        assertEquals(2, shared.entries.size)
        assertEquals("https://example.com/a", shared.entries[0].url)
        assertEquals("記事", shared.entries[0].title)
        assertNull(shared.entries[1].url)
        assertEquals("メモの文章\n二行目", shared.entries[1].text)
        assertEquals("メモの文章", shared.entries[1].title)
    }

    @Test
    fun 複数共有でも1件だけならふつうのテキストの共有と同じに扱う() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf("https://example.com/only"))
        }

        val shared = parseSharedContent(intent)!!

        assertTrue(!shared.hasMultipleEntries)
        assertEquals("https://example.com/only", shared.url)
        assertTrue(shared.entries.isEmpty())
    }

    @Test
    fun 複数共有でテキストもファイルも無ければ受け取らない() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf("", "  "))
        }

        assertNull(parseSharedContent(intent))
    }

    @Test
    fun URLだけが並んだテキストは1件ずつのリンクとして受け取る() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/a\n\nhttps://example.com/b\nhttps://example.com/a")
        }

        val shared = parseSharedContent(intent)!!

        assertEquals(listOf("https://example.com/a", "https://example.com/b"), shared.entries.map { it.url })
    }

    @Test
    fun URLと文章が混ざったテキストは1件のメモとして受け取る() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "この記事がおすすめ\nhttps://example.com/a\nhttps://example.com/b")
        }

        val shared = parseSharedContent(intent)!!

        assertTrue(!shared.hasMultipleEntries)
        assertEquals("https://example.com/a", shared.url)
    }

    @Test
    fun URLだけの行の判定() {
        assertEquals(listOf("https://a.example", "https://b.example"), urlOnlyLines("https://a.example\n  https://b.example  "))
        assertTrue(urlOnlyLines("https://a.example").isEmpty())
        assertTrue(urlOnlyLines("https://a.example\n説明").isEmpty())
        assertTrue(urlOnlyLines("").isEmpty())
    }

    @Test
    fun 選んだ文字のメニューからは引用として受け取る() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "  一行目の文章\n\n二行目の文章  ")
        }

        val shared = parseSharedContent(intent)!!

        assertTrue(shared.isQuote)
        assertEquals("> 一行目の文章\n>\n> 二行目の文章", shared.text)
        assertEquals("一行目の文章", shared.tentativeTitle)
        assertNull(shared.url)
        assertTrue(shared.files.isEmpty())
    }

    @Test
    fun 選んだ文字が空なら引用として受け取らない() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "   ")
        }

        assertNull(parseSharedContent(intent))
        assertNull(parseSharedContent(Intent(Intent.ACTION_PROCESS_TEXT)))
    }

    @Test
    fun 長い引用の仮タイトルは先頭の80文字まで() {
        val shared = quoteContentOf("あ".repeat(200))!!

        assertEquals(80, shared.tentativeTitle.length)
    }

    @Test
    fun 引用の各行の頭に引用の記号を付ける() {
        assertEquals("> a\n> b", asQuote("a\nb"))
        assertEquals("> a\n>\n> b", asQuote("a\n  \nb"))
        assertEquals("> a", asQuote("a   "))
    }
}
