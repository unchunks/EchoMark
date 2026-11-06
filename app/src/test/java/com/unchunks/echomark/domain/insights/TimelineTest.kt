package com.unchunks.echomark.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class TimelineTest {

    @Test
    fun 空なら行も無い() {
        assertTrue(Timeline.rows(emptyList(), TimelineBasis.SAVED, TOKYO).isEmpty())
    }

    @Test
    fun 新しい順に月と日の見出しをはさんで並べる() {
        val rows = Timeline.rows(
            listOf(
                bookmark(1, createdAt = millis(2026, 5, 20)),
                bookmark(2, createdAt = millis(2026, 6, 2, 9)),
                bookmark(3, createdAt = millis(2026, 6, 2, 18)),
                bookmark(4, createdAt = millis(2026, 6, 10))
            ),
            TimelineBasis.SAVED, TOKYO
        )
        assertEquals(
            listOf(
                TimelineRow.MonthHeader(YearMonth.of(2026, 6), 3),
                TimelineRow.DayHeader(date(2026, 6, 10)),
                "b4",
                TimelineRow.DayHeader(date(2026, 6, 2)),
                "b3",
                "b2",
                TimelineRow.MonthHeader(YearMonth.of(2026, 5), 1),
                TimelineRow.DayHeader(date(2026, 5, 20)),
                "b1"
            ),
            rows.map { if (it is TimelineRow.Item) "b${it.bookmark.id}" else it }
        )
    }

    @Test
    fun 基準を変えると最後に開いた日で並ぶ() {
        val list = listOf(
            bookmark(1, createdAt = millis(2026, 1, 1), lastAccessedAt = millis(2026, 6, 1)),
            bookmark(2, createdAt = millis(2026, 3, 1), lastAccessedAt = millis(2026, 3, 1))
        )
        val saved = Timeline.rows(list, TimelineBasis.SAVED, TOKYO).filterIsInstance<TimelineRow.Item>()
        val opened = Timeline.rows(list, TimelineBasis.LAST_OPENED, TOKYO).filterIsInstance<TimelineRow.Item>()
        assertEquals(listOf(2L, 1L), saved.map { it.bookmark.id })
        assertEquals(listOf(1L, 2L), opened.map { it.bookmark.id })
    }

    @Test
    fun 行のキーは重ならない() {
        val list = (1..50L).map { bookmark(it, createdAt = millis(2026, 1 + (it % 5).toInt(), 1 + (it % 7).toInt())) }
        val keys = Timeline.rows(list, TimelineBasis.SAVED, TOKYO).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun 数千件でも行数は件数と見出しの分だけ() {
        val list = (1..5000L).map { bookmark(it, createdAt = millis(2024, 1, 1) + it * 3_600_000L) }
        val rows = Timeline.rows(list, TimelineBasis.SAVED, TOKYO)
        assertEquals(5000, rows.count { it is TimelineRow.Item })
        assertEquals(5000, rows.filterIsInstance<TimelineRow.MonthHeader>().sumOf { it.count })
    }
}
