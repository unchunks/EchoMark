package com.unchunks.echomark.domain.usage

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.repository.AiTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class ApiUsageSummaryTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun millis(date: LocalDate, hour: Int = 12) =
        LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private fun record(
        createdAt: Long,
        provider: ApiProvider = ApiProvider.CLAUDE,
        model: String = "claude-opus-5-5",
        task: AiTask = AiTask.SUMMARY,
        input: Long = 1_000_000,
        output: Long = 100_000
    ) = ApiUsageRecord(createdAt, provider, model, task, input, output)

    private val day = LocalDate.of(2026, 10, 30)

    @Test
    fun 記録が無ければ空の集計() {
        assertEquals(ApiUsageSummary(), summarizeApiUsage(emptyList(), zone))
    }

    @Test
    fun 回数とトークン数を足し_費用は呼び出しごとに計算する() {
        val summary = summarizeApiUsage(
            listOf(record(millis(day)), record(millis(day), input = 500_000, output = 0)),
            zone
        )

        assertEquals(2, summary.calls)
        assertEquals(1_500_000L, summary.inputTokens)
        assertEquals(100_000L, summary.outputTokens)
        // Opus: 入力 4 ドル / 100 万・出力 20 ドル / 100 万 → (4 + 2) + 2
        assertEquals(8.0, summary.estimatedCostUsd!!, 1e-9)
        assertFalse(summary.hasUnpricedUsage)
    }

    @Test
    fun 入力の長さで変わる単価は呼び出しごとに当てはめる() {
        // 10 万トークン以下の呼び出し 2 回と、超える 1 回。合計の 25 万で一度に計算すると単価を誤る
        val summary = summarizeApiUsage(
            listOf(
                record(millis(day), model = "claude-haiku-5-5", input = 50_000, output = 0),
                record(millis(day), model = "claude-haiku-5-5", input = 50_000, output = 0),
                record(millis(day), model = "claude-haiku-5-5", input = 150_000, output = 0)
            ),
            zone
        )

        // 5 万 * 0.10 / 100 万 * 2 + 15 万 * 0.50 / 100 万
        assertEquals(0.01 + 0.075, summary.estimatedCostUsd!!, 1e-9)
    }

    @Test
    fun 価格が分からないモデルはトークン数だけで_費用の合計に含めない() {
        val summary = summarizeApiUsage(
            listOf(record(millis(day)), record(millis(day), model = "my-custom-model", input = 9_000_000)),
            zone
        )

        assertEquals(2, summary.calls)
        assertEquals(10_000_000L, summary.inputTokens)
        assertTrue(summary.hasUnpricedUsage)
        assertEquals(4.0 + 2.0, summary.estimatedCostUsd!!, 1e-9)
        val custom = summary.lines.single { it.model == "my-custom-model" }
        assertNull(custom.estimatedCostUsd)
        // 費用の分かる行が先
        assertEquals(listOf("claude-opus-5-5", "my-custom-model"), summary.lines.map { it.model })
    }

    @Test
    fun 価格の分かるモデルが無ければ費用は出さない() {
        val summary = summarizeApiUsage(listOf(record(millis(day), model = "my-custom-model")), zone)

        assertNull(summary.estimatedCostUsd)
        assertTrue(summary.hasUnpricedUsage)
    }

    @Test
    fun 値上げをまたぐ記録は呼び出した日の価格で計算する() {
        val summary = summarizeApiUsage(
            listOf(
                record(millis(LocalDate.of(2026, 12, 31)), ApiProvider.GEMINI, "gemini-3.8-flash", input = 1_000_000, output = 0),
                record(millis(LocalDate.of(2027, 1, 1)), ApiProvider.GEMINI, "gemini-3.8-flash", input = 1_000_000, output = 0)
            ),
            zone
        )

        assertEquals(0.75 + 1.50, summary.estimatedCostUsd!!, 1e-9)
    }

    @Test
    fun 提供元とモデルごとの行に分ける() {
        val summary = summarizeApiUsage(
            listOf(
                record(millis(day), ApiProvider.CLAUDE, "claude-haiku-5-5", input = 1000, output = 100),
                record(millis(day), ApiProvider.GEMINI, "gemini-3.5-flash-lite", input = 2000, output = 200),
                record(millis(day), ApiProvider.CLAUDE, "claude-haiku-5-5", input = 3000, output = 300)
            ),
            zone
        )

        assertEquals(2, summary.lines.size)
        val haiku = summary.lines.single { it.provider == ApiProvider.CLAUDE }
        assertEquals(2, haiku.calls)
        assertEquals(4000L, haiku.inputTokens)
        assertEquals(400L, haiku.outputTokens)
    }

    // ---- 期間 ----

    @Test
    fun 期間は今日_直近7日_今月に分ける() {
        val now = millis(day, hour = 15)
        val records = listOf(
            record(millis(day, 0)), // 今日の 0 時
            record(millis(day.minusDays(6), 0)), // 6 日前の 0 時(直近7日の最初)
            record(millis(day.minusDays(7), 23)), // 7 日前(直近7日の外)。同じ月
            record(millis(LocalDate.of(2026, 10, 1), 0)), // 今月の最初
            record(millis(LocalDate.of(2026, 9, 30), 23)) // 先月(どれにも入らない)
        )

        val periods = summarizeByPeriod(records, now, zone)

        assertEquals(1, periods.today.calls)
        assertEquals(2, periods.last7Days.calls)
        assertEquals(4, periods.thisMonth.calls)
        assertEquals(periods.today, periods[ApiUsagePeriod.TODAY])
        assertEquals(periods.thisMonth, periods[ApiUsagePeriod.THIS_MONTH])
    }

    @Test
    fun 月の初めは直近7日が先月にまたがる() {
        val firstDays = LocalDate.of(2026, 11, 2)
        val now = millis(firstDays, 9)
        val records = listOf(record(millis(LocalDate.of(2026, 10, 30))), record(millis(firstDays, 8)))

        val periods = summarizeByPeriod(records, now, zone)

        assertEquals(2, periods.last7Days.calls)
        // 今月は 11/1 から
        assertEquals(1, periods.thisMonth.calls)
        // 読み始める時刻は、直近7日の始まり(今月の初めより前)
        assertEquals(millis(LocalDate.of(2026, 10, 27), 0), apiUsageReadFrom(now, zone))
    }

    @Test
    fun 今より後の記録は数えない() {
        val now = millis(day, 10)

        val periods = summarizeByPeriod(listOf(record(millis(day, 11))), now, zone)

        assertEquals(0, periods.today.calls)
    }
}
