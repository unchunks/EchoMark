package com.unchunks.echomark.domain.video

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.contentKindOfUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 動画のファイルを保存できるブックマークか(ダウンロードが認められたものだけ) */
class VideoFileSourceKindTest {

    private fun bookmark(url: String, filePath: String? = null, mimeType: String? = null) =
        Bookmark(type = BookmarkType.URL, contentUri = url, title = "t", createdAt = 0, lastAccessedAt = 0, filePath = filePath, mimeType = mimeType)

    @Test
    fun 動画のファイルへの直接のリンクは保存できる() {
        assertEquals(VideoFileSourceKind.DIRECT_FILE, bookmark("https://example.com/media/clip.mp4").videoFileSourceKind())
        assertEquals(VideoFileSourceKind.DIRECT_FILE, bookmark("https://example.com/a.WEBM?token=1").videoFileSourceKind())
        assertEquals(ContentKind.VIDEO, contentKindOfUrl("https://example.com/media/clip.mp4"))
    }

    @Test
    fun PeerTubeの動画は保存できるか確かめる対象() {
        assertEquals(VideoFileSourceKind.PEERTUBE, bookmark("https://peertube.example/w/9c9de5e8xQNXhHR4Fvu8Qa").videoFileSourceKind())
    }

    @Test
    fun YouTubeなどの動画サイトのページは保存しない() {
        assertNull(bookmark("https://www.youtube.com/watch?v=abcdefghijk").videoFileSourceKind())
        assertNull(bookmark("https://youtu.be/abcdefghijk").videoFileSourceKind())
        assertNull(bookmark("https://vimeo.com/123456").videoFileSourceKind())
        assertNull(bookmark("https://www.nicovideo.jp/watch/sm9").videoFileSourceKind())
    }

    @Test
    fun 添付ファイルとして保存済みのものと動画でないリンクは対象でない() {
        assertNull(bookmark("https://example.com/a.mp4", filePath = "attachments/a.mp4", mimeType = "video/mp4").videoFileSourceKind())
        assertNull(bookmark("https://example.com/article").videoFileSourceKind())
        assertFalse(isDirectVideoUrl("https://example.com/mp4"))
        assertFalse(isDirectVideoUrl("https://example.com/a.mp4.html"))
        assertTrue(isDirectVideoUrl("https://example.com/a.m4v"))
    }

    @Test
    fun 上限の既定値と選べる値() {
        assertEquals(2L * 1024 * 1024 * 1024, VideoDownloadSettings().maxTotalBytes)
        assertTrue(VideoDownloadSettings().wifiOnly)
        assertTrue(VideoDownloadSettings.DEFAULT_MAX_TOTAL_BYTES in VideoDownloadSettings.MAX_TOTAL_BYTES_CHOICES)
    }
}
