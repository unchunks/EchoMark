package com.unchunks.echomark.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.EvidenceReport
import com.unchunks.echomark.domain.model.SummaryLanguage

/*
 * 要約の根拠の表示(#18)と、要約の翻訳のシート(#24)。
 */

/**
 * 要約の各文と、根拠になる本文の一節。本文に見つからない文は、その旨を出す(言い換えが大きいときも見つからないため、
 * 間違いの断定ではなく、元の本文で確かめる目印として出す)。突き合わせられないとき(言語が違うなど)は、その理由を出す。
 */
@Composable
fun EvidenceBlock(report: EvidenceReport, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("要約の根拠(本文から探した一節)", style = MaterialTheme.typography.labelLarge)
            if (!report.comparable) {
                Text(
                    "要約と本文の言語が違うか、本文が短いため、根拠を探せません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                report.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.sentence, style = MaterialTheme.typography.bodyMedium)
                        if (item.quote != null) {
                            Text(
                                "本文: 「${item.quote}」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "本文に根拠が見つかりません(言い換えが大きいだけかもしれません。元の本文で確かめてください)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (report.unsupportedCount > 0) {
                    Text(
                        "${report.items.size} 文のうち ${report.unsupportedCount} 文は、本文に根拠が見つかりませんでした",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 要約を翻訳するシート。言語を選ぶと翻訳し、結果をコピーできる。翻訳は保存しない。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryTranslateSheet(
    state: TranslationState,
    onTranslate: (SummaryLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        SummaryTranslateSheetContent(state = state, onTranslate = onTranslate, onCopy = { copyToClipboard(context, it) })
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("要約の翻訳", text))
}

/** 翻訳のシートの中身(状態を受け取って描くだけ) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryTranslateSheetContent(
    state: TranslationState,
    onTranslate: (SummaryLanguage) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = when (state) {
        TranslationState.Idle -> null
        is TranslationState.Loading -> state.target
        is TranslationState.Done -> state.target
        is TranslationState.Failed -> state.target
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("要約を翻訳", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(
            "翻訳する言語を選んでください。「要約」の設定の AI で翻訳します(クラウド AI のときは、要約の文が送信されます)。翻訳は保存しません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryLanguage.entries.forEach { language ->
                FilterChip(
                    selected = selected == language,
                    onClick = { onTranslate(language) },
                    label = { Text(language.label) },
                    enabled = state !is TranslationState.Loading
                )
            }
        }
        when (state) {
            TranslationState.Idle -> Unit
            is TranslationState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("${state.target.label} に翻訳しています…", style = MaterialTheme.typography.bodyMedium)
            }
            is TranslationState.Done -> {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(state.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
                    }
                }
                OutlinedButton(onClick = { onCopy(state.text) }) { Text("コピー") }
            }
            is TranslationState.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
