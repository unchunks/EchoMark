package com.unchunks.echomark.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(millis: Long) = formatRelativeTime(now - millis, now)

    @Test
    fun lessThanOneMinute_isJustNow() {
        assertEquals("たった今", ago(0))
        assertEquals("たった今", ago(59_999))
    }

    @Test
    fun futureTime_isJustNow() {
        assertEquals("たった今", formatRelativeTime(now + 5 * minute, now))
    }

    @Test
    fun minutes() {
        assertEquals("1分前", ago(minute))
        assertEquals("59分前", ago(hour - 1))
    }

    @Test
    fun hours() {
        assertEquals("1時間前", ago(hour))
        assertEquals("23時間前", ago(day - 1))
    }

    @Test
    fun days() {
        assertEquals("1日前", ago(day))
        assertEquals("3日前", ago(3 * day + 5 * hour))
        assertEquals("6日前", ago(7 * day - 1))
    }

    @Test
    fun weeks() {
        assertEquals("1週間前", ago(7 * day))
        assertEquals("4週間前", ago(29 * day))
    }

    @Test
    fun months() {
        assertEquals("1か月前", ago(30 * day))
        assertEquals("12か月前", ago(364 * day))
    }

    @Test
    fun years() {
        assertEquals("1年前", ago(365 * day))
        assertEquals("2年前", ago(800 * day))
    }
}
