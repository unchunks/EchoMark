package com.unchunks.echomark.domain.usage

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.repository.AiTask
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * クラウド API の1回の呼び出しで使ったトークン数の記録(#69)。
 * [outputTokens] は思考・推論のトークンを含む。記録するのは各社の応答に入っていた値だけで、推測した値は入れない。
 */
data class ApiUsageRecord(
    /** 呼び出した日時(epoch ミリ秒) */
    val createdAt: Long,
    val provider: ApiProvider,
    val model: String,
    val task: AiTask,
    val inputTokens: Long,
    val outputTokens: Long
)

/** クラウド API の使用量の記録(端末の中だけに持つ。バックアップには含めない)。 */
interface ApiUsageRepository {
    /** 1回の呼び出しの使用量を記録する。古い記録は掃除する。 */
    suspend fun record(usage: ApiUsageRecord)

    /** [from](epoch ミリ秒)以降の記録(古い順) */
    fun observeSince(from: Long): Flow<List<ApiUsageRecord>>
}

/** 使用量の集計の1行(提供元・モデルごと)。 */
data class ApiUsageLine(
    val provider: ApiProvider,
    val model: String,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    /** 費用の目安(米ドル)。モデルの価格が分からなければ null */
    val estimatedCostUsd: Double?
)

/**
 * ある期間の使用量の集計。
 * @property estimatedCostUsd 価格の分かるモデルの費用の合計(米ドル)。価格の分かるモデルが無ければ null
 * @property hasUnpricedUsage 価格の分からないモデルの使用量があり、[estimatedCostUsd] に含まれていない
 */
data class ApiUsageSummary(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimatedCostUsd: Double? = null,
    val hasUnpricedUsage: Boolean = false,
    val lines: List<ApiUsageLine> = emptyList()
)

/**
 * [records] を集計する。費用は呼び出しごとに、その日([zone] での日付)の価格・その呼び出しの入力の長さの単価で計算して足す
 * (入力の長さや日付で単価が変わるモデルがあるため、合計トークン数から一度に計算しない)。
 * 行は費用の多い順(価格が分からないものは後ろ)、同じなら使ったトークンの多い順。
 */
fun summarizeApiUsage(records: List<ApiUsageRecord>, zone: ZoneId = ZoneId.systemDefault()): ApiUsageSummary {
    if (records.isEmpty()) return ApiUsageSummary()
    val lines = records.groupBy { it.provider to it.model }.map { (key, group) ->
        val (provider, model) = key
        val costs = group.map { record ->
            ApiPricing.priceOf(
                provider, model, record.inputTokens,
                Instant.ofEpochMilli(record.createdAt).atZone(zone).toLocalDate()
            )?.costUsd(record.inputTokens, record.outputTokens)
        }
        ApiUsageLine(
            provider = provider,
            model = model,
            calls = group.size,
            inputTokens = group.sumOf { it.inputTokens },
            outputTokens = group.sumOf { it.outputTokens },
            // 1回でも価格が分からない(価格の無い日付など)ならそのモデルの費用は出さない
            estimatedCostUsd = if (costs.all { it != null }) costs.sumOf { it!! } else null
        )
    }.sortedWith(
        compareByDescending<ApiUsageLine> { it.estimatedCostUsd != null }
            .thenByDescending { it.estimatedCostUsd ?: 0.0 }
            .thenByDescending { it.inputTokens + it.outputTokens }
    )
    val priced = lines.mapNotNull { it.estimatedCostUsd }
    return ApiUsageSummary(
        calls = lines.sumOf { it.calls },
        inputTokens = lines.sumOf { it.inputTokens },
        outputTokens = lines.sumOf { it.outputTokens },
        estimatedCostUsd = priced.takeIf { it.isNotEmpty() }?.sum(),
        hasUnpricedUsage = lines.any { it.estimatedCostUsd == null },
        lines = lines
    )
}

/** 使用量を見る期間。 */
enum class ApiUsagePeriod(val label: String) {
    TODAY("今日"),
    LAST_7_DAYS("直近7日"),
    THIS_MONTH("今月")
}

/** 期間ごとの使用量の集計。 */
data class ApiUsagePeriods(
    val today: ApiUsageSummary = ApiUsageSummary(),
    val last7Days: ApiUsageSummary = ApiUsageSummary(),
    val thisMonth: ApiUsageSummary = ApiUsageSummary()
) {
    operator fun get(period: ApiUsagePeriod): ApiUsageSummary = when (period) {
        ApiUsagePeriod.TODAY -> today
        ApiUsagePeriod.LAST_7_DAYS -> last7Days
        ApiUsagePeriod.THIS_MONTH -> thisMonth
    }
}

/**
 * [records] を期間ごとに集計する。期間は [zone] の暦で、今日は今日の 0 時から、直近7日は今日を含む7日(6日前の 0 時から)、
 * 今月は今月 1 日の 0 時から([nowMillis] まで)。
 */
fun summarizeByPeriod(records: List<ApiUsageRecord>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ApiUsagePeriods {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    fun startOf(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val todayStart = startOf(today)
    val weekStart = startOf(today.minusDays(6))
    val monthStart = startOf(today.withDayOfMonth(1))
    fun summarize(from: Long) = summarizeApiUsage(records.filter { it.createdAt in from..nowMillis }, zone)
    return ApiUsagePeriods(today = summarize(todayStart), last7Days = summarize(weekStart), thisMonth = summarize(monthStart))
}

/** [summarizeByPeriod] に必要な、記録を読み始める時刻(期間のうち一番古い始まり)。 */
fun apiUsageReadFrom(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return minOf(today.minusDays(6), today.withDayOfMonth(1)).atStartOfDay(zone).toInstant().toEpochMilli()
}
