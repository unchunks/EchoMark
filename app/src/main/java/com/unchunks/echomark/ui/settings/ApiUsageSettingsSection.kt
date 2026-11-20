package com.unchunks.echomark.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.usage.ApiPricing
import com.unchunks.echomark.domain.usage.ApiUsageLine
import com.unchunks.echomark.domain.usage.ApiUsagePeriod
import com.unchunks.echomark.domain.usage.ApiUsagePeriods
import com.unchunks.echomark.domain.usage.ApiUsageSummary
import com.unchunks.echomark.ui.components.SectionHeader
import java.text.NumberFormat
import java.util.Locale

/*
 * 設定画面の「クラウド AI の使用量」の欄(#69)。期間ごとのトークン数と、費用の目安を出す。
 * クラウド AI を使えない間(CLOUD_AI_ENABLED)は、SettingsContent が出さない。
 */

/** 「クラウド AI の使用量」の欄。 */
internal fun LazyListScope.apiUsageItems(usage: ApiUsagePeriods) {
    item { SectionHeader("クラウド AI の使用量") }
    item { ApiUsageCard(usage) }
}

@Composable
internal fun ApiUsageCard(usage: ApiUsagePeriods, modifier: Modifier = Modifier) {
    var period by rememberSaveable { mutableStateOf(ApiUsagePeriod.THIS_MONTH) }
    val summary = usage[period]
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ApiUsagePeriod.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = period == entry,
                    onClick = { period = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, ApiUsagePeriod.entries.size)
                ) {
                    Text(entry.label)
                }
            }
        }
        if (summary.calls == 0) {
            Text(
                "この期間の使用はありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ApiUsageTotals(summary)
            summary.lines.forEach { ApiUsageLineRow(it) }
        }
        Text(
            apiUsageNote(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ApiUsageTotals(summary: ApiUsageSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            costLabel(summary.estimatedCostUsd, summary.hasUnpricedUsage),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            tokensLabel(summary.calls, summary.inputTokens, summary.outputTokens),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (summary.hasUnpricedUsage) {
            Text(
                if (summary.estimatedCostUsd == null) {
                    "価格を確認できていないモデルだけを使ったため、費用の目安は出せません"
                } else {
                    "価格を確認できていないモデルの分は、費用の目安に含めていません"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApiUsageLineRow(line: ApiUsageLine) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.model, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${line.provider.displayName} ・ ${tokensLabel(line.calls, line.inputTokens, line.outputTokens)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            line.estimatedCostUsd?.let { "約 ${formatUsd(it)}" } ?: "価格不明",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/** 費用の目安の見出し。価格の分からないモデルがあれば、含めていないことが分かるようにする。 */
internal fun costLabel(costUsd: Double?, hasUnpricedUsage: Boolean): String = when {
    costUsd == null -> "費用の目安: 出せません"
    hasUnpricedUsage -> "費用の目安: 約 ${formatUsd(costUsd)}(一部のみ)"
    else -> "費用の目安: 約 ${formatUsd(costUsd)}"
}

internal fun tokensLabel(calls: Int, inputTokens: Long, outputTokens: Long): String {
    val number = NumberFormat.getIntegerInstance(Locale.JAPAN)
    return "$calls 回 ・ 入力 ${number.format(inputTokens)} / 出力 ${number.format(outputTokens)} トークン"
}

/** 米ドルの金額。1 セント未満は「未満」で出す(0 と区別するため)。 */
internal fun formatUsd(usd: Double): String =
    if (usd < 0.01) "\$0.01 未満" else "\$" + String.format(Locale.US, "%.2f", usd)

/** 費用が目安であることと、価格の出どころの日付。 */
internal fun apiUsageNote(): String =
    "費用は目安です。使ったトークン数を、各社の公式の価格表(${ApiPricing.CHECKED_ON} に確認)の通常の価格に当てはめて計算していて、" +
        "実際の請求とは異なることがあります(米ドル)。価格を確認できていないモデルは、トークン数だけ表示します。"
