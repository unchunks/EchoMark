package com.unchunks.echomark.worker

import com.unchunks.echomark.testing.FakeAppSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同時に動かす数と、クラウド API の呼び出しの上限(#70)。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingLimitsTest {

    private val settings = FakeAppSettingsRepository()

    @Test
    fun 上限まで同時に動かし_空いたら次を動かす() = runTest {
        settings.updateProcessingSettings { it.copy(maxConcurrent = 2) }
        val limiter = ProcessingConcurrencyLimiter(settings)
        val gates = List(3) { CompletableDeferred<Unit>() }
        val started = MutableList(3) { false }

        val jobs = gates.mapIndexed { i, gate ->
            launch {
                limiter.withPermit {
                    started[i] = true
                    gate.await()
                }
            }
        }
        runCurrent()
        assertEquals(listOf(true, true, false), started)
        assertEquals(2, limiter.runningCount)

        gates[0].complete(Unit)
        runCurrent()
        assertTrue(started[2])

        gates.forEach { it.complete(Unit) }
        jobs.forEach { it.join() }
        assertEquals(0, limiter.runningCount)
    }

    @Test
    fun 待っている間に上限を変えても_空いたときに読み直して動く() = runTest {
        settings.updateProcessingSettings { it.copy(maxConcurrent = 1) }
        val limiter = ProcessingConcurrencyLimiter(settings)
        val first = CompletableDeferred<Unit>()
        var secondStarted = false
        launch { limiter.withPermit { first.await() } }
        launch { limiter.withPermit { secondStarted = true } }
        runCurrent()
        assertFalse(secondStarted)

        // 待っている間に上限を変えても、空きが出たときに読み直す
        settings.updateProcessingSettings { it.copy(maxConcurrent = 2) }
        first.complete(Unit)
        advanceUntilIdle()
        assertTrue(secondStarted)
    }

    @Test
    fun 失敗しても枠を返す() = runTest {
        val limiter = ProcessingConcurrencyLimiter(settings)
        runCatching { limiter.withPermit { error("boom") } }

        assertEquals(0, limiter.runningCount)
    }

    @Test
    fun API呼び出しは1分あたりの上限を超えたら_いちばん古い呼び出しから1分経つまで待つ() = runTest {
        settings.updateProcessingSettings { it.copy(apiRequestsPerMinute = 2) }
        val limiter = ApiRateLimiter(settings).apply { clock = { currentTime } }

        limiter.acquire()
        advanceTimeBy(10_000)
        limiter.acquire()
        val third = async { limiter.acquire(); currentTime }
        advanceUntilIdle()

        // 1 回目(0 秒)から 1 分後に 3 回目の枠が空く
        assertEquals(60_000L, third.await())
    }

    @Test
    fun 上限なしなら待たない() = runTest {
        val limiter = ApiRateLimiter(settings).apply { clock = { currentTime } }

        repeat(100) { limiter.acquire() }

        assertEquals(0L, currentTime)
    }
}
