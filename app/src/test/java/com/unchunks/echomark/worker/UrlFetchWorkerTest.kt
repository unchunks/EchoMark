package com.unchunks.echomark.worker

import com.unchunks.echomark.data.remote.FetchedContent
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.worker.UrlFetchWorker.Companion.contentAfterFetch
import com.unchunks.echomark.worker.UrlFetchWorker.Companion.titleAfterFetch
import com.unchunks.echomark.domain.bookmark.model.LimitedAccess
import com.unchunks.echomark.domain.bookmark.model.PageAccessLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本文取得ワーカーが書き戻すタイトル・本文(取得の間に編集された最新の内容をもとに組み立てる)。 */
class UrlFetchWorkerTest {

    private val url = "https://example.com/a"

    private fun bookmark(title: String = url, content: String? = null) = Bookmark(
        type = BookmarkType.URL, contentUri = url, title = title, content = content, createdAt = 0, lastAccessedAt = 0
    )

    private val fetched = FetchedContent(title = "ページのタイトル", description = "説明", text = "ページの本文")

    @Test
    fun 未編集のタイトルはページのタイトルに置き換える() {
        assertEquals("ページのタイトル", titleAfterFetch(bookmark(title = url), url, fetched))
        assertEquals("ページのタイトル", titleAfterFetch(bookmark(title = ""), url, fetched))
    }

    @Test
    fun 取得の間に編集されたタイトルは残す() {
        assertEquals("自分で付けた名前", titleAfterFetch(bookmark(title = "自分で付けた名前"), url, fetched))
    }

    @Test
    fun 取得の間に書かれたメモの後ろにページの本文を足す() {
        assertEquals(
            "編集したメモ\n\n説明\n\nページの本文",
            contentAfterFetch(bookmark(content = "編集したメモ"), fetched)
        )
    }

    @Test
    fun 同じ本文は二重に足さない() {
        val once = contentAfterFetch(bookmark(content = "メモ"), fetched)
        assertEquals(once, contentAfterFetch(bookmark(content = once), fetched))
    }

    @Test
    fun 本文を取れなかったときは今の本文のまま() {
        assertEquals("メモ", contentAfterFetch(bookmark(content = "メモ"), FetchedContent(null, null, "")))
    }
    @Test
    fun 有料ページは冒頭の本文の前に一部のみ取得の印を入れる() {
        val limited = FetchedContent(null, "説明", "冒頭の本文", accessLimit = PageAccessLimit.PAYWALL)

        val content = contentAfterFetch(bookmark(content = null), limited)!!

        assertTrue(content, content.startsWith(LimitedAccess.MARKER))
        assertTrue(content, content.endsWith("説明\n\n冒頭の本文"))
        // 取得し直しても二重に足さない
        assertEquals(content, contentAfterFetch(bookmark(content = content), limited))
    }

    @Test
    fun 冒頭も説明も取れなかった有料ページは印だけの本文にしない() {
        val limited = FetchedContent(null, null, "", accessLimit = PageAccessLimit.LOGIN_REQUIRED)

        assertEquals("メモ", contentAfterFetch(bookmark(content = "メモ"), limited))
        assertNull(contentAfterFetch(bookmark(content = null), limited))
    }

    @Test
    fun ボット対策のページは本文を足さない() {
        val blocked = FetchedContent(null, null, "", accessLimit = PageAccessLimit.BLOCKED)

        assertEquals("メモ", contentAfterFetch(bookmark(content = "メモ"), blocked))
    }
}
