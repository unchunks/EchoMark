package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.ui.common.displayLabel
import com.unchunks.echomark.ui.common.extractDomain
import kotlinx.coroutines.launch

@Composable
fun BookmarkListScreen(
    onOpenBookmark: (Long) -> Unit = {},
    viewModel: BookmarkViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var text by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
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
                items(uiState.bookmarks, key = { it.id }) { bookmark ->
                    SwipeToDeleteItem(
                        bookmark = bookmark,
                        onClick = { onOpenBookmark(bookmark.id) },
                        onDelete = {
                            viewModel.deleteBookmark(bookmark)
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                val result = snackbarHostState.showSnackbar(
                                    message = "「${bookmark.title}」を削除しました",
                                    actionLabel = "元に戻す",
                                    duration = SnackbarDuration.Long
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.restoreBookmark(bookmark)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
    )
    }
}

/** 左スワイプで削除できる一覧の1行。タップで詳細へ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteItem(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentOnDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text("削除", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    ) {
        BookmarkListItem(bookmark = bookmark, onClick = onClick)
    }
}

@Composable
private fun BookmarkListItem(bookmark: Bookmark, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(bookmark.title, style = MaterialTheme.typography.titleMedium)
        if (!bookmark.summary.isNullOrBlank()) {
            Text(
                bookmark.summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            bookmark.contentUri?.let {
                Text(
                    extractDomain(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            bookmark.aiStatus.displayLabel()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        bookmark.category?.let { Text("カテゴリ: $it", style = MaterialTheme.typography.labelSmall) }
        if (bookmark.tags.isNotEmpty()) {
            Text(bookmark.tags.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
    }
}
