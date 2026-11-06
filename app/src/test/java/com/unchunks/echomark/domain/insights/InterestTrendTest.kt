package com.unchunks.echomark.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class InterestTrendTest {

    // 2026-06 を含む直近 6 か月 = 1〜6 月。前半 = 1〜3 月、後半 = 4〜6 月
    private val now = millis(2026, 6, 15)
    private var nextId = 1L

    private fun saved(year: Int, month: Int, vararg tags: String) =
        bookmark(nextId++, createdAt = millis(year, month, 10), tags = tags.toList())

    private fun trend(vararg bookmarks: com.unchunks.echomark.domain.bookmark.model.Bookmark, months: Int = 6) =
        InterestTrends.compute(bookmarks.toList(), now, TOKYO, months)

    @Test
    fun 空なら空() {
        val result = InterestTrends.compute(emptyList(), now, TOKYO)
        assertTrue(result.isEmpty)
        assertEquals(6, result.months.size)
        assertEquals(YearMonth.of(2026, 1), result.months.first())
        assertEquals(YearMonth.of(2026, 6), result.months.last())
    }

    @Test
    fun 月ごとの件数を数え期間外は含めない() {
        val result = trend(
            saved(2026, 1, "Kotlin"),
            saved(2026, 3, "Kotlin", "Kotlin"),
            saved(2026, 6, "Kotlin"),
            saved(2025, 12, "Kotlin"),
            saved(2026, 7, "Kotlin")
        )
        val kotlin = result.series.single()
        // 同じ名前が 2 回付いていても 1 件として数える
        assertEquals(listOf(1, 0, 1, 0, 0, 1), kotlin.counts)
        assertEquals(3, result.bookmarkCount)
    }

    @Test
    fun 前半より後半に増えたタグは増えているに入る() {
        val result = trend(
            saved(2026, 1, "料理"),
            saved(2026, 4, "料理"), saved(2026, 5, "料理"), saved(2026, 6, "料理")
        )
        val change = result.rising.single()
        assertEquals(InterestChange("料理", 1, 3, InterestChangeKind.RISING), change)
        assertEquals(2, change.delta)
    }

    @Test
    fun 差が一件だけなら増減とみなさない() {
        val result = trend(saved(2026, 2, "旅行"), saved(2026, 5, "旅行"), saved(2026, 6, "旅行"))
        assertTrue(result.rising.isEmpty())
        assertTrue(result.falling.isEmpty())
    }

    @Test
    fun 前半に多く後半に少ないタグは減っている() {
        val result = trend(
            saved(2026, 1, "Java"), saved(2026, 2, "Java"), saved(2026, 3, "Java"),
            saved(2026, 5, "Java")
        )
        assertEquals(InterestChange("Java", 3, 1, InterestChangeKind.FALLING), result.falling.single())
        assertTrue(result.rising.isEmpty())
    }

    @Test
    fun 以前に無く後半に二件以上付いたら新しい話題() {
        val result = trend(
            saved(2026, 5, "Rust"), saved(2026, 6, "Rust"),
            saved(2026, 6, "Zig")
        )
        assertEquals(listOf("Rust"), result.newTopics.map { it.tag })
        // 新しい話題は増えているには重ねて出さない
        assertTrue(result.rising.isEmpty())
    }

    @Test
    fun 期間より前に付いていたタグは新しい話題にしない() {
        val result = trend(
            saved(2025, 10, "Rust"),
            saved(2026, 5, "Rust"), saved(2026, 6, "Rust"), saved(2026, 6, "Rust")
        )
        assertTrue(result.newTopics.isEmpty())
        assertEquals("Rust", result.rising.single().tag)
    }

    @Test
    fun 推移のグラフには合計の多いタグを五つまで出す() {
        val bookmarks = (1..7).flatMap { t -> (1..t).map { saved(2026, 6, "タグ$t") } }
        val result = trend(*bookmarks.toTypedArray())
        assertEquals(listOf("タグ7", "タグ6", "タグ5", "タグ4", "タグ3"), result.series.map { it.tag })
    }

    @Test
    fun 期間を短くすると前半後半の区切りも変わる() {
        // 3 か月 = 4〜6 月。count/2 = 1 なので、後半は 6 月だけ
        val result = trend(saved(2026, 4, "A"), saved(2026, 4, "A"), saved(2026, 5, "A"), months = 3)
        assertEquals(InterestChange("A", 3, 0, InterestChangeKind.FALLING), result.falling.single())
        assertEquals(3, result.months.size)
    }
}
