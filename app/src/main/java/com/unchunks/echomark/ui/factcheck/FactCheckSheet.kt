package com.unchunks.echomark.ui.factcheck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.factcheck.CheckedClaim
import com.unchunks.echomark.domain.factcheck.ClaimVerdict
import com.unchunks.echomark.domain.factcheck.FactCheckEvidence
import com.unchunks.echomark.domain.factcheck.FactCheckProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.unchunks.echomark.ui.design.EchoMarkButton
import com.unchunks.echomark.ui.design.EchoMarkOutlinedButton
import com.unchunks.echomark.ui.design.EchoMarkModalBottomSheet
import com.unchunks.echomark.ui.design.DesignCard
import com.unchunks.echomark.ui.design.SurfaceRole

/** 「Web で確かめる」のシート。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactCheckSheet(
    state: FactCheckUiState,
    onCheck: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state.target == null) return
    EchoMarkModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        FactCheckSheetContent(state, onCheck, onOpenUrl, onOpenAiSettings)
    }
}

/**
 * シートの中身(スクリーンショットテストで描く)。
 * 判定ではなく手がかりであること、要約・回答は書き換えないことを、結果より先に書く。
 */
@Composable
fun FactCheckSheetContent(
    state: FactCheckUiState,
    onCheck: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val paragraph = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TravelExplore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Web で確かめる", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Row(modifier = Modifier.padding(12.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(FACT_CHECK_HINT, style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph))
            }
        }

        when {
            !state.availability.keyConfigured -> {
                Text("Web で確かめるには、AI 設定で Brave Search API のキーを入れてください。", style = paragraph)
                EchoMarkOutlinedButton(onClick = onOpenAiSettings) { Text("AI 設定を開く") }
            }
            state.availability.offline -> Text("オフラインモードの間は確かめられません。", style = paragraph)
        }

        state.result?.let { result ->
            Text(
                "${checkedAtText(result.checkedAt, zone)} に確かめました" + if (state.isStale) "(その後、内容が変わりました)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            result.claims.forEach { ClaimCard(it, onOpenUrl) }
        }

        state.progress?.let { progress ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(progressText(progress), style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { error ->
            Text(error, style = paragraph, color = MaterialTheme.colorScheme.error)
        }

        if (state.availability.usable) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EchoMarkButton(onClick = onCheck, enabled = !state.isRunning) {
                    Text(if (state.result == null) "確かめる" else "もう一度確かめる")
                }
                Text(
                    "主張ごとに1回、その語句を Brave Search に送って検索し、見つかったページを読みに行きます。",
                    style = MaterialTheme.typography.labelSmall.copy(lineBreak = LineBreak.Paragraph),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 判定ではないことの説明 */
internal const val FACT_CHECK_HINT =
    "判定ではなく、確かめるための手がかりです。Web で見つかったページと照らし合わせた結果で、ページの内容も正しいとは限りません。" +
        "要約・回答は書き換えません。"

@Composable
private fun ClaimCard(claim: CheckedClaim, onOpenUrl: (String) -> Unit) {
    val style = verdictStyle(claim.verdict)
    DesignCard(
        modifier = Modifier.fillMaxWidth(),
        classic = MaterialTheme.colorScheme.surfaceContainerLow,
        classicBorder = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(claim.verdict.label, style = MaterialTheme.typography.labelLarge, color = style.color)
            }
            Text(claim.claim, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph))
            val quoted = claim.evidence.filter { it.quote.isNotBlank() }
            quoted.forEach { EvidenceQuote(it, onOpenUrl) }
            val looked = claim.evidence.filter { it.quote.isBlank() }
            if (looked.isNotEmpty()) {
                Text("調べたページ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                looked.forEach { PageLink(it, onOpenUrl) }
            } else if (quoted.isEmpty()) {
                Text(
                    "関係するページを読めませんでした",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EvidenceQuote(evidence: FactCheckEvidence, onOpenUrl: (String) -> Unit) {
    // 主張のカードの中の、根拠の引用の区画
    DesignCard(
        role = SurfaceRole.Nested,
        classic = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("「${evidence.quote}」", style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph))
            PageLink(evidence, onOpenUrl)
        }
    }
}

@Composable
private fun PageLink(evidence: FactCheckEvidence, onOpenUrl: (String) -> Unit) {
    Text(
        text = "${evidence.domain}・${evidence.title}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clickable { onOpenUrl(evidence.url) }
            .padding(vertical = 2.dp)
    )
}

private class VerdictStyle(val icon: ImageVector, val color: Color)

@Composable
private fun verdictStyle(verdict: ClaimVerdict): VerdictStyle = when (verdict) {
    ClaimVerdict.SUPPORTED -> VerdictStyle(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary)
    ClaimVerdict.CONFLICTING -> VerdictStyle(Icons.Outlined.ReportProblem, MaterialTheme.colorScheme.error)
    ClaimVerdict.NOT_FOUND -> VerdictStyle(Icons.AutoMirrored.Outlined.HelpOutline, MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 確かめている途中の説明 */
internal fun progressText(progress: FactCheckProgress): String = when (progress) {
    FactCheckProgress.ExtractingClaims -> "確かめる文を取り出しています…"
    is FactCheckProgress.Searching -> "${progress.index}/${progress.total} 件目を Web で探しています…"
}

private val CHECKED_AT = DateTimeFormatter.ofPattern("M月d日 H:mm")

/** 確かめた日時(例: 11月15日 9:30) */
internal fun checkedAtText(millis: Long, zone: ZoneId): String = CHECKED_AT.format(Instant.ofEpochMilli(millis).atZone(zone))
