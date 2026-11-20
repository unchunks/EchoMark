package com.unchunks.echomark.data.importer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.importer.ImportFormat
import com.unchunks.echomark.domain.importer.LinkImportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** 選んだファイルの読み込み(Robolectric)。 */
@RunWith(AndroidJUnit4::class)
class ContentResolverLinkImportSourceTest {

    private lateinit var context: Context
    private lateinit var source: ContentResolverLinkImportSource

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = ContentResolverLinkImportSource(context, TestDispatcherProvider(Dispatchers.Unconfined))
    }

    private fun register(uri: Uri, bytes: ByteArray) {
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
    }

    @Test
    fun ファイルを読んでリンクにする() = runBlocking {
        val uri = Uri.parse("content://com.example.files/document/pocket.csv")
        register(uri, "title,url,time_added,tags,status\n記事,https://example.com/a,1600000000,x,unread\n".toByteArray())

        val result = source.read(uri.toString())

        assertEquals(ImportFormat.CSV, result.format)
        assertEquals("https://example.com/a", result.links.single().url)
    }

    @Test
    fun 対応していない形式は理由つきの例外にする() = runBlocking {
        val uri = Uri.parse("content://com.example.files/document/notes.txt")
        register(uri, "ただのメモです。".toByteArray())

        val error = runCatching { source.read(uri.toString()) }.exceptionOrNull()

        assertTrue(error is LinkImportException)
        assertTrue(error!!.message!!.contains("対応していません"))
    }

    @Test
    fun 開けないファイルは例外にする() = runBlocking {
        val error = runCatching { source.read("content://com.example.files/document/missing.csv") }.exceptionOrNull()

        assertTrue(error is LinkImportException)
    }

    @Test
    fun 上限を超える大きさは読む前に打ち切る() {
        val bytes = ByteArray(2048) { 'a'.code.toByte() }

        val error = runCatching { readLimited(bytes.inputStream(), maxBytes = 1024) }.exceptionOrNull()

        assertTrue(error is LinkImportException)
        assertTrue(error!!.message!!.contains("大きすぎます"))
    }

    @Test
    fun 上限ちょうどまでは読める() {
        val text = readLimited("あいう".toByteArray().inputStream(), maxBytes = 9)

        assertEquals("あいう", text)
    }
}
