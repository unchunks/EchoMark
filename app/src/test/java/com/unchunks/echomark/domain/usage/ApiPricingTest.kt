package com.unchunks.echomark.domain.usage

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.provider.presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ApiPricingTest {

    private val day = LocalDate.of(2026, 10, 30)

    @Test
    fun AI設定で選べるモデルはすべて価格を載せている() {
        // プリセットを足したのに価格を足し忘れると、そのモデルの費用だけ出なくなる
        val missing = ApiProvider.entries.flatMap { provider ->
            provider.presets.map { provider to it.id }.filterNot { (p, id) -> ApiPricing.hasPrice(p, id) }
        }
        assertEquals(emptyList<Pair<ApiProvider, String>>(), missing)
    }

    @Test
    fun 価格は100万トークンあたりで入力と出力を別に計算する() {
        val price = ApiPricing.priceOf(ApiProvider.CLAUDE, "claude-opus-5-5", 1_000, day)!!

        assertEquals(TokenPrice(4.0, 20.0), price)
        // 入力 200 万 + 出力 100 万 = 4 * 2 + 20 * 1
        assertEquals(28.0, price.costUsd(2_000_000, 1_000_000), 1e-9)
        assertEquals(0.0, price.costUsd(0, 0), 0.0)
    }

    @Test
    fun 入力が長いと単価が上がるモデルは境目で切り替わる() {
        fun haiku(input: Long) = ApiPricing.priceOf(ApiProvider.CLAUDE, "claude-haiku-5-5", input, day)

        assertEquals(TokenPrice(0.10, 0.50), haiku(100_000))
        assertEquals(TokenPrice(0.50, 2.50), haiku(100_001))
        assertEquals(TokenPrice(2.0, 12.0), ApiPricing.priceOf(ApiProvider.GEMINI, "gemini-3.1-pro-preview", 200_000, day))
        assertEquals(TokenPrice(4.0, 18.0), ApiPricing.priceOf(ApiProvider.GEMINI, "gemini-3.1-pro-preview", 200_001, day))
        assertEquals(TokenPrice(2.0, 10.0), ApiPricing.priceOf(ApiProvider.OPENAI, "gpt-6.1-sol", 272_000, day))
        assertEquals(TokenPrice(4.0, 15.0), ApiPricing.priceOf(ApiProvider.OPENAI, "gpt-6.1-sol", 272_001, day))
    }

    @Test
    fun 値上げの日付より前は今の価格_日付からは新しい価格() {
        fun flash(date: LocalDate) = ApiPricing.priceOf(ApiProvider.GEMINI, "gemini-3.8-flash", 1_000, date)

        assertEquals(TokenPrice(0.75, 3.75), flash(day))
        assertEquals(TokenPrice(0.75, 3.75), flash(LocalDate.of(2026, 12, 31)))
        assertEquals(TokenPrice(1.50, 7.50), flash(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun 載っていないモデルの価格は出さない() {
        assertNull(ApiPricing.priceOf(ApiProvider.CLAUDE, "claude-my-custom-model", 1_000, day))
        // 提供元が違えば、同じ名前でも別のモデル
        assertNull(ApiPricing.priceOf(ApiProvider.OPENAI, "claude-opus-5-5", 1_000, day))
        assertFalse(ApiPricing.hasPrice(ApiProvider.GEMINI, "gemini-3.8-flash-preview-02"))
        assertTrue(ApiPricing.hasPrice(ApiProvider.GEMINI, "gemini-3.8-flash"))
    }

    @Test
    fun 価格を確かめた日と出どころを持つ() {
        assertEquals(LocalDate.of(2026, 10, 30), ApiPricing.CHECKED_ON)
        assertEquals(ApiProvider.entries.toSet(), ApiPricing.SOURCE_URLS.keys)
        assertTrue(ApiPricing.SOURCE_URLS.values.all { it.startsWith("https://") })
    }
}
