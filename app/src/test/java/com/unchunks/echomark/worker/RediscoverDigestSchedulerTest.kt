package com.unchunks.echomark.worker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class RediscoverDigestSchedulerTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    // 2026-09-28 は月曜日
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone)

    private fun delay(now: ZonedDateTime, dow: DayOfWeek, h: Int, m: Int) =
        RediscoverDigestScheduler.calculateInitialDelayMillis(now, dow, h, m)

    @Test
    fun 同じ曜日で時刻がまだ先なら当日の時刻まで() {
        val now = at(28, 9, 0)
        assertEquals(TimeUnit.HOURS.toMillis(11), delay(now, DayOfWeek.MONDAY, 20, 0))
    }

    @Test
    fun 同じ曜日で時刻を過ぎていれば来週まで() {
        val now = at(28, 21, 0)
        assertEquals(
            TimeUnit.DAYS.toMillis(7) - TimeUnit.HOURS.toMillis(1),
            delay(now, DayOfWeek.MONDAY, 20, 0)
        )
    }

    @Test
    fun ちょうど今の時刻なら来週まで() {
        val now = at(28, 20, 0)
        assertEquals(TimeUnit.DAYS.toMillis(7), delay(now, DayOfWeek.MONDAY, 20, 0))
    }

    @Test
    fun 別の曜日なら次のその曜日まで() {
        val now = at(28, 20, 0) // 月曜
        assertEquals(TimeUnit.DAYS.toMillis(2), delay(now, DayOfWeek.WEDNESDAY, 20, 0))
        // 曜日が既に過ぎている(日曜)場合は6日後
        assertEquals(TimeUnit.DAYS.toMillis(6), delay(now, DayOfWeek.SUNDAY, 20, 0))
    }

    @Test
    fun 秒とナノ秒は切り捨てて時刻ちょうどに合わせる() {
        val now = ZonedDateTime.of(2026, 9, 28, 19, 59, 30, 500_000_000, zone)
        assertEquals(29_500L, delay(now, DayOfWeek.MONDAY, 20, 0))
    }
}
