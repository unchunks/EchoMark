package com.unchunks.echomark.domain.usage

import com.unchunks.components.llm.ApiProvider
import java.time.LocalDate

/** 100 万トークンあたりの価格(米ドル)。 */
data class TokenPrice(val inputUsdPerMillion: Double, val outputUsdPerMillion: Double) {
    /** [inputTokens]・[outputTokens] の費用(米ドル) */
    fun costUsd(inputTokens: Long, outputTokens: Long): Double =
        (inputTokens * inputUsdPerMillion + outputTokens * outputUsdPerMillion) / TOKENS_PER_MILLION

    private companion object {
        const val TOKENS_PER_MILLION = 1_000_000.0
    }
}

/**
 * 価格の1行。
 * @property effectiveFrom この日から適用する(null なら以前から)。値上げの予告など、日付で変わる価格に使う
 * @property maxInputTokens 1回の呼び出しの入力がこのトークン数以下のときの価格(null なら上限なし)。
 *   入力の長さで単価が変わるモデルは、短い方から順に並べる
 */
data class PriceRule(
    val price: TokenPrice,
    val maxInputTokens: Long? = null,
    val effectiveFrom: LocalDate? = null
)

/**
 * 各社の公式の価格表から写した、モデルごとの価格(#69)。費用の目安にだけ使う。
 *
 * - 価格を確かめられたモデルだけを載せる。載っていないモデル(自由入力したモデル ID など)の費用は出さない(トークン数だけ出す)。
 *   推測で価格を入れない
 * - 価格は変わるため、[CHECKED_ON] に公式のページで確かめた。変わったらこの表と [CHECKED_ON] を直す
 * - 米ドルのまま扱う(為替は見積もらない)。バッチ・キャッシュ・優先処理の割引や割増は含めない(通常の価格)
 * - 出力の価格には、思考・推論のトークンも含まれる(3社とも出力として課金される)。
 *   ただし OpenAI の価格表は推論トークンの扱いを明記していないため、OpenAI の出力の費用は下限の目安になる
 */
object ApiPricing {
    /** 価格を公式のページで確かめた日 */
    val CHECKED_ON: LocalDate = LocalDate.of(2026, 10, 30)

    /** 提供元ごとの、価格を確かめた公式のページ */
    val SOURCE_URLS: Map<ApiProvider, String> = mapOf(
        ApiProvider.CLAUDE to "https://platform.claude.com/docs/en/about-claude/pricing",
        ApiProvider.GEMINI to "https://ai.google.dev/gemini-api/docs/pricing",
        ApiProvider.OPENAI to "https://developers.openai.com/api/docs/pricing"
    )

    private fun price(input: Double, output: Double) = TokenPrice(input, output)

    /**
     * モデル ID ごとの価格。Claude の価格表はモデルの表示名(「Claude Opus 5.5」など)で載っているため、
     * モデル ID との対応は表示名から読み替えている。
     */
    private val RULES: Map<Pair<ApiProvider, String>, List<PriceRule>> = mapOf(
        // Claude(https://platform.claude.com/docs/en/about-claude/pricing の Base input tokens・Output tokens)
        (ApiProvider.CLAUDE to "claude-opus-5-5") to listOf(PriceRule(price(4.0, 20.0))),
        (ApiProvider.CLAUDE to "claude-sonnet-5-5") to listOf(PriceRule(price(2.0, 10.0))),
        // Haiku 5.5 は、1回の呼び出しの入力が 100,000 トークンを超えると単価が上がる
        (ApiProvider.CLAUDE to "claude-haiku-5-5") to listOf(
            PriceRule(price(0.10, 0.50), maxInputTokens = 100_000),
            PriceRule(price(0.50, 2.50))
        ),
        (ApiProvider.CLAUDE to "claude-haiku-4-5") to listOf(PriceRule(price(1.0, 5.0))),
        // Gemini(https://ai.google.dev/gemini-api/docs/pricing の Standard・有料)
        // 3.8 Flash は 2027-01-01 から値上げすると公式のページに載っている
        (ApiProvider.GEMINI to "gemini-3.8-flash") to listOf(
            PriceRule(price(0.75, 3.75)),
            PriceRule(price(1.50, 7.50), effectiveFrom = LocalDate.of(2027, 1, 1))
        ),
        (ApiProvider.GEMINI to "gemini-3.5-flash-lite") to listOf(PriceRule(price(0.30, 2.50))),
        (ApiProvider.GEMINI to "gemini-3.1-pro-preview") to listOf(
            PriceRule(price(2.0, 12.0), maxInputTokens = 200_000),
            PriceRule(price(4.0, 18.0))
        ),
        // OpenAI(https://developers.openai.com/api/docs/pricing の Standard)。入力が 272K トークンを超えると単価が上がる
        (ApiProvider.OPENAI to "gpt-6.1-sol") to listOf(
            PriceRule(price(2.0, 10.0), maxInputTokens = 272_000),
            PriceRule(price(4.0, 15.0))
        ),
        (ApiProvider.OPENAI to "gpt-6-astra") to listOf(
            PriceRule(price(10.0, 50.0), maxInputTokens = 272_000),
            PriceRule(price(20.0, 75.0))
        ),
        (ApiProvider.OPENAI to "gpt-6-luna") to listOf(
            PriceRule(price(0.10, 0.50), maxInputTokens = 272_000),
            PriceRule(price(0.20, 0.75))
        )
    )

    /** 価格を確かめたモデルか。 */
    fun hasPrice(provider: ApiProvider, model: String): Boolean = (provider to model) in RULES

    /**
     * [date] に、入力が [inputTokens] トークンの呼び出しにかかる価格。表に無いモデルは null(価格が分からない)。
     * 日付で変わる価格は、[date] に適用されている最新のものを使う。
     */
    fun priceOf(provider: ApiProvider, model: String, inputTokens: Long, date: LocalDate): TokenPrice? {
        val rules = RULES[provider to model] ?: return null
        val effectiveFrom = rules.mapNotNull { it.effectiveFrom }.filter { !it.isAfter(date) }.maxOrNull()
        val inEffect = rules.filter { it.effectiveFrom == effectiveFrom }
        return (inEffect.firstOrNull { it.maxInputTokens != null && inputTokens <= it.maxInputTokens }
            ?: inEffect.firstOrNull { it.maxInputTokens == null })?.price
    }
}
