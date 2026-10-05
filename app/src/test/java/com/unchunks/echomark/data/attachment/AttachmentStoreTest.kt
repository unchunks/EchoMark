package com.unchunks.echomark.data.attachment

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.domain.bookmark.model.AttachmentError
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/** 添付ファイルの取り込み・保存先・掃除のテスト(Robolectric)。 */
@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {

    private lateinit var context: Context
    private lateinit var store: AttachmentStore

    private val attachmentsDir: File get() = File(context.filesDir, "attachments")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
    }

    private fun registerContent(uri: Uri, bytes: ByteArray) {
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
    }

    @Test
    fun URIのファイルをアプリ内にコピーしてファイル名と種類とサイズを返す() = runBlocking {
        val uri = Uri.parse("content://com.example.files/document/会議資料.pdf")
        registerContent(uri, ByteArray(1500) { 7 })

        val stored = store.importFromUri(uri)

        assertTrue(stored.filePath, stored.filePath.matches(Regex("attachments/[0-9a-f-]{36}\\.pdf")))
        assertEquals("application/pdf", stored.mimeType)
        assertEquals("会議資料.pdf", stored.fileName)
        assertEquals(1500L, stored.fileSize)
        val file = File(context.filesDir, stored.filePath)
        assertEquals(1500L, file.length())
        // 書き込み途中のファイルは残さない
        assertTrue(attachmentsDir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun 上限を超えるファイルはエラーにして途中のファイルを残さない() = runBlocking {
        val uri = Uri.parse("content://com.example.files/document/big.png")
        registerContent(uri, ByteArray(2048))

        val error = runCatching { store.importFromUri(uri, maxBytes = 1024) }.exceptionOrNull()

        assertEquals(AttachmentError.TooLarge(1024), (error as AttachmentException).error)
        assertTrue(attachmentsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun 空のファイルはエラーにする() = runBlocking {
        val uri = Uri.parse("content://com.example.files/document/empty.mp3")
        registerContent(uri, ByteArray(0))

        val error = runCatching { store.importFromUri(uri) }.exceptionOrNull()

        assertEquals(AttachmentError.Empty, (error as AttachmentException).error)
        assertTrue(attachmentsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun 対応していない形式はエラーにする() = runBlocking {
        val uri = Uri.parse("content://com.example.files/document/archive.zip")
        registerContent(uri, ByteArray(10))

        val error = runCatching { store.importFromUri(uri) }.exceptionOrNull()

        assertTrue((error as AttachmentException).error is AttachmentError.Unsupported)
    }

    @Test
    fun 取り消されたらコピーを止めて途中のファイルを消す() {
        var reads = 0
        val input = object : java.io.InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return len
            }
        }
        try {
            store.saveStream(input, "video/mp4", "clip.mp4", maxBytes = Long.MAX_VALUE) { reads < 3 }
            fail("取り消されるはず")
        } catch (e: CancellationException) {
            // 期待どおり
        }
        assertTrue(attachmentsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun 保存先の外を指すパスは扱わない() {
        assertNull(store.fileOf("../databases/echomark.db"))
        assertNull(store.fileOf("attachments/../../shared_prefs/a.xml"))
        assertNull(store.fileOf("models/model.task"))
        assertNull(store.fileOf(null))
        assertEquals(File(attachmentsDir, "abc.pdf"), store.fileOf("attachments/abc.pdf"))
    }

    @Test
    fun テキストファイルの中身を先頭から読む() = runBlocking {
        val stored = store.saveStream("﻿こんにちは世界".toByteArray().inputStream(), "text/plain", "hello.txt", 1024)

        assertEquals("こんにちは", store.readText(stored.filePath, maxChars = 5))
    }

    @Test
    fun 参照されていない古いファイルだけを掃除する() = runBlocking {
        val now = 10_000_000_000L
        fun save(name: String, modified: Long): String {
            val stored = store.saveStream(byteArrayOf(1).inputStream(), "image/png", name, 1024)
            File(context.filesDir, stored.filePath).setLastModified(modified)
            return stored.filePath
        }
        val referencedOld = save("a.png", now - 10 * DAY)
        val unreferencedOld = save("b.png", now - 2 * DAY)
        val unreferencedNew = save("c.png", now - DAY / 2)
        val leftoverPart = File(attachmentsDir, "x.png.part").apply {
            writeText("途中")
            setLastModified(now - 2 * DAY)
        }

        val deleted = store.deleteUnreferenced(setOf(referencedOld), olderThanMillis = now - DAY)

        assertEquals(2, deleted)
        assertTrue(File(context.filesDir, referencedOld).isFile)
        assertFalse(File(context.filesDir, unreferencedOld).exists())
        assertTrue(File(context.filesDir, unreferencedNew).isFile)
        assertFalse(leftoverPart.exists())
    }

    @Test
    fun 起動時の掃除はブックマークが参照しているファイルを残す() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val kept = store.saveStream(byteArrayOf(1).inputStream(), "image/png", "kept.png", 1024)
            val orphan = store.saveStream(byteArrayOf(1).inputStream(), "image/png", "orphan.png", 1024)
            listOf(kept, orphan).forEach { File(context.filesDir, it.filePath).setLastModified(1_000L) }
            db.bookmarkDao().insert(
                BookmarkEntity(
                    type = BookmarkType.IMAGE, title = "kept", createdAt = 1L, lastAccessedAt = 1L,
                    filePath = kept.filePath, mimeType = kept.mimeType
                )
            )
            val cleaner = UnusedAttachmentCleaner({ store }, { db.bookmarkDao() }, TestDispatcherProvider(Dispatchers.Unconfined))

            assertEquals(1, cleaner.cleanUp(now = 1_000L + 2 * DAY))

            assertTrue(File(context.filesDir, kept.filePath).isFile)
            assertFalse(File(context.filesDir, orphan.filePath).exists())
        } finally {
            db.close()
        }
    }

    @Test
    fun 合計サイズと全削除() = runBlocking {
        store.saveStream(ByteArray(100).inputStream(), "image/png", "a.png", 1024)
        store.saveStream(ByteArray(50).inputStream(), "audio/mpeg", "b.mp3", 1024)

        assertEquals(150L, store.totalBytes())
        store.deleteAll()
        assertEquals(0L, store.totalBytes())
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
