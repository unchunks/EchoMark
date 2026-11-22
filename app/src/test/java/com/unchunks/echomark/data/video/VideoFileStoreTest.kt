package com.unchunks.echomark.data.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.ui.video.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 動画のファイルの置き場所(Robolectric) */
@RunWith(AndroidJUnit4::class)
class VideoFileStoreTest {

    private lateinit var context: Context
    private lateinit var store: VideoFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = VideoFileStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @Test
    fun 落とし終えたファイルを置き場所に移して相対パスを返す() {
        val part = store.partFile(7).apply { writeBytes(ByteArray(10)) }

        val path = store.adopt(part, 7, "video/webm")

        assertTrue(path, path.matches(Regex("offline_videos/7-[0-9a-f-]{36}\\.webm")))
        assertFalse(part.exists())
        assertEquals(10L, store.existingFile(path)?.length())
    }

    @Test
    fun 置き場所の外を指すパスは受け付けない() {
        assertNull(store.fileOf("../databases/echomark.db"))
        assertNull(store.fileOf("offline_videos/../shared_prefs/a.xml"))
        assertNull(store.fileOf("attachments/a.mp4"))
        assertNull(store.fileOf("offline_videos/"))
        assertNull(store.fileOf(null))
    }

    @Test
    fun 再生できるのは動画と添付ファイルの置き場所の中のファイルだけ() {
        val video = File(store.directory, "1-a.mp4").apply { writeBytes(ByteArray(1)) }
        val attachment = File(File(context.filesDir, "attachments").apply { mkdirs() }, "b.mp4").apply { writeBytes(ByteArray(1)) }

        assertEquals(video, VideoPlayerActivity.playableFile(context.filesDir, "offline_videos/1-a.mp4"))
        assertEquals(attachment, VideoPlayerActivity.playableFile(context.filesDir, "attachments/b.mp4"))
        assertNull(VideoPlayerActivity.playableFile(context.filesDir, "offline_videos/missing.mp4"))
        assertNull(VideoPlayerActivity.playableFile(context.filesDir, "../databases/echomark.db"))
    }

    @Test
    fun 参照されていないファイルと止まったままの途中のファイルを消す() = runBlocking {
        val now = 10 * DAY
        val referenced = File(store.directory, "1-kept.mp4").apply { writeBytes(ByteArray(1)); setLastModified(now - 2 * DAY) }
        val orphan = File(store.directory, "2-orphan.mp4").apply { writeBytes(ByteArray(1)); setLastModified(now - 2 * DAY) }
        val recent = File(store.directory, "3-recent.mp4").apply { writeBytes(ByteArray(1)); setLastModified(now) }
        val activePart = store.partFile(1).apply { writeBytes(ByteArray(1)); setLastModified(now - DAY) }
        val stalePart = store.partFile(4).apply { writeBytes(ByteArray(1)); setLastModified(now - 8 * DAY) }
        val deletedBookmarkPart = store.partFile(5).apply { writeBytes(ByteArray(1)); setLastModified(now) }

        val count = store.deleteUnreferenced(
            referenced = setOf("offline_videos/1-kept.mp4"),
            existingBookmarkIds = setOf(1L, 4L),
            olderThanMillis = now - DAY,
            partOlderThanMillis = now - 7 * DAY
        )

        assertEquals(3, count)
        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        assertTrue(recent.exists())
        assertTrue(activePart.exists())
        assertFalse(stalePart.exists())
        assertFalse(deletedBookmarkPart.exists())
    }

    @Test
    fun MIMEタイプから拡張子を決める() {
        assertEquals("mp4", VideoFileStore.extensionFor("video/mp4"))
        assertEquals("webm", VideoFileStore.extensionFor("video/webm; codecs=vp9"))
        assertEquals("mov", VideoFileStore.extensionFor("video/quicktime"))
        assertEquals("mp4", VideoFileStore.extensionFor("video/x-unknown"))
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
