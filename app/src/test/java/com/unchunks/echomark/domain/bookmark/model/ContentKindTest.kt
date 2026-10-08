package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentKindTest {

    private fun bookmark(type: BookmarkType, url: String? = null, mimeType: String? = null) = Bookmark(
        type = type, contentUri = url, title = "t", createdAt = 0, lastAccessedAt = 0, mimeType = mimeType
    )

    @Test
    fun 保存形式から種類を決める() {
        assertEquals(ContentKind.MEMO, bookmark(BookmarkType.TEXT).contentKind())
        assertEquals(ContentKind.IMAGE, bookmark(BookmarkType.IMAGE).contentKind())
        assertEquals(ContentKind.DOCUMENT, bookmark(BookmarkType.PDF).contentKind())
        assertEquals(ContentKind.AUDIO, bookmark(BookmarkType.AUDIO).contentKind())
        assertEquals(ContentKind.VIDEO, bookmark(BookmarkType.VIDEO).contentKind())
    }

    @Test
    fun リンクは動画サイトなら動画_それ以外はWebページ() {
        listOf(
            "https://www.youtube.com/watch?v=abc", "https://m.youtube.com/watch?v=abc", "https://youtu.be/abc",
            "https://vimeo.com/1", "https://www.nicovideo.jp/watch/sm1", "https://www.tiktok.com/@a/video/1"
        ).forEach { assertEquals(it, ContentKind.VIDEO, bookmark(BookmarkType.URL, it).contentKind()) }

        assertEquals(ContentKind.WEB_PAGE, bookmark(BookmarkType.URL, "https://example.com/post").contentKind())
        assertEquals(ContentKind.WEB_PAGE, bookmark(BookmarkType.URL, "https://notyoutube.com/x").contentKind())
        assertEquals(ContentKind.WEB_PAGE, bookmark(BookmarkType.URL, "不正な URL").contentKind())
    }

    @Test
    fun リンク先がHTML以外ならMIMEタイプで決める() {
        assertEquals(
            ContentKind.DOCUMENT,
            bookmark(BookmarkType.URL, "https://example.com/a.pdf", "application/pdf").contentKind()
        )
        assertEquals(ContentKind.IMAGE, bookmark(BookmarkType.URL, "https://example.com/a", "image/png").contentKind())
        assertEquals(
            ContentKind.AUDIO,
            bookmark(BookmarkType.URL, "https://example.com/a", "audio/mpeg; charset=binary").contentKind()
        )
        // HTML や不明な MIME タイプなら URL で判断する
        assertEquals(
            ContentKind.VIDEO,
            bookmark(BookmarkType.URL, "https://youtu.be/abc", "text/html").contentKind()
        )
    }

    @Test
    fun MIMEタイプの判定() {
        assertEquals(ContentKind.VIDEO, contentKindOfMimeType("video/mp4"))
        assertEquals(ContentKind.MEMO, contentKindOfMimeType("text/plain; charset=utf-8"))
        assertNull(contentKindOfMimeType("text/html"))
        assertNull(contentKindOfMimeType(null))
    }
}
