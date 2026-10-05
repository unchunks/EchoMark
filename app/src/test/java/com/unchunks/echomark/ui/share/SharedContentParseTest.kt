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
}
