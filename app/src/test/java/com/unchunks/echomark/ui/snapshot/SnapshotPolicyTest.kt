package com.unchunks.echomark.ui.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPolicyTest {

    @Test
    fun httpとhttpsのリンクだけオフラインコピーを作れる() {
        assertTrue(canSnapshot("https://example.com/a"))
        assertTrue(canSnapshot("  HTTP://example.com "))
        assertFalse(canSnapshot(null))
        assertFalse(canSnapshot(""))
        assertFalse(canSnapshot("file:///data/data/x"))
        assertFalse(canSnapshot("content://media/1"))
        assertFalse(canSnapshot("javascript:alert(1)"))
        assertFalse(canSnapshot("example.com"))
    }

    @Test
    fun 利用者が押したhttpのリンクだけブラウザに渡す() {
        assertEquals(SnapshotLinkAction.OPEN_IN_BROWSER, linkActionFor("https", isUserGesture = true))
        assertEquals(SnapshotLinkAction.OPEN_IN_BROWSER, linkActionFor("HTTP", isUserGesture = true))
    }

    @Test
    fun 押されていない遷移とhttp以外は読み込まずブラウザにも渡さない() {
        assertEquals(SnapshotLinkAction.BLOCK, linkActionFor("https", isUserGesture = false))
        assertEquals(SnapshotLinkAction.BLOCK, linkActionFor("intent", isUserGesture = true))
        assertEquals(SnapshotLinkAction.BLOCK, linkActionFor("javascript", isUserGesture = true))
        assertEquals(SnapshotLinkAction.BLOCK, linkActionFor("file", isUserGesture = true))
        assertEquals(SnapshotLinkAction.BLOCK, linkActionFor(null, isUserGesture = true))
    }

    @Test
    fun file以外のリクエストは通しfileは保存したコピーだけ通す() {
        val allowed = "file:///data/user/0/com.unchunks.echomark/files/attachments/abc.mhtml"

        assertTrue(isFileRequestAllowed(allowed, allowed))
        assertTrue(isFileRequestAllowed("https://example.com/a.png", allowed))
        assertFalse(isFileRequestAllowed("file:///data/user/0/com.unchunks.echomark/databases/echomark.db", allowed))
        assertFalse(isFileRequestAllowed("FILE:///etc/hosts", allowed))
    }

    @Test
    fun PDFのページ位置は表示の高さごとに上から区切る() {
        assertEquals(listOf(0, 800, 1600), pdfPageOffsets(contentHeight = 2000, pageHeight = 800))
        assertEquals(listOf(0), pdfPageOffsets(contentHeight = 800, pageHeight = 800))
        assertEquals(listOf(0), pdfPageOffsets(contentHeight = 10, pageHeight = 800))
        assertEquals(emptyList<Int>(), pdfPageOffsets(contentHeight = 0, pageHeight = 800))
        assertEquals(emptyList<Int>(), pdfPageOffsets(contentHeight = 100, pageHeight = 0))
    }

    @Test
    fun PDFのページ数は上限で打ち切る() {
        val offsets = pdfPageOffsets(contentHeight = 1_000_000, pageHeight = 100)

        assertEquals(MAX_PDF_PAGES, offsets.size)
        assertEquals((MAX_PDF_PAGES - 1) * 100, offsets.last())
        assertEquals(1, pdfPageOffsets(contentHeight = 1_000, pageHeight = 100, maxPages = 0).size)
    }

    @Test
    fun 全体が1色の描画は空とみなす() {
        assertTrue(isBlankImage(IntArray(100 * 200) { 0xFFFFFFFF.toInt() }, 100, 200))
        assertTrue(isBlankImage(IntArray(1) { 0 }, 1, 1))
    }

    @Test
    fun 文字のような濃い点が1つでもあれば空ではない() {
        val pixels = IntArray(100 * 200) { 0xFFFFFFFF.toInt() }
        // 間引いて調べる点のどれかに当たる位置(右下の端)
        pixels[100 * 200 - 1] = 0xFF000000.toInt()

        assertFalse(isBlankImage(pixels, 100, 200))
    }

    @Test
    fun 大きさが合わない描画は空とみなす() {
        assertTrue(isBlankImage(IntArray(10), 100, 200))
        assertTrue(isBlankImage(IntArray(10), 0, 0))
    }
}
