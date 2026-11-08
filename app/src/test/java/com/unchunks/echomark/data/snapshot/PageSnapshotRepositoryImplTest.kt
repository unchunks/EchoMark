package com.unchunks.echomark.data.snapshot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** ページのオフラインコピーの保存・差し替え・削除(Robolectric。DB と保存先は本物)。 */
@RunWith(AndroidJUnit4::class)
class PageSnapshotRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: BookmarkDao
    private lateinit var repository: PageSnapshotRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.bookmarkDao()
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        repository = PageSnapshotRepositoryImpl(AttachmentStore(context, dispatchers), dao, dispatchers)
        runBlocking {
            dao.insert(
                BookmarkEntity(
                    id = 1, type = BookmarkType.URL, title = "記事", contentUri = "https://example.com/a",
                    createdAt = 1L, lastAccessedAt = 1L
                )
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun temp(name: String, text: String) = File(context.cacheDir, name).apply { writeText(text) }

    private fun fileAt(path: String?) = File(context.filesDir, checkNotNull(path))

    @Test
    fun コピーを添付ファイルの置き場所に移してブックマークに結びつける() = runBlocking {
        val mhtml = temp("a.mhtml", "MIME")
        val pdf = temp("a.pdf", "%PDF")

        val stored = repository.save(1, mhtml, pdf)

        assertTrue(stored.mhtmlPath, stored.mhtmlPath.matches(Regex("attachments/[0-9a-f-]{36}\\.mhtml")))
        assertTrue(stored.pdfPath!!.endsWith(".pdf"))
        assertEquals("MIME", fileAt(stored.mhtmlPath).readText())
        assertEquals("%PDF", fileAt(stored.pdfPath).readText())
        assertFalse(mhtml.exists())
        assertFalse(pdf.exists())
        val row = dao.getById(1)!!
        assertEquals(stored.mhtmlPath, row.snapshotPath)
        assertEquals(stored.pdfPath, row.snapshotPdfPath)
        assertEquals(stored.savedAt, row.snapshotAt)
        assertNotNull(repository.fileOf(stored.mhtmlPath))
    }

    @Test
    fun PDFが無いコピーも保存できる() = runBlocking {
        val stored = repository.save(1, temp("a.mhtml", "MIME"), null)

        assertNull(stored.pdfPath)
        assertNull(dao.getById(1)!!.snapshotPdfPath)
    }

    @Test
    fun 保存し直すと前のコピーのファイルを消す() = runBlocking {
        val first = repository.save(1, temp("a.mhtml", "古い"), temp("a.pdf", "古い"))

        val second = repository.save(1, temp("b.mhtml", "新しい"), null)

        assertFalse(fileAt(first.mhtmlPath).exists())
        assertFalse(fileAt(first.pdfPath).exists())
        assertEquals("新しい", fileAt(second.mhtmlPath).readText())
        val row = dao.getById(1)!!
        assertEquals(second.mhtmlPath, row.snapshotPath)
        assertNull(row.snapshotPdfPath)
    }

    @Test
    fun コピーを消すとファイルも記録も無くなる() = runBlocking {
        val stored = repository.save(1, temp("a.mhtml", "MIME"), temp("a.pdf", "%PDF"))

        repository.remove(1)

        assertFalse(fileAt(stored.mhtmlPath).exists())
        assertFalse(fileAt(stored.pdfPath).exists())
        val row = dao.getById(1)!!
        assertNull(row.snapshotPath)
        assertNull(row.snapshotPdfPath)
        assertNull(row.snapshotAt)
        assertTrue(dao.getAllSnapshotPaths().isEmpty())
    }

    @Test
    fun 無いブックマークへの保存は失敗しファイルを取り込まない() = runBlocking {
        val mhtml = temp("a.mhtml", "MIME")

        val error = runCatching { repository.save(99, mhtml, null) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(File(context.filesDir, "attachments").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun 無いブックマークのコピーを消しても何も起きない() = runBlocking {
        repository.remove(99)
    }

    @Test
    fun 保存先でないパスのファイルは返さない() {
        assertNull(repository.fileOf(null))
        assertNull(repository.fileOf("../databases/echomark.db"))
        assertNull(repository.fileOf("attachments/missing.mhtml"))
    }
}
