package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.runBlocking
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
}
