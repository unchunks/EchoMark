package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole

/**
 * 1つの会話の画面。
 * @param onBack 会話一覧へ戻る
 * @param onOpenBookmark 引用チップ(ブックマーク)タップ時に、そのIDの詳細を開く
 *
 * インセット: 親の Scaffold(ボトムバー)が consumeWindowInsets 済みのため、
 * ここの imePadding は「キーボード高 - ボトムバー分」だけを足し、二重の余白にならない。
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit = {},
    onOpenBookmark: (Long) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("← チャット一覧")
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                ChatBubble(
                    message = message,
                    citationTitles = uiState.citationTitles,
                    onOpenBookmark = onOpenBookmark
                )
            }
            if (uiState.isSending) {
                item { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
            }
        }

        uiState.errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("質問を入力") },
                enabled = !uiState.isSending
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.sendMessage(input); input = "" },
                enabled = !uiState.isSending && input.isNotBlank()
            ) {
                Text("送信")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    citationTitles: Map<Long, String>,
    onOpenBookmark: (Long) -> Unit
) {
    val isUser = message.role == ChatRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp)
            )
        }

        // アシスタント回答の下に、参照したブックマークを引用チップで表示(削除済みは非表示)
        // 番号 [n] は回答中の引用番号(保存順)と一致させるため、元の並びの index を保つ
        val citations = if (isUser) emptyList()
        else message.referencedBookmarkIds.withIndex().filter { it.value in citationTitles }
        if (citations.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.widthIn(max = 280.dp).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                citations.forEach { (index, id) ->
                    AssistChip(
                        onClick = { onOpenBookmark(id) },
                        label = {
                            Text(
                                text = "[${index + 1}] ${citationTitles[id].orEmpty()}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}
