package com.unchunks.echomark.ui.settings.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.processingProgress
import com.unchunks.echomark.ui.components.AiStatusBadge
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.SectionHeader
import com.unchunks.echomark.ui.components.message
import com.unchunks.echomark.ui.components.processingBadge
import com.unchunks.echomark.ui.components.rememberTickingNow
import com.unchunks.echomark.ui.components.summaryText

/** 処理の状況(#65)。状態は [ProcessingQueueViewModel]、表示は [ProcessingQueueContent]。 */
@Composable
fun ProcessingQueueScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: ProcessingQueueViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    ProcessingQueueContent(
        uiState = uiState,
        actions = ProcessingQueueActions(
            onBack = onBack,
            onOpenBookmark = onOpenBookmark,
            onCancel = viewModel::cancel,
            onProcessNow = viewModel::processNow,
            onRetry = viewModel::retry
        )
    )
}

/** 処理の状況の画面の操作。既定は何もしない(スクリーンショット用)。 */
class ProcessingQueueActions(
    val onBack: () -> Unit = {},
    val onOpenBookmark: (Long) -> Unit = {},
    val onCancel: (Long) -> Unit = {},
    val onProcessNow: (Long) -> Unit = {},
    val onRetry: (Long) -> Unit = {}
)

/**
 * 処理の状況の本体。上から 処理中(段と進み具合) → 処理待ち(順番。今すぐ処理・やめる) → 最近の失敗(理由・再試行)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingQueueContent(
    uiState: ProcessingQueueUiState,
    actions: ProcessingQueueActions,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val queue = uiState.queue
    // 処理中のものがあるときだけ、経過時間・残り時間を 1 秒ごとに更新する
    val now by rememberTickingNow(enabled = queue.running.isNotEmpty())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("処理の状況", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                queue.running.isEmpty() && queue.waiting.isEmpty() && queue.recentFailures.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.TaskAlt,
                    title = "処理中・処理待ちのものはありません",
                    description = "保存したものは、本文の取得・要約・タグ付けが終わると一覧に要約が出ます。"
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (queue.running.isNotEmpty()) {
                        item { SectionHeader("処理中(${queue.running.size} 件)") }
                        items(queue.running, key = { "running_${it.id}" }) { bookmark ->
                            QueueRow(
                                bookmark = bookmark,
                                supporting = bookmark.processingProgress(now, null, uiState.stageAverages)?.summaryText(),
                                showProgress = true,
                                onClick = { actions.onOpenBookmark(bookmark.id) },
                                trailing = {
                                    IconButton(onClick = { actions.onCancel(bookmark.id) }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "「${bookmark.title}」の処理をやめる")
                                    }
                                }
                            )
                        }
                    }
                    if (queue.waiting.isNotEmpty()) {
                        item { SectionHeader("処理待ち(${queue.waiting.size} 件)") }
                        if (uiState.settings.nightlyBatch) {
                            item {
                                Text(
                                    "夜間にまとめて処理する設定のため、${uiState.settings.nightlyHour}:00 から充電中に処理します。" +
                                        "急ぐものは「今すぐ処理」でいつでも処理できます。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                        items(queue.waiting, key = { "waiting_${it.id}" }) { bookmark ->
                            QueueRow(
                                bookmark = bookmark,
                                supporting = "${queue.waiting.indexOf(bookmark) + 1} 番目",
                                onClick = { actions.onOpenBookmark(bookmark.id) },
                                trailing = {
                                    Row {
                                        IconButton(onClick = { actions.onProcessNow(bookmark.id) }) {
                                            Icon(Icons.Outlined.FastForward, contentDescription = "「${bookmark.title}」を今すぐ処理")
                                        }
                                        IconButton(onClick = { actions.onCancel(bookmark.id) }) {
                                            Icon(Icons.Outlined.Close, contentDescription = "「${bookmark.title}」の処理をやめる")
                                        }
                                    }
                                }
                            )
                        }
                    }
                    if (queue.recentFailures.isNotEmpty()) {
                        item { SectionHeader("最近の失敗") }
                        items(queue.recentFailures, key = { "failed_${it.id}" }) { bookmark ->
                            QueueRow(
                                bookmark = bookmark,
                                supporting = bookmark.processing.failureReason?.message(),
                                onClick = { actions.onOpenBookmark(bookmark.id) },
                                trailing = {
                                    IconButton(onClick = { actions.onRetry(bookmark.id) }) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = "「${bookmark.title}」を再試行")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 1 件の行。タイトルと状態のバッジ、補足(何番目・進み具合・失敗の理由)、操作のボタン。 */
@Composable
private fun QueueRow(
    bookmark: Bookmark,
    supporting: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    showProgress: Boolean = false
) {
    ListItem(
        headlineContent = { Text(bookmark.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(Modifier.height(2.dp))
                AiStatusBadge(bookmark.processingBadge())
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (showProgress) LinearProgressIndicator()
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
