package com.unchunks.echomark.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** AI 処理の計測ログ: 記録・ブックマークとの結びつけ・件数の上限・1行の要約。 */
class AiPerfLogTest {

    private var now = 1_000_000L

    @Before
    fun setUp() {
        AiPerfLog.clear()
        AiPerfLog.wallClock = { now }
    }

    @After
    fun tearDown() {
        AiPerfLog.clear()
        AiPerfLog.wallClock = { System.currentTimeMillis() }
    }

    @Test
    fun 記録は古い順に読める() {
        AiPerfLog.record("fetch", 1_200, bookmarkId = 1L)
        now += 10
        AiPerfLog.record("analysis", 40_000, detail = "local", bookmarkId = 1L)

        val recent = AiPerfLog.recent()
        assertEquals(listOf("fetch", "analysis"), recent.map { it.stage })
        assertEquals(listOf(1_200L, 40_000L), recent.map { it.durationMs })
        assertEquals("local", recent[1].detail)
    }

    @Test
    fun 件数の上限を超えたら古いものから捨てる() {
        repeat(AiPerfLog.CAPACITY + 5) { AiPerfLog.record("s$it", it.toLong()) }

        val recent = AiPerfLog.recent()
        assertEquals(AiPerfLog.CAPACITY, recent.size)
        assertEquals("s5", recent.first().stage)
        assertEquals("s${AiPerfLog.CAPACITY + 4}", recent.last().stage)
    }

    @Test
    fun measureは結果を返し_所要時間を記録する() = runBlocking {
        val result = AiPerfLog.measure("embedding") { 42 }

        assertEquals(42, result)
        val entry = AiPerfLog.recent().single()
        assertEquals("embedding", entry.stage)
        assertTrue(entry.durationMs >= 0)
        assertNull(entry.bookmarkId)
    }

    @Test
    fun measureは失敗しても記録して例外をそのまま投げる() = runBlocking {
        val failure = runCatching { AiPerfLog.measure<Unit>("analysis") { error("boom") } }.exceptionOrNull()

        assertEquals("boom", (failure as IllegalStateException).message)
        assertEquals(listOf("analysis"), AiPerfLog.recent().map { it.stage })
    }

    @Test
    fun コンテキストのトレースでブックマークに結びつく() = runBlocking {
        withContext(AiPerfLog.trace(7L)) {
            AiPerfLog.measure("extract") { }
            // 内側のコルーチンにも引き継がれる
            withContext(Dispatchers.Default) { AiPerfLog.measure("llm.generate") { } }
        }
        AiPerfLog.measure("fetch") { }

        assertEquals(listOf(7L, 7L, null), AiPerfLog.recent().map { it.bookmarkId })
    }

    @Test
    fun 要約は段ごとに合計して_totalを先頭に出す() {
        AiPerfLog.record("fetch", 1_200, bookmarkId = 3L)
        AiPerfLog.record("llm.generate", 20_000, detail = "part 1/2", bookmarkId = 3L)
        AiPerfLog.record("llm.generate", 15_000, detail = "final", bookmarkId = 3L)
        AiPerfLog.record("embedding", 1_100, bookmarkId = 3L)
        AiPerfLog.record("total", 38_000, bookmarkId = 3L)
        AiPerfLog.record("fetch", 9_999, bookmarkId = 4L)

        assertEquals(
            "bookmark#3 total=38.0s [fetch=1.2s llm.generate=35.0s(x2) embedding=1.1s]",
            AiPerfLog.summaryOf(3L)
        )
    }

    @Test
    fun 要約は期間より古い記録と他のブックマークを含めない() {
        AiPerfLog.record("fetch", 1_000, bookmarkId = 1L)
        now += 31 * 60 * 1000L
        AiPerfLog.record("analysis", 2_000, bookmarkId = 1L)

        assertEquals("bookmark#1 [analysis=2.0s]", AiPerfLog.summaryOf(1L))
        assertNull(AiPerfLog.summaryOf(2L))
    }
}
