package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.usage.ApiUsageLine
import com.unchunks.echomark.domain.usage.ApiUsagePeriods
import com.unchunks.echomark.domain.usage.ApiUsageSummary
import com.unchunks.echomark.ui.settings.ApiUsageCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 設定の「クラウド AI の使用量」の欄(#69)。クラウド AI を使えない間は設定画面に出ないため、欄だけを撮る。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApiUsageScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Composable
    private fun Section(usage: ApiUsagePeriods) {
        Surface {
            Box { ApiUsageCard(usage) }
        }
    }

    private val priced = ApiUsageSummary(
        calls = 42,
        inputTokens = 1_250_000,
        outputTokens = 96_000,
        estimatedCostUsd = 0.19,
        lines = listOf(
            ApiUsageLine(ApiProvider.CLAUDE, "claude-haiku-5-5", 38, 1_100_000, 60_000, 0.14),
            ApiUsageLine(ApiProvider.GEMINI, "gemini-3.5-flash-lite", 4, 150_000, 36_000, 0.05)
        )
    )

    /** 価格の分かるモデルだけを使った月(既定の選択は「今月」) */
    @Test
    fun priced() = screenshot.captureLightDark("settings_api_usage") {
        Section(ApiUsagePeriods(thisMonth = priced))
    }

    /** 価格を確認できていないモデル(自由入力)も使った月。費用は一部のみ、そのモデルはトークン数だけ */
    @Test
    fun partlyUnpriced() = screenshot.captureLightDark("settings_api_usage_partial") {
        Section(
            ApiUsagePeriods(
                thisMonth = priced.copy(
                    calls = 45,
                    inputTokens = 1_290_000,
                    hasUnpricedUsage = true,
                    lines = priced.lines + ApiUsageLine(ApiProvider.OPENAI, "my-custom-model", 3, 40_000, 9_000, null)
                )
            )
        )
    }

    /** 価格を確認できていないモデルだけ。トークン数だけ出す */
    @Test
    fun unpricedOnly() = screenshot.captureLightDark("settings_api_usage_unpriced") {
        Section(
            ApiUsagePeriods(
                thisMonth = ApiUsageSummary(
                    calls = 3,
                    inputTokens = 40_000,
                    outputTokens = 9_000,
                    hasUnpricedUsage = true,
                    lines = listOf(ApiUsageLine(ApiProvider.OPENAI, "my-custom-model", 3, 40_000, 9_000, null))
                )
            )
        )
    }

    @Test
    fun empty() = screenshot.captureLightDark("settings_api_usage_empty") {
        Section(ApiUsagePeriods())
    }
}
