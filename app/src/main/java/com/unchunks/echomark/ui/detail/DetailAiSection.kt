package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.SummaryVersion
import com.unchunks.echomark.domain.bookmark.model.processingDurationMillis
import com.unchunks.echomark.domain.bookmark.model.processingProgress
import com.unchunks.echomark.ui.components.AiStatusBadge
import com.unchunks.echomark.ui.components.description
import com.unchunks.echomark.ui.components.formatElapsed
import com.unchunks.echomark.ui.components.message
import com.unchunks.echomark.ui.components.needsSetup
import com.unchunks.echomark.ui.components.processingBadge
import com.unchunks.echomark.ui.components.rememberHighlighted
import com.unchunks.echomark.ui.components.retryingMessage
import com.unchunks.echomark.ui.components.rememberTickingNow
import com.unchunks.echomark.ui.components.summaryText
import com.unchunks.echomark.ui.design.EchoMarkButton
import com.unchunks.echomark.ui.design.EchoMarkOutlinedButton
import com.unchunks.echomark.ui.design.AccentCard
import com.unchunks.echomark.ui.design.DesignCard

/**
 * 処理待ち・処理中の進み具合。段が進み具合を報告していれば(ダウンロード・文字起こし)その割合のバーを、
 * していなければ動き続けるバーを出し、何段目か・経過時間・残り時間の目安を添える。
 */
@Composable
private fun ProcessingProgressSection(
    bookmark: Bookmark,
    stageProgress: Float?,
    stageAverages: Map<StageDurationKey, Long>
) {
    // 経過時間を出すときだけ、1 秒ごとに更新する
    val now by rememberTickingNow(enabled = bookmark.processing.startedAt != null)
    val progress = bookmark.processingProgress(now, stageProgress, stageAverages) ?: return
    val fraction = progress.stageFraction
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    Text(
        progress.summaryText(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 失敗の理由の一文(情報のアイコン付き) */
@Composable
private fun FailureReasonLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 失敗・準備待ちのときの次の行動。ワンタップで再試行するか、AI 設定を開く。
 * [setupFirst] なら「AI設定を開く」を先に(塗りのボタンで)出す。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetryActions(setupFirst: Boolean, callbacks: BookmarkDetailCallbacks) {
    // 文字が大きいときは折り返して次の行に並べる(ボタンの文字を切らない)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (setupFirst) {
            EchoMarkButton(onClick = callbacks.onOpenAiSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("AI設定を開く")
            }
            EchoMarkOutlinedButton(onClick = callbacks.onReprocess) { Text("再試行") }
        } else {
            EchoMarkButton(onClick = callbacks.onReprocess) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("再試行")
            }
            EchoMarkOutlinedButton(onClick = callbacks.onOpenAiSettings) { Text("AI設定を開く") }
        }
    }
}

/** AI 要約(完了時)か、AI の状態と次の行動(処理中・失敗・準備待ち) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiSection(
    bookmark: Bookmark,
    stageProgress: Float?,
    stageAverages: Map<StageDurationKey, Long>,
    summaryHistory: List<SummaryVersion>,
    translation: TranslationState,
    categoryChoices: List<CategoryEntry>,
    callbacks: BookmarkDetailCallbacks,
    highlightTerms: List<String> = emptyList()
) {
    val summary = bookmark.summary
    if (bookmark.aiStatus == AiStatus.DONE && !summary.isNullOrBlank()) {
        var showSummaryEdit by rememberSaveable { mutableStateOf(false) }
        var showSummaryHistory by rememberSaveable { mutableStateOf(false) }
        var showCategory by rememberSaveable { mutableStateOf(false) }
        var showEvidence by rememberSaveable { mutableStateOf(false) }
        var showTranslate by rememberSaveable { mutableStateOf(false) }
        AccentCard(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (bookmark.summaryEdited) "要約(手で編集)" else "AI要約",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() }
                    )
                    bookmark.category?.takeIf { it.isNotBlank() }?.let { category ->
                        Surface(
                            onClick = { showCategory = true },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { contentDescription = "カテゴリ: ${category}。タップで説明と変更" }
                        ) {
                            Text(
                                category,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(rememberHighlighted(summary, highlightTerms), style = MaterialTheme.typography.bodyLarge)
                if (bookmark.summaryEdited) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "手で直した要約です。「AIで処理し直す」では置き換わりません",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { showSummaryEdit = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("要約を直す")
                    }
                    // 作り直し・手直しで版が2つ以上になったら、見比べたり戻したりできる
                    if (summaryHistory.size >= 2) {
                        TextButton(onClick = { showSummaryHistory = true }) {
                            Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("履歴(${summaryHistory.size})")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 本文のあるものは、要約の各文の根拠になる一節を見られる
                    if (!bookmark.content.isNullOrBlank()) {
                        TextButton(onClick = { showEvidence = !showEvidence }) {
                            Icon(Icons.Outlined.FactCheck, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(if (showEvidence) "根拠を閉じる" else "根拠を見る")
                        }
                    }
                    TextButton(onClick = { showTranslate = true }) {
                        Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("翻訳")
                    }
                }
                // 要約の事実の文を Web のページと照らし合わせる(手がかりを出すだけで、要約は書き換えない)
                TextButton(onClick = { callbacks.onFactCheck(summary) }) {
                    Icon(Icons.Outlined.TravelExplore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Web で確かめる")
                }
                if (showEvidence) {
                    val body = listOfNotNull(bookmark.title, bookmark.content).joinToString("\n")
                    Spacer(Modifier.height(8.dp))
                    EvidenceSection(summary, body)
                }
                bookmark.processingDurationMillis()?.let { duration ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "処理時間 ${formatElapsed(duration)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (showCategory) {
            bookmark.category?.takeIf { it.isNotBlank() }?.let { category ->
                CategorySheet(
                    current = category,
                    isEdited = bookmark.categoryEdited,
                    choices = categoryChoices,
                    onSelect = {
                        showCategory = false
                        callbacks.onSelectCategory(it)
                    },
                    onReset = {
                        showCategory = false
                        callbacks.onResetCategory()
                    },
                    onDismiss = { showCategory = false }
                )
            }
        }
        if (showSummaryEdit) {
            SummaryEditSheet(
                initial = summary,
                onDismiss = { showSummaryEdit = false },
                onSave = {
                    showSummaryEdit = false
                    callbacks.onEditSummary(it)
                }
            )
        }
        if (showTranslate) {
            SummaryTranslateSheet(
                state = translation,
                onTranslate = callbacks.onTranslateSummary,
                onDismiss = {
                    showTranslate = false
                    callbacks.onClearTranslation()
                }
            )
        }
        if (showSummaryHistory) {
            SummaryHistorySheet(
                current = summary,
                versions = summaryHistory,
                onDismiss = { showSummaryHistory = false },
                onRestore = callbacks.onRestoreSummary
            )
        }
        return
    }
    if (bookmark.aiStatus == AiStatus.DONE) return

    val isFailed = bookmark.aiStatus == AiStatus.FAILED
    DesignCard(
        classic = MaterialTheme.colorScheme.surfaceContainerHigh,
        // 失敗は、どのデザインでも警告の色で示す
        accentColor = if (isFailed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            val badge = bookmark.processingBadge()
            AiStatusBadge(badge)
            // 失敗・準備待ちで理由が分かっているときは、一般的な説明の代わりに理由を出す
            val hasSpecificReason = bookmark.processing.failureReason != null &&
                (bookmark.aiStatus == AiStatus.FAILED || bookmark.aiStatus == AiStatus.WAITING_MODEL)
            badge.description()?.takeUnless { hasSpecificReason }?.let { description ->
                Spacer(Modifier.height(10.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            val reason = bookmark.processing.failureReason
            when (bookmark.aiStatus) {
                AiStatus.PENDING, AiStatus.PROCESSING -> {
                    // 再試行を待っているときは、直前の失敗の理由と自動でやり直すことを伝える
                    if (bookmark.aiStatus == AiStatus.PENDING && reason != null) {
                        Spacer(Modifier.height(8.dp))
                        FailureReasonLine(reason.retryingMessage())
                    }
                    Spacer(Modifier.height(12.dp))
                    ProcessingProgressSection(bookmark, stageProgress, stageAverages)
                }
                AiStatus.FAILED, AiStatus.WAITING_MODEL -> {
                    reason?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it.message(), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    // 設定を変えないと直らない理由なら「AI設定を開く」、一時的な失敗なら「再試行」を先に(目立つ形で)出す。
                    // 理由が分からない(以前の版で失敗した)ときは、準備待ちなら設定、失敗なら再試行を先にする
                    RetryActions(
                        setupFirst = reason?.needsSetup ?: (bookmark.aiStatus == AiStatus.WAITING_MODEL),
                        callbacks = callbacks
                    )
                }
                AiStatus.DONE -> Unit
            }
        }
    }
}
