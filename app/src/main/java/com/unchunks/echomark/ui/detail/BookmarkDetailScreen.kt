package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.ui.components.badgeLabel
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.common.openUrl

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkDetailScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: BookmarkDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var contentExpanded by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        TextButton(onClick = onBack) { Text("← 戻る") }

        val bookmark: Bookmark? = uiState.bookmark
        when {
            uiState.isLoading -> CircularProgressIndicator()
            bookmark == null -> Text("ブックマークが見つかりません")
            else -> Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(bookmark.title, style = MaterialTheme.typography.headlineSmall)

                // ドメイン(タップでブラウザを開く)
                bookmark.contentUri?.let { uri ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        extractDomain(uri),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { openUrl(context, uri) }
                    )
                    Text(
                        uri,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // AI ステータス(完了時は非表示)
                bookmark.aiStatus.takeIf { it != AiStatus.DONE }?.badgeLabel()?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                bookmark.category?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("カテゴリ: $it", style = MaterialTheme.typography.labelLarge)
                }

                if (!bookmark.summary.isNullOrBlank()) {
                    Spacer(Modifier.height(16.dp))
                    SectionTitle("要約")
                    Text(bookmark.summary, style = MaterialTheme.typography.bodyMedium)
                }

                if (!bookmark.content.isNullOrBlank()) {
                    Spacer(Modifier.height(16.dp))
                    SectionTitle("本文")
                    Text(
                        bookmark.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (contentExpanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { contentExpanded = !contentExpanded }) {
                        Text(if (contentExpanded) "折りたたむ" else "もっと見る")
                    }
                }

                // タグ(タップで削除、下の入力欄で追加)
                Spacer(Modifier.height(16.dp))
                SectionTitle("タグ")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bookmark.tags.forEach { tag ->
                        AssistChip(
                            onClick = { viewModel.removeTag(tag) },
                            label = { Text("$tag ×") }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("タグを追加") },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = tagInput.isNotBlank(),
                        onClick = {
                            viewModel.addTag(tagInput)
                            tagInput = ""
                        }
                    ) { Text("追加") }
                }

                // 関連ブックマーク
                if (uiState.related.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SectionTitle("関連ブックマーク")
                    uiState.related.forEach { related ->
                        Text(
                            related.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBookmark(related.id) }
                                .padding(vertical = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.reprocess() }) { Text("AI再処理") }
                    OutlinedButton(onClick = { showDeleteDialog = true }) { Text("削除") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("削除しますか？") },
            text = { Text("このブックマークを削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
}
