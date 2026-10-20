package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 長い本文の分割要約(map 側): 呼び出し回数・渡す部分・間引き・時間の目安・失敗の扱い。 */
class LongTextDigesterTest {

    private val config = LongTextConfig(chunkChars = 100, maxChunks = 3, noteMaxChars = 20, timeBudgetMillis = 1_000)

    /** 渡された部分を記録し、部分の番号をメモとして返す LLM の代わり。 */
    private class RecordingSummarizer(private val clock: FakeClock? = null) {
        val parts = mutableListOf<TextPart>()
        var failOn: Map<Int, Exception> = emptyMap()

        suspend operator fun invoke(part: TextPart): String {
            parts += part
            clock?.advance(400)
            failOn[part.number]?.let { throw it }
            return "要点${part.number}"
        }
    }

    private class FakeClock {
        var now = 0L
        fun advance(ms: Long) { now += ms }
    }

    /** 1段落 90 文字の段落を [count] 個並べた本文(1段落が1部分になる)。 */
    private fun paragraphs(count: Int) = (1..count).joinToString("\n\n") { n -> "$n" + "あ".repeat(89) }

    @Test
    fun 上限以下の本文はAIを呼ばずにそのまま返す() = runBlocking {
        val summarizer = RecordingSummarizer()

        val body = LongTextDigester(config).prepare("短い本文") { summarizer(it) }

        assertEquals(PreparedBody.Whole("短い本文"), body)
        assertTrue(summarizer.parts.isEmpty())
    }

    @Test
    fun 長い本文は部分ごとに要約してメモにまとめる() = runBlocking {
        val summarizer = RecordingSummarizer()

        val body = LongTextDigester(config).prepare(paragraphs(2)) { summarizer(it) }

        assertEquals(listOf(1, 2), summarizer.parts.map { it.number })
        assertTrue(summarizer.parts.all { it.total == 2 && it.text.length <= config.chunkChars })
        assertTrue(summarizer.parts[1].text.startsWith("2"))
        body as PreparedBody.Digest
        assertEquals(2, body.usedParts)
        assertEquals(2, body.totalParts)
        assertEquals(
            "長い本文を2個の部分に分けて要約したメモです。\n\n[部分 1/2]\n要点1\n\n[部分 2/2]\n要点2",
            body.text
        )
    }

    @Test
    fun 部分が多すぎるときは先頭と末尾を含めて間引く() = runBlocking {
        val summarizer = RecordingSummarizer()

        val body = LongTextDigester(config).prepare(paragraphs(7)) { summarizer(it) }

        // 呼び出しは上限(3 回)まで
        assertEquals(listOf(1, 4, 7), summarizer.parts.map { it.number })
        body as PreparedBody.Digest
        assertTrue(body.text, body.text.contains("(長さと時間の都合で4個の部分は省略)"))
        assertTrue(body.text, body.text.contains("[部分 7/7]\n要点7"))
    }

    @Test
    fun 時間の目安を超えたら残りの部分を飛ばす() = runBlocking {
        val clock = FakeClock()
        val summarizer = RecordingSummarizer(clock)

        // 1部分に 400ms かかる。3部分目の開始時点で 800ms(目安 1,000ms 以内)、4部分目は 1,200ms で飛ばす
        val body = LongTextDigester(config.copy(maxChunks = 5), clock = { clock.now })
            .prepare(paragraphs(5)) { summarizer(it) }

        assertEquals(3, summarizer.parts.size)
        assertEquals(3, (body as PreparedBody.Digest).usedParts)
    }

    @Test
    fun 部分の時間切れは飛ばし_それ以外の失敗は投げる() = runBlocking {
        val summarizer = RecordingSummarizer().apply { failOn = mapOf(1 to LlmException.Timeout(1L)) }

        val body = LongTextDigester(config).prepare(paragraphs(2)) { summarizer(it) }

        assertEquals(1, (body as PreparedBody.Digest).usedParts)
        assertTrue(body.text.contains("[部分 2/2]\n要点2"))

        val failing = RecordingSummarizer().apply { failOn = mapOf(2 to LlmException.ServerError(503)) }
        try {
            LongTextDigester(config).prepare(paragraphs(2)) { failing(it) }
            fail("例外が投げられるはず")
        } catch (e: LlmException.ServerError) {
            // 再試行で回復しうる失敗は、ワーカーに再試行させる
        }
    }

    @Test
    fun どの部分の要約も得られなければ元の本文を返す() = runBlocking {
        val text = paragraphs(2)

        val body = LongTextDigester(config).prepare(text) { " " }

        assertEquals(PreparedBody.Whole(text), body)
    }

    @Test
    fun 長すぎるメモはまとめに収まるよう切り詰める() = runBlocking {
        val body = LongTextDigester(config).prepare(paragraphs(2)) { "い".repeat(100) }

        // 指示した文字数(20)の 130% まで
        assertTrue(body.text, body.text.contains("[部分 1/2]\n" + "い".repeat(26) + "\n"))
    }

    @Test
    fun 分けずに渡せる上限が部分の長さより大きければ_その長さまでAIを呼ばない() = runBlocking {
        val summarizer = RecordingSummarizer()
        val text = paragraphs(3) // 約 272 文字(1部分は 100 文字以下)

        val whole = LongTextDigester(config.copy(singleCallChars = 300)).prepare(text) { summarizer(it) }
        assertEquals(PreparedBody.Whole(text), whole)
        assertTrue(summarizer.parts.isEmpty())

        // 上限を超えたら、部分の長さ(chunkChars)で分ける
        val digest = LongTextDigester(config.copy(singleCallChars = 200)).prepare(text) { summarizer(it) }
        assertEquals(3, (digest as PreparedBody.Digest).totalParts)
    }

    // ---- 並行 ----

    /** 部分ごとに [delays] の時間(仮想時間)をかけて、同時に実行中の数の最大を記録する LLM の代わり。 */
    private class DelayedSummarizer(private val delays: (Int) -> Long = { 100L }) {
        val started = mutableListOf<Int>()
        var running = 0
        var maxRunning = 0
        var failOn: Map<Int, Exception> = emptyMap()
        var cancelled = mutableListOf<Int>()

        suspend operator fun invoke(part: TextPart): String {
            started += part.number
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                delay(delays(part.number))
                failOn[part.number]?.let { throw it }
                return "要点${part.number}"
            } catch (e: CancellationException) {
                cancelled += part.number
                throw e
            } finally {
                running--
            }
        }
    }

    @Test
    fun 並行数を指定すると部分要約を同時に進める_順番と結果は変わらない() = runTest {
        val sequential = DelayedSummarizer()
        val expected = LongTextDigester(config.copy(maxChunks = 8)).prepare(paragraphs(8)) { sequential(it) }
        val sequentialMillis = currentTime

        val parallel = DelayedSummarizer()
        val body = LongTextDigester(config.copy(maxChunks = 8, parallelism = 4)).prepare(paragraphs(8)) { parallel(it) }
        val parallelMillis = currentTime - sequentialMillis

        assertEquals(expected, body)
        assertEquals(1, sequential.maxRunning)
        assertEquals(4, parallel.maxRunning)
        // 8 部分 × 100ms: 順番なら 800ms、4 並行なら 200ms
        assertEquals(800L, sequentialMillis)
        assertEquals(200L, parallelMillis)
    }

    @Test
    fun 並行でも部分の終わる順に関わらずメモは部分の順に並ぶ() = runTest {
        // 前の部分ほど時間がかかる(終わる順は逆)
        val summarizer = DelayedSummarizer { number -> (5 - number) * 100L }

        val body = LongTextDigester(config.copy(maxChunks = 4, parallelism = 4)).prepare(paragraphs(4)) { summarizer(it) }

        assertEquals(listOf(1, 2, 3, 4), summarizer.started)
        assertEquals(
            "長い本文を4個の部分に分けて要約したメモです。\n\n[部分 1/4]\n要点1\n\n[部分 2/4]\n要点2" +
                "\n\n[部分 3/4]\n要点3\n\n[部分 4/4]\n要点4",
            body.text
        )
    }

    @Test
    fun 並行数より部分が少なければ部分の数だけ同時に進める() = runTest {
        val summarizer = DelayedSummarizer()

        LongTextDigester(config.copy(parallelism = 10)).prepare(paragraphs(2)) { summarizer(it) }

        assertEquals(2, summarizer.maxRunning)
    }

    @Test
    fun 並行でも時間の目安を超えたら始めていない部分を飛ばす() = runTest {
        // 1部分 600ms。2並行: 1・2 部分目は 0ms、3・4 部分目は 600ms(目安 1,000ms 以内)、5・6 部分目は 1,200ms で飛ばす
        val summarizer = DelayedSummarizer { 600L }

        val body = LongTextDigester(config.copy(maxChunks = 6, parallelism = 2), clock = { currentTime })
            .prepare(paragraphs(6)) { summarizer(it) }

        assertEquals(listOf(1, 2, 3, 4), summarizer.started)
        assertEquals(4, (body as PreparedBody.Digest).usedParts)
        assertEquals(6, body.totalParts)
        assertEquals(1_200L, currentTime)
    }

    @Test
    fun 並行でも部分の時間切れは飛ばす() = runTest {
        val summarizer = DelayedSummarizer().apply { failOn = mapOf(2 to LlmException.Timeout(1L)) }

        val body = LongTextDigester(config.copy(maxChunks = 3, parallelism = 3)).prepare(paragraphs(3)) { summarizer(it) }

        assertEquals(2, (body as PreparedBody.Digest).usedParts)
        assertTrue(body.text, body.text.contains("[部分 1/3]\n要点1\n\n[部分 3/3]\n要点3"))
    }

    @Test
    fun 並行でもそれ以外の失敗は投げ_ほかの部分はキャンセルする() = runTest {
        // 2 部分目が 100ms で失敗する間、1・3 部分目は 1,000ms かかる
        val summarizer = DelayedSummarizer { number -> if (number == 2) 100L else 1_000L }
            .apply { failOn = mapOf(2 to LlmException.ServerError(503)) }

        try {
            LongTextDigester(config.copy(maxChunks = 3, parallelism = 3)).prepare(paragraphs(3)) { summarizer(it) }
            fail("例外が投げられるはず")
        } catch (e: LlmException.ServerError) {
            // 失敗した時点で、ほかの部分の呼び出しは打ち切る(料金・時間を無駄にしない)
        }
        assertEquals(listOf(1, 3), summarizer.cancelled.sorted())
        assertEquals(100L, currentTime)
    }
}
