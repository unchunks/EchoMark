package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType

@Composable
fun BookmarkListScreen(
    viewModel: BookmarkViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val related by viewModel.relatedBookmarks.collectAsState()
    var text by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var relatedDialogBookmark by remember { mutableStateOf<Bookmark?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 保存フォーム
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                label = { Text("タイトル") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (text.isNotBlank()) {
                    viewModel.saveTextBookmark(text, content)
                    text = ""
                    content = ""
                }
            }) {
                Text("保存")
            }
        }

        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("本文(任意)") }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 検索バー
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { viewModel.onSearchQueryChange(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("検索") },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // タグフィルタ(横スクロールのチップ)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = uiState.selectedTagId == null,
                    onClick = { viewModel.onTagSelected(null) },
                    label = { Text("すべて") }
                )
            }
            items(uiState.allTags) { tag ->
                FilterChip(
                    selected = uiState.selectedTagId == tag.id,
                    onClick = { viewModel.onTagSelected(tag.id) },
                    label = { Text(tag.name) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ブックマークリスト
        if (uiState.isLoading) {
            CircularProgressIndicator()
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(uiState.bookmarks) { bookmark ->
                    val isUrl = bookmark.type == BookmarkType.URL && bookmark.contentUri != null
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (isUrl) Modifier.clickable { openUrl(context, bookmark.contentUri!!) }
                                        else Modifier
                                    )
                            ) {
                                Text(bookmark.title)
                                if (isUrl) {
                                    // タップでブラウザを開けるURLのドメインを表示
                                    Text(
                                        extractDomain(bookmark.contentUri!!),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            TextButton(onClick = {
                                relatedDialogBookmark = bookmark
                                viewModel.findRelated(bookmark.id)
                            }) { Text("類似") }
                        }
                        bookmark.category?.let { Text("カテゴリ: $it", style = MaterialTheme.typography.labelSmall) }
                        if (bookmark.tags.isNotEmpty()) {
                            Text(bookmark.tags.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    relatedDialogBookmark?.let { origin ->
        AlertDialog(
            onDismissRequest = { relatedDialogBookmark = null; viewModel.clearRelated() },
            title = { Text("「${origin.title}」に似たブックマーク") },
            text = {
                if (related.isEmpty()) {
                    Text("見つかりませんでした")
                } else {
                    Column { related.forEach { Text("・${it.title}") } }
                }
            },
            confirmButton = {
                TextButton(onClick = { relatedDialogBookmark = null; viewModel.clearRelated() }) { Text("閉じる") }
            }
        )
    }
}

/** URLからドメイン(www.除去)を取り出す。取れなければURLをそのまま返す */
private fun extractDomain(url: String): String =
    Uri.parse(url).host?.removePrefix("www.") ?: url

/** 既定のブラウザでURLを開く */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "URLを開けるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}
