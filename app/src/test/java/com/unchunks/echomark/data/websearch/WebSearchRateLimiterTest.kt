package com.unchunks.echomark.data.websearch

import com.unchunks.echomark.domain.websearch.WebSearchException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebSearchRateLimiterTest {

    private fun TestScope.limiter(interval: Long = 1_100, maxWait: Long = 5_000) =
        WebSearchRateLimiter(interval, maxWait) { currentTime }

    @Test
    fun 続けて検索するときは間隔を空ける() = runTest {
        val limiter = limiter()

        limiter.acquire()
        assertEquals(0L, currentTime)
        limiter.acquire()
        assertEquals(1_100L, currentTime)
        limiter.acquire()
        assertEquals(2_200L, currentTime)
    }

    @Test
    fun 間隔より後なら待たない() = runTest {
        val limiter = limiter()
        limiter.acquire()
        testScheduler.advanceTimeBy(5_000)

        val before = currentTime
        limiter.acquire()

        assertEquals(before, currentTime)
    }

    @Test
    fun 断られた後は_指示された時間まで待つ() = runTest {
        val limiter = limiter(interval = 0)
        limiter.pauseFor(3_000)

        limiter.acquire()

        assertEquals(3_000L, currentTime)
    }

    @Test
    fun 待ち時間が長すぎるなら待たずに知らせる() = runTest {
        val limiter = limiter(interval = 0, maxWait = 5_000)
        limiter.pauseFor(30_000)

        val error = runCatching { limiter.acquire() }.exceptionOrNull()

        assertTrue(error is WebSearchException.RateLimited)
        assertEquals(30L, (error as WebSearchException.RateLimited).retryAfterSeconds)
        assertEquals(0L, currentTime)
    }
}
