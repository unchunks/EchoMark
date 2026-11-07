package com.unchunks.echomark.domain.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class SpacedRepetitionTest {

    private val zone = ZoneId.of("Asia/Tokyo")
    private fun at(month: Int, day: Int, hour: Int = 15) =
        LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val now = at(6, 10)

    private fun card(
        interval: Int = 0, ease: Int = SpacedRepetition.DEFAULT_EASE, reps: Int = 0, lapses: Int = 0
    ) = ReviewCard(1, dueAt = 0, intervalDays = interval, easePermille = ease, repetitions = reps, lapses = lapses, lastReviewedAt = null, createdAt = 0)

    private fun ReviewCard.dueDate() = java.time.Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()

    @Test
    fun 新しいカードは翌日の0時から復習に出る() {
        val card = SpacedRepetition.newCard(7, now, zone)
        assertEquals(7L, card.bookmarkId)
        assertEquals(at(6, 11, 0), card.dueAt)
        assertEquals(SpacedRepetition.DEFAULT_EASE, card.easePermille)
        assertEquals(now, card.createdAt)
        assertFalse(card.isDue(now))
        assertTrue(card.isDue(at(6, 11, 0)))
    }

    @Test
    fun 続けて思い出せると_1日_3日_そのあとは伸びやすさをかけて延びる() {
        var c = card()
        c = SpacedRepetition.next(c, ReviewGrade.GOOD, now, zone)
        assertEquals(1, c.intervalDays)
        c = SpacedRepetition.next(c, ReviewGrade.GOOD, now, zone)
        assertEquals(3, c.intervalDays)
        c = SpacedRepetition.next(c, ReviewGrade.GOOD, now, zone)
        // 3 × 2.5 = 7.5 → 8
        assertEquals(8, c.intervalDays)
        c = SpacedRepetition.next(c, ReviewGrade.GOOD, now, zone)
        assertEquals(20, c.intervalDays)
        assertEquals(4, c.repetitions)
    }

    @Test
    fun 次の日時は日付の区切りにそろえる() {
        // 夜 23 時に答えても、3 日後の 0 時から出る(その日の朝から復習できる)
        val late = at(6, 10, 23)
        val c = SpacedRepetition.next(card(interval = 1, reps = 1), ReviewGrade.GOOD, late, zone)
        assertEquals(3, c.intervalDays)
        assertEquals(at(6, 13, 0), c.dueAt)
    }

    @Test
    fun もう一度は間隔を0に戻し_10分後にまた出す() {
        val c = SpacedRepetition.next(card(interval = 20, reps = 4, lapses = 1), ReviewGrade.AGAIN, now, zone)
        assertEquals(0, c.intervalDays)
        assertEquals(0, c.repetitions)
        assertEquals(2, c.lapses)
        assertEquals(now + TimeUnit.MINUTES.toMillis(SpacedRepetition.RELEARN_MINUTES.toLong()), c.dueAt)
        assertEquals(SpacedRepetition.DEFAULT_EASE - 200, c.easePermille)
        assertEquals(now, c.lastReviewedAt)
        // 次に思い出せたら、また 1 日から
        assertEquals(1, SpacedRepetition.next(c, ReviewGrade.GOOD, now, zone).intervalDays)
    }

    @Test
    fun むずかしいは少しだけ延ばし_伸びやすさを下げる() {
        val c = SpacedRepetition.next(card(interval = 10, reps = 3), ReviewGrade.HARD, now, zone)
        assertEquals(12, c.intervalDays)
        assertEquals(SpacedRepetition.DEFAULT_EASE - 150, c.easePermille)
        assertEquals(4, c.repetitions)
        // 間隔が 0 や 1 のときも、必ず 1 日は延びる
        assertEquals(1, SpacedRepetition.next(card(interval = 0), ReviewGrade.HARD, now, zone).intervalDays)
        assertEquals(2, SpacedRepetition.next(card(interval = 1, reps = 1), ReviewGrade.HARD, now, zone).intervalDays)
    }

    @Test
    fun かんたんは大きく延ばし_伸びやすさを上げる() {
        assertEquals(4, SpacedRepetition.next(card(), ReviewGrade.EASY, now, zone).intervalDays)
        assertEquals(7, SpacedRepetition.next(card(interval = 4, reps = 1), ReviewGrade.EASY, now, zone).intervalDays)
        val c = SpacedRepetition.next(card(interval = 10, reps = 3), ReviewGrade.EASY, now, zone)
        // 10 × 2.5 × 1.3 = 32.5 → 33
        assertEquals(33, c.intervalDays)
        assertEquals(SpacedRepetition.DEFAULT_EASE + 150, c.easePermille)
    }

    @Test
    fun 伸びやすさには上限と下限がある() {
        var low = card(ease = SpacedRepetition.MIN_EASE + 50)
        repeat(5) { low = SpacedRepetition.next(low, ReviewGrade.AGAIN, now, zone) }
        assertEquals(SpacedRepetition.MIN_EASE, low.easePermille)
        var high = card()
        repeat(20) { high = SpacedRepetition.next(high, ReviewGrade.EASY, now, zone) }
        assertTrue(high.easePermille <= 3500)
    }

    @Test
    fun 間隔には上限がある() {
        var c = card(interval = 300, reps = 9)
        repeat(3) { c = SpacedRepetition.next(c, ReviewGrade.EASY, now, zone) }
        assertEquals(SpacedRepetition.MAX_INTERVAL_DAYS, c.intervalDays)
    }

    @Test
    fun 間隔は答えるたびに縮まない_かんたんほど長い() {
        val base = card(interval = 6, reps = 2)
        val hard = SpacedRepetition.next(base, ReviewGrade.HARD, now, zone).intervalDays
        val good = SpacedRepetition.next(base, ReviewGrade.GOOD, now, zone).intervalDays
        val easy = SpacedRepetition.next(base, ReviewGrade.EASY, now, zone).intervalDays
        assertTrue("hard=$hard good=$good easy=$easy", base.intervalDays < hard && hard <= good && good < easy)
    }

    @Test
    fun 夏時間の国でも日付の区切りにそろう() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-07(夏時間の前日)の昼に答えて 2 日後 = 3/9 の 0 時(夏時間の後。時差が 1 時間変わる)
        val answeredAt = LocalDateTime.of(2026, 3, 7, 12, 0).atZone(ny).toInstant().toEpochMilli()
        val c = SpacedRepetition.next(card(interval = 1, reps = 1), ReviewGrade.GOOD, answeredAt, ny)
        assertEquals(java.time.LocalDate.of(2026, 3, 10), c.dueAt.let { java.time.Instant.ofEpochMilli(it).atZone(ny).toLocalDate() })
        assertEquals(0, java.time.Instant.ofEpochMilli(c.dueAt).atZone(ny).hour)
    }

    @Test
    fun 日時が来ているものを早い順に選ぶ() {
        val a = card().copy(bookmarkId = 1, dueAt = 300)
        val b = card().copy(bookmarkId = 2, dueAt = 100)
        val c = card().copy(bookmarkId = 3, dueAt = 100)
        val future = card().copy(bookmarkId = 4, dueAt = 10_000)
        assertEquals(listOf(2L, 3L, 1L), SpacedRepetition.due(listOf(a, future, c, b), now = 500).map { it.bookmarkId })
    }

    @Test
    fun 次の復習の言い方() {
        assertEquals("10分後", SpacedRepetition.intervalLabel(card(interval = 0)))
        assertEquals("明日", SpacedRepetition.intervalLabel(card(interval = 1)))
        assertEquals("8日後", SpacedRepetition.intervalLabel(card(interval = 8)))
        assertEquals("約2か月後", SpacedRepetition.intervalLabel(card(interval = 60)))
        assertEquals("約1年後", SpacedRepetition.intervalLabel(card(interval = 365)))
    }

    @Test
    fun 一覧に出す_次の復習がいつかの言い方() {
        assertEquals("いま復習できます", SpacedRepetition.dueLabel(dueAt = now - 1, now = now, zone = zone))
        assertEquals("今日あとで", SpacedRepetition.dueLabel(dueAt = now + 60_000, now = now, zone = zone))
        assertEquals("明日", SpacedRepetition.dueLabel(dueAt = at(6, 11, 0), now = now, zone = zone))
        assertEquals("3日後", SpacedRepetition.dueLabel(dueAt = at(6, 13, 0), now = now, zone = zone))
        assertEquals("約2か月後", SpacedRepetition.dueLabel(dueAt = at(8, 10, 0), now = now, zone = zone))
    }
}
