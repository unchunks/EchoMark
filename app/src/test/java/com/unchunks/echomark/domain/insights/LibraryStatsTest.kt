package com.unchunks.echomark.domain.insights

import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class LibraryStatsTest {

    private val now = millis(2026, 6, 15)

    @Test
    fun 空ならすべてゼロ() {
        assertEquals(LibraryStats.EMPTY, LibraryStatsCalculator.compute(emptyList(), now, TOKYO))
    }

    @Test
    fun 件数の内訳を数える() {
        val stats = LibraryStatsCalculator.compute(
            listOf(
                bookmark(1, readAt = 1L, isFavorite = true, summary = "要約"),
                bookmark(2, isArchived = true),
                bookmark(3, summary = " "),
                bookmark(4, readAt = 2L, isFavorite = true)
            ),
            now, TOKYO
        )
        assertEquals(4, stats.total)
        assertEquals(2, stats.read)
        assertEquals(2, stats.unread)
        assertEquals(2, stats.favorites)
        assertEquals(1, stats.archived)
        assertEquals(1, stats.withSummary)
    }

    @Test
    fun 種類は多い順で0件の種類は出さない() {
        val stats = LibraryStatsCalculator.compute(
            listOf(
                bookmark(1, type = BookmarkType.PDF),
                bookmark(2, type = BookmarkType.URL),
                bookmark(3, type = BookmarkType.URL)
            ),
            now, TOKYO
        )
        assertEquals(listOf(BookmarkType.URL to 2, BookmarkType.PDF to 1), stats.byType)
    }

    @Test
    fun カテゴリは多い順で未分類は最後() {
        val stats = LibraryStatsCalculator.compute(
            listOf(
                bookmark(1), bookmark(2), bookmark(3),
                bookmark(4, category = "技術"),
                bookmark(5, category = "料理"), bookmark(6, category = "料理"),
                bookmark(7, category = "  ")
            ),
            now, TOKYO
        )
        assertEquals(
            listOf(CountEntry("料理", 2), CountEntry("技術", 1), CountEntry(LibraryStatsCalculator.UNCATEGORIZED, 4)),
            stats.byCategory
        )
    }

    @Test
    fun カテゴリが多いときは上位だけ出して残りはその他にまとめる() {
        val bookmarks = (1..12).flatMap { c -> (1..c).map { n -> bookmark(c * 100L + n, category = "c$c") } }
        val stats = LibraryStatsCalculator.compute(bookmarks, now, TOKYO)
        assertEquals(LibraryStatsCalculator.MAX_CATEGORIES, stats.byCategory.size)
        assertEquals("c12", stats.byCategory.first().label)
        val other = stats.byCategory.last()
        assertEquals(LibraryStatsCalculator.OTHER, other.label)
        // 上位 7 個は c12..c6、残りは c5..c1 = 15 件
        assertEquals(15, other.count)
        assertEquals(bookmarks.size, stats.byCategory.sumOf { it.count })
    }

    @Test
    fun 月ごとは件数ゼロの月も含めて連続し今月が最後() {
        val stats = LibraryStatsCalculator.compute(
            listOf(
                bookmark(1, createdAt = millis(2026, 6, 1), readAt = millis(2026, 6, 2)),
                bookmark(2, createdAt = millis(2026, 6, 30, 23), readAt = millis(2026, 4, 3)),
                bookmark(3, createdAt = millis(2026, 4, 10)),
                bookmark(4, createdAt = millis(2025, 1, 1))
            ),
            now, TOKYO, months = 4
        )
        assertEquals(
            listOf(
                MonthlyActivity(YearMonth.of(2026, 3), 0, 0),
                MonthlyActivity(YearMonth.of(2026, 4), 1, 1),
                MonthlyActivity(YearMonth.of(2026, 5), 0, 0),
                MonthlyActivity(YearMonth.of(2026, 6), 2, 1)
            ),
            stats.monthly
        )
    }

    @Test
    fun 月の区切りはタイムゾーンで決まる() {
        // 日本時間の 7/1 0:30 は UTC では 6/30
        val created = millis(2026, 7, 1, hour = 0) + 30 * 60_000
        val tokyo = LibraryStatsCalculator.compute(listOf(bookmark(1, createdAt = created)), created, TOKYO, months = 1)
        val utc = LibraryStatsCalculator.compute(listOf(bookmark(1, createdAt = created)), created, java.time.ZoneOffset.UTC, months = 1)
        assertEquals(YearMonth.of(2026, 7), tokyo.monthly.single().month)
        assertEquals(YearMonth.of(2026, 6), utc.monthly.single().month)
        assertTrue(tokyo.monthly.single().added == 1 && utc.monthly.single().added == 1)
    }
}
