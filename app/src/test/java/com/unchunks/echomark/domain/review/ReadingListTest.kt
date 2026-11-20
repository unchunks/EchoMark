package com.unchunks.echomark.domain.review

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingListTest {

    private val long = "あ".repeat(1500) // 3 分

    private fun bookmark(id: Long, listedAt: Long?, content: String? = null) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", createdAt = 0, lastAccessedAt = 0, readingListAt = listedAt, content = content
    )

    @Test
    fun 入れていないものは含めず_入れた順に並ぶ() {
        val list = ReadingList.of(listOf(bookmark(1, 300), bookmark(2, null), bookmark(3, 100), bookmark(4, 200)))
        assertEquals(listOf(3L, 4L, 1L), list.items.map { it.id })
        assertEquals(3L, list.next!!.id)
        assertTrue(!list.isEmpty)
    }

    @Test
    fun 入れた日時が同じなら_IDの順() {
        val list = ReadingList.of(listOf(bookmark(5, 100), bookmark(2, 100)))
        assertEquals(listOf(2L, 5L), list.items.map { it.id })
    }

    @Test
    fun 空のときは次も時間もない() {
        val list = ReadingList.of(listOf(bookmark(1, null)))
        assertTrue(list.isEmpty)
        assertNull(list.next)
        assertNull(list.totalLabel())
        assertEquals(ReadingList.EMPTY, list)
    }

    @Test
    fun 時間の目安を足し_出せなかった件数を知らせる() {
        val list = ReadingList.of(listOf(bookmark(1, 1, long), bookmark(2, 2, long), bookmark(3, 3, null)))
        assertEquals(6, list.totalMinutes)
        assertEquals(1, list.unknownCount)
        assertEquals("全部で約6分(ほか1件は目安なし)", list.totalLabel())
    }

    @Test
    fun 時間の言い方は_分_時間_時間と分() {
        fun label(minutes: Int) = ReadingList(listOf(bookmark(1, 1)), minutes, 0).totalLabel()
        assertEquals("全部で約45分", label(45))
        assertEquals("全部で約2時間", label(120))
        assertEquals("全部で約1時間15分", label(75))
        // 目安が 1 件も出せないときは、時間を言わない
        assertNull(label(0))
    }
}
