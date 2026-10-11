package com.unchunks.echomark.domain.bookmark.model

import com.unchunks.echomark.data.attachment.attachmentFileName
import com.unchunks.echomark.data.attachment.extensionOf
import com.unchunks.echomark.data.attachment.fallbackExtensionOf
import com.unchunks.echomark.data.attachment.fallbackMimeTypeOf
import com.unchunks.echomark.data.attachment.sanitizeDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ファイルの種類の判定・ファイル名の扱いのテスト。 */
class AttachmentTest {

    @Test
    fun MIMEタイプから保存形式を決める() {
        assertEquals(BookmarkType.IMAGE, bookmarkTypeOfMimeType("image/jpeg"))
        assertEquals(BookmarkType.IMAGE, bookmarkTypeOfMimeType("IMAGE/PNG"))
        assertEquals(BookmarkType.PDF, bookmarkTypeOfMimeType("application/pdf"))
        assertEquals(BookmarkType.AUDIO, bookmarkTypeOfMimeType("audio/mp4"))
        assertEquals(BookmarkType.VIDEO, bookmarkTypeOfMimeType("video/quicktime"))
        assertEquals(BookmarkType.TEXT, bookmarkTypeOfMimeType("text/markdown; charset=utf-8"))
        assertNull(bookmarkTypeOfMimeType("application/zip"))
        assertNull(bookmarkTypeOfMimeType(null))
        assertNull(bookmarkTypeOfMimeType(""))
    }

    @Test
    fun リンク先からダウンロードするのはPDFと画像と音声と動画だけ() {
        assertTrue(isDownloadableMimeType("application/pdf"))
        assertTrue(isDownloadableMimeType("image/webp"))
        assertTrue(isDownloadableMimeType("audio/mpeg"))
        assertTrue(isDownloadableMimeType("video/mp4"))
        assertFalse(isDownloadableMimeType("text/plain"))
        assertFalse(isDownloadableMimeType("text/html"))
        assertFalse(isDownloadableMimeType("application/json"))
    }

    @Test
    fun タイトルの既定値はファイル名から拡張子を除いたもの() {
        assertEquals("旅行の写真", titleFromFileName("旅行の写真.JPG"))
        assertEquals("report.v2", titleFromFileName("report.v2.pdf"))
        assertEquals("README", titleFromFileName("README"))
        assertEquals(".bashrc", titleFromFileName(".bashrc"))
    }

    @Test
    fun 拡張子とMIMEタイプの対応() {
        assertEquals("pdf", extensionOf("a/b/Paper.PDF"))
        assertNull(extensionOf("noext"))
        assertEquals("audio/mp4", fallbackMimeTypeOf("m4a"))
        assertEquals("jpg", fallbackExtensionOf("image/jpeg"))
        assertEquals("html", fallbackExtensionOf("text/html"))
    }

    @Test
    fun 表示名はパスの区切りを除き空ならfileにする() {
        assertEquals("secret.txt", sanitizeDisplayName("../../secret.txt", "txt"))
        assertEquals("file.pdf", sanitizeDisplayName("  ", "pdf"))
        assertEquals("file", sanitizeDisplayName(null, null))
    }

    @Test
    fun 保存先のパスは添付ファイルの置き場所だけを受け付ける() {
        assertEquals("0b1c-2d.jpg", attachmentFileName("attachments/0b1c-2d.jpg"))
        assertNull(attachmentFileName("attachments/../x.jpg"))
        assertNull(attachmentFileName("attachments/sub/x.jpg"))
        assertNull(attachmentFileName("/data/data/x.jpg"))
        assertNull(attachmentFileName("attachments/.hidden"))
    }
}
