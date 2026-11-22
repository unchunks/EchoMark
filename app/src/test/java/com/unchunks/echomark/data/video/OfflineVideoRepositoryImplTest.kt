package com.unchunks.echomark.data.video

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.video.SavedVideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 動画のオフライン保存の記録とサムネイルのファイル(Robolectric・メモリ上の DB) */
@RunWith(AndroidJUnit4::class)
class OfflineVideoRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: AttachmentStore
    private lateinit var repository: OfflineVideoRepositoryImpl
    private lateinit var videos: VideoFileStore
    private var bookmarkId = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        store = AttachmentStore(context, dispatchers)
        videos = VideoFileStore(context, dispatchers)
        repository = OfflineVideoRepositoryImpl(db.offlineVideoDao(), store, videos, dispatchers)
        bookmarkId = db.bookmarkDao().insert(
            BookmarkEntity(
                type = BookmarkType.URL, title = "動画", createdAt = 1L, lastAccessedAt = 1L,
                contentUri = "https://www.youtube.com/watch?v=abcdefghijk"
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun thumbnail(name: String): String =
        store.saveStream(byteArrayOf(1, 2, 3).inputStream(), "image/jpeg", name, 1024).filePath

    private fun exists(path: String) = File(context.filesDir, path).isFile

    @Test
    fun サムネイルを結びつけて保存した記録にする() = runBlocking {
        val first = thumbnail("a.jpg")

        assertTrue(repository.markSaved(bookmarkId, first, savedAt = 10L))

        val saved = repository.observe(bookmarkId).first()!!
        assertEquals(first, saved.thumbnailPath)
        assertEquals(10L, saved.savedAt)
        assertEquals(File(context.filesDir, first), repository.fileOf(first))
    }

    @Test
    fun 保存し直すと前のサムネイルを消す() = runBlocking {
        val first = thumbnail("a.jpg")
        val second = thumbnail("b.jpg")
        repository.markSaved(bookmarkId, first, savedAt = 10L)

        repository.markSaved(bookmarkId, second, savedAt = 20L)

        assertEquals(second, repository.get(bookmarkId)?.thumbnailPath)
        assertFalse(exists(first))
        assertTrue(exists(second))
    }

    @Test
    fun サムネイルを保存できなかったときは前のものを残す() = runBlocking {
        val first = thumbnail("a.jpg")
        repository.markSaved(bookmarkId, first, savedAt = 10L)

        repository.markSaved(bookmarkId, null, savedAt = 20L)

        val saved = repository.get(bookmarkId)!!
        assertEquals(first, saved.thumbnailPath)
        assertEquals(20L, saved.savedAt)
        assertTrue(exists(first))
    }

    @Test
    fun 動画のファイルの記録には触れない() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO offline_videos (bookmarkId, savedAt, videoPath, videoSize) VALUES ($bookmarkId, 1, 'offline_videos/a.mp4', 99)"
        )

        repository.markSaved(bookmarkId, thumbnail("a.jpg"), savedAt = 20L)

        val row = db.offlineVideoDao().get(bookmarkId)!!
        assertEquals("offline_videos/a.mp4", row.videoPath)
        assertEquals(99L, row.videoSize)
    }

    @Test
    fun ブックマークが消えていたら記録せずにサムネイルを消す() = runBlocking {
        val orphan = thumbnail("a.jpg")

        assertFalse(repository.markSaved(bookmarkId + 100, orphan, savedAt = 10L))

        assertNull(repository.get(bookmarkId + 100))
        assertFalse(exists(orphan))
    }

    private fun videoFile(name: String, bytes: Int = 100): String {
        val part = File(videos.directory, "$name.part").apply { writeBytes(ByteArray(bytes)) }
        return videos.adopt(part, bookmarkId, "video/mp4")
    }

    private fun saved(path: String, size: Long, savedAt: Long = 30L) =
        SavedVideoFile(path = path, mimeType = "video/mp4", size = size, sourceUrl = "https://example.com/a.mp4", label = "720p", savedAt = savedAt)

    @Test
    fun 動画のファイルを結びつけて一覧と合計に出す() = runBlocking {
        val path = videoFile("a")

        assertTrue(repository.saveVideo(bookmarkId, saved(path, 100)))

        val video = repository.get(bookmarkId)!!.video!!
        assertEquals(path, video.path)
        assertEquals("720p", video.label)
        assertEquals(100L, repository.observeVideoBytes().first())
        assertEquals(0L, repository.totalVideoBytes(excludingBookmarkId = bookmarkId))
        val item = repository.observeSavedVideos().first().single()
        assertEquals("動画", item.title)
        assertEquals(path, item.path)
        assertEquals(File(context.filesDir, path), repository.videoFileOf(path))
    }

    @Test
    fun 保存し直すと前の動画のファイルを消し_サムネイルは残す() = runBlocking {
        val thumb = thumbnail("a.jpg")
        repository.markSaved(bookmarkId, thumb, savedAt = 10L)
        val first = videoFile("a")
        val second = videoFile("b")
        repository.saveVideo(bookmarkId, saved(first, 100))

        repository.saveVideo(bookmarkId, saved(second, 200))

        assertFalse(exists(first))
        assertTrue(exists(second))
        assertEquals(thumb, repository.get(bookmarkId)?.thumbnailPath)
    }

    @Test
    fun 動画だけを消すとサムネイルの記録は残る() = runBlocking {
        val thumb = thumbnail("a.jpg")
        repository.markSaved(bookmarkId, thumb, savedAt = 10L)
        val path = videoFile("a")
        repository.saveVideo(bookmarkId, saved(path, 100))
        val part = videos.partFile(bookmarkId).apply { writeBytes(ByteArray(5)) }

        repository.removeVideo(bookmarkId)

        assertNull(repository.get(bookmarkId)?.video)
        assertEquals(thumb, repository.get(bookmarkId)?.thumbnailPath)
        assertFalse(exists(path))
        assertFalse(part.exists())
    }

    @Test
    fun 途中のファイルだけを消すと保存した動画は残る() = runBlocking {
        val path = videoFile("a")
        repository.saveVideo(bookmarkId, saved(path, 100))
        val part = videos.partFile(bookmarkId).apply { writeBytes(ByteArray(5)) }

        repository.discardPartialVideo(bookmarkId)

        assertFalse(part.exists())
        assertTrue(exists(path))
    }

    @Test
    fun すべての動画を消す() = runBlocking {
        val path = videoFile("a")
        repository.saveVideo(bookmarkId, saved(path, 100))

        repository.removeAllVideos()

        assertNull(repository.get(bookmarkId)?.video)
        assertFalse(exists(path))
        assertEquals(0L, repository.observeVideoBytes().first())
    }

    @Test
    fun ブックマークが消えていたら動画を記録せずにファイルを消す() = runBlocking {
        val path = videoFile("a")

        assertFalse(repository.saveVideo(bookmarkId + 100, saved(path, 100)))

        assertFalse(exists(path))
    }

    @Test
    fun 削除すると記録とサムネイルを消す() = runBlocking {
        val first = thumbnail("a.jpg")
        repository.markSaved(bookmarkId, first, savedAt = 10L)

        repository.remove(bookmarkId)

        assertNull(repository.get(bookmarkId))
        assertFalse(exists(first))
        // ブックマークは残る
        assertTrue(db.bookmarkDao().getById(bookmarkId) != null)
    }
}
