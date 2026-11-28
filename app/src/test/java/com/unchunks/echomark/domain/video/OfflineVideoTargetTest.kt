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

/** 動画のオフライン保存の対象・PeerTube の URL・本文の字幕の見分け方 */
class OfflineVideoTargetTest {

    private fun bookmark(url: String?, type: BookmarkType = BookmarkType.URL, filePath: String? = null, mimeType: String? = null) =
        Bookmark(type = type, contentUri = url, title = "t", createdAt = 0, lastAccessedAt = 0, filePath = filePath, mimeType = mimeType)

    @Test
    fun 動画サイトのページは対象() {
        assertTrue(bookmark("https://www.youtube.com/watch?v=abcdefghijk").isOfflineVideoTarget())
        assertTrue(bookmark("https://youtu.be/abcdefghijk").isOfflineVideoTarget())
        assertTrue(bookmark("https://vimeo.com/123456").isOfflineVideoTarget())
        assertTrue(bookmark("https://peertube.example/w/9c9de5e8xQNXhHR4Fvu8Qa").isOfflineVideoTarget())
    }

    @Test
    fun 動画でないページとファイルとメモは対象でない() {
        assertFalse(bookmark("https://example.com/article").isOfflineVideoTarget())
        // 動画のファイルへの直接のリンクで、ファイルを保存したもの(字幕ではなくファイルで見られる)
        assertFalse(
            bookmark("https://example.com/a.mp4", filePath = "attachments/a.mp4", mimeType = "video/mp4").isOfflineVideoTarget()
        )
        assertFalse(bookmark(null, type = BookmarkType.TEXT).isOfflineVideoTarget())
        assertFalse(bookmark("intent://www.youtube.com/watch?v=abcdefghijk").isOfflineVideoTarget())
    }

    @Test
    fun PeerTubeの動画ページのパスを見分ける() {
        assertEquals(
            PeerTubeUrls.VideoRef("https://peertube.example", "9c9de5e8xQNXhHR4Fvu8Qa"),
            PeerTubeUrls.videoOf("https://peertube.example/w/9c9de5e8xQNXhHR4Fvu8Qa")
        )
        assertEquals(
            PeerTubeUrls.VideoRef("http://tube.example:8080", "9c9de5e8-89c4-4c5c-8b8a-3f1c2e2b1a77"),
            PeerTubeUrls.videoOf("http://tube.example:8080/videos/watch/9c9de5e8-89c4-4c5c-8b8a-3f1c2e2b1a77")
        )
        assertEquals("9c9de5e8xQNXhHR4Fvu8Qa", PeerTubeUrls.videoOf("https://tube.example/videos/embed/9c9de5e8xQNXhHR4Fvu8Qa")?.videoId)
    }

    @Test
    fun PeerTubeの動画でないパスは見分けない() {
        assertNull(PeerTubeUrls.videoOf("https://peertube.example/w/p/9c9de5e8xQNXhHR4Fvu8Qa"))
        assertNull(PeerTubeUrls.videoOf("https://example.com/w/short"))
        assertNull(PeerTubeUrls.videoOf("https://example.com/wiki/Video"))
        assertNull(PeerTubeUrls.videoOf("ftp://peertube.example/w/9c9de5e8xQNXhHR4Fvu8Qa"))
        assertEquals(ContentKind.VIDEO, contentKindOfUrl("https://peertube.example/w/9c9de5e8xQNXhHR4Fvu8Qa"))
        assertNull(contentKindOfUrl("https://example.com/w/short"))
    }

    @Test
    fun 本文の字幕の見出しで字幕があるかを見分ける() {
        assertTrue(hasVideoTranscript("チャンネル: A\n\n説明\n\n字幕:\nこんにちは"))
        assertTrue(hasVideoTranscript("説明\r\n\r\n字幕(自動生成):\r\nこんにちは"))
        assertFalse(hasVideoTranscript("チャンネル: A\n\n説明"))
        assertFalse(hasVideoTranscript("字幕: の話をする記事"))
        assertFalse(hasVideoTranscript(null))
    }
}
