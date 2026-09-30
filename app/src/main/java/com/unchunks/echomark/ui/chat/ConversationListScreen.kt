package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.model.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会話の一覧(チャットタブのトップ)。
 * @param onOpenConversation 既存会話を開く
 * @param onNewConversation 新しい会話を始める(会話自体は初回送信時に作成される)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    onOpenConversation: (Long) -> Unit,
    onNewConversation: () -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var menuTargetId by remember { mutableStateOf<Long?>(null) }
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (conversations.isEmpty()) {
            Text(
                text = "まだチャットがありません。\n保存したブックマークについて質問してみましょう。",
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(conversations, key = { it.id }) { conversation ->
                    Box {
                        ListItem(
                            headlineContent = {
                                Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(dateFormat.format(Date(conversation.updatedAt)))
                            },
                            trailingContent = {
                                TextButton(onClick = { menuTargetId = conversation.id }) { Text("︙") }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { onOpenConversation(conversation.id) },
                                onLongClick = { menuTargetId = conversation.id }
                            )
                        )
                        DropdownMenu(
                            expanded = menuTargetId == conversation.id,
                            onDismissRequest = { menuTargetId = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text("名前を変更") },
                                onClick = { menuTargetId = null; renameTarget = conversation }
                            )
                            DropdownMenuItem(
                                text = { Text("削除") },
                                onClick = { menuTargetId = null; deleteTarget = conversation }
                            )
                        }
                    }
                    HorizontalDivider()
                }
                // FAB と最後の行が重ならないよう余白を確保
                item { Box(modifier = Modifier.padding(bottom = 88.dp)) {} }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onNewConversation,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Text("新しいチャット")
        }
    }

    renameTarget?.let { target ->
        var title by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("名前を変更") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.rename(target.id, title); renameTarget = null },
                    enabled = title.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("キャンセル") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("チャットを削除") },
            text = { Text("「${target.title}」とそのメッセージを削除します。") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(target.id); deleteTarget = null }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") } }
        )
    }
}
