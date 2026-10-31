package com.unchunks.echomark.data.capture

import com.unchunks.echomark.domain.capture.CaptureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class CaptureFilesTest {

    private val zone: ZoneId = ZoneOffset.ofHours(9)

    // 2026-10-31 03:04:05 UTC = 12:04:05 JST
    private val now = 1_793_415_845_000L

    @Test
    fun ファイル名は種類と撮った日時を含み拡張子が種類に合う() {
        assertEquals("写真 2026-10-31 12-04-05.jpg", captureFileName(CaptureKind.PHOTO, now, zone))
        assertEquals("動画 2026-10-31 12-04-05.mp4", captureFileName(CaptureKind.VIDEO, now, zone))
        assertEquals("音声メモ 2026-10-31 12-04-05.m4a", captureFileName(CaptureKind.VOICE_MEMO, now, zone))
    }

    @Test
    fun 同じ秒に重なったときは番号で区別する() {
        assertEquals("写真 2026-10-31 12-04-05 (2).jpg", captureFileName(CaptureKind.PHOTO, now, zone, sequence = 2))
    }

    @Test
    fun 種類のMIMEタイプは添付ファイルとして保存できる形() {
        assertEquals("image/jpeg", CaptureKind.PHOTO.mimeType)
        assertEquals("video/mp4", CaptureKind.VIDEO.mimeType)
        assertEquals("audio/mp4", CaptureKind.VOICE_MEMO.mimeType)
    }

    @Test
    fun 一時ファイル名の検証はディレクトリの区切りと相対パスを断る() {
        assertTrue(isSafeCaptureName("写真 2026-10-31 12-04-05.jpg"))
        assertFalse(isSafeCaptureName(null))
        assertFalse(isSafeCaptureName(""))
        assertFalse(isSafeCaptureName(".."))
        assertFalse(isSafeCaptureName("."))
        assertFalse(isSafeCaptureName("../databases/echomark.db"))
        assertFalse(isSafeCaptureName("a\\b.jpg"))
        assertFalse(isSafeCaptureName("a\u0000b.jpg"))
        assertFalse(isSafeCaptureName("x".repeat(101)))
    }
}
