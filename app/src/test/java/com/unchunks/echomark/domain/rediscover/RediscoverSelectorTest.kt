package com.unchunks.echomark.domain.rediscover

import com.unchunks.echomark.testing.testBookmark
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RediscoverSelectorTest {

    private val now = TimeUnit.DAYS.toMillis(1000)
    private fun daysAgo(days: Long) = now - TimeUnit.DAYS.toMillis(days)

    @Test
    fun 三十日未満のものは対象外で三十日ちょうどは対象() {
        val candidates = listOf(
            testBookmark(1, lastAccessedAt = daysAgo(29)),
            testBookmark(2, lastAccessedAt = daysAgo(30))
        )
        val result = RediscoverSelector.select(candidates, emptyMap(), now)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun 最終アクセスが古い順に最大三件を返す() {
        val candidates = listOf(
            testBookmark(1, lastAccessedAt = daysAgo(40)),
            testBookmark(2, lastAccessedAt = daysAgo(100)),
            testBookmark(3, lastAccessedAt = daysAgo(60)),
            testBookmark(4, lastAccessedAt = daysAgo(90)),
            testBookmark(5, lastAccessedAt = daysAgo(31))
        )
        val result = RediscoverSelector.select(candidates, emptyMap(), now)
        assertEquals(listOf(2L, 4L, 3L), result.map { it.id })
    }

    @Test
    fun 直近三十日以内に通知したものは除外される() {
        val candidates = listOf(
            testBookmark(1, lastAccessedAt = daysAgo(100)),
            testBookmark(2, lastAccessedAt = daysAgo(90)),
            testBookmark(3, lastAccessedAt = daysAgo(80))
        )
        val notified = mapOf(
            1L to daysAgo(7),
            2L to daysAgo(30) // ちょうど30日前はクールダウン明け
        )
        val result = RediscoverSelector.select(candidates, notified, now)
        assertEquals(listOf(2L, 3L), result.map { it.id })
    }

    @Test
    fun 候補が無ければ空() {
        assertEquals(emptyList<Long>(), RediscoverSelector.select(emptyList(), emptyMap(), now).map { it.id })
    }

    @Test
    fun 全て通知済みなら空() {
        val candidates = listOf(testBookmark(1, lastAccessedAt = daysAgo(60)))
        val result = RediscoverSelector.select(candidates, mapOf(1L to daysAgo(1)), now)
        assertEquals(emptyList<Long>(), result.map { it.id })
    }

    @Test
    fun 上限と閾値を引数で変えられる() {
        val candidates = listOf(
            testBookmark(1, lastAccessedAt = daysAgo(10)),
            testBookmark(2, lastAccessedAt = daysAgo(8)),
            testBookmark(3, lastAccessedAt = daysAgo(3))
        )
        val result = RediscoverSelector.select(candidates, emptyMap(), now, staleDays = 7, limit = 1)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun 今日の再発見は十四日以上開いていないアーカイブ以外から選ぶ() {
        val candidates = listOf(
            testBookmark(1, lastAccessedAt = daysAgo(13)),
            testBookmark(2, lastAccessedAt = daysAgo(14)),
            testBookmark(3, lastAccessedAt = daysAgo(50)).copy(isArchived = true)
        )
        val result = RediscoverSelector.pickDaily(candidates, now, dayIndex = 0)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun 今日の再発見は同じ日なら同じ顔ぶれで日が変わると入れ替わる() {
        val candidates = (1L..7L).map { testBookmark(it, lastAccessedAt = daysAgo(100 - it)) }

        val day0 = RediscoverSelector.pickDaily(candidates, now, dayIndex = 0).map { it.id }
        val day0Again = RediscoverSelector.pickDaily(candidates.shuffled(), now, dayIndex = 0).map { it.id }
        val day1 = RediscoverSelector.pickDaily(candidates, now, dayIndex = 1).map { it.id }

        assertEquals(listOf(1L, 2L, 3L), day0)
        assertEquals(day0, day0Again)
        assertEquals(listOf(4L, 5L, 6L), day1)
        // 末尾で折り返す
        assertEquals(listOf(7L, 1L, 2L), RediscoverSelector.pickDaily(candidates, now, dayIndex = 2).map { it.id })
    }
}
