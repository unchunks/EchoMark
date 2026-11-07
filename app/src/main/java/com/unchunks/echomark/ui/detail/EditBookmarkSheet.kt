package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType

/** タイトルと本文(メモ)を編集するシート。保存すると [onSave] に新しい値を渡す。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookmarkSheet(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> Unit
) {
    var title by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.title) }
    var content by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.content.orEmpty()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        EditBookmarkSheetContent(
            isLink = bookmark.type == BookmarkType.URL,
            title = title,
            content = content,
            onTitleChange = { title = it },
            onContentChange = { content = it },
            onCancel = onDismiss,
            onSave = { onSave(title, content) }
        )
    }
}

/** 編集シートの中身(状態を受け取って描くだけ) */
@Composable
fun EditBookmarkSheetContent(
    isLink: Boolean,
    title: String,
    content: String,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("編集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("タイトル") },
            isError = title.isBlank(),
            supportingText = if (title.isBlank()) {
                { Text("タイトルを入力してください") }
            } else {
                null
            }
        )
        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            label = { Text(if (isLink) "本文・メモ" else "メモ") },
            minLines = 4,
            supportingText = { Text("変更したら、AI で要約し直すこともできます") }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("キャンセル") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onSave, enabled = title.isNotBlank()) { Text("保存") }
        }
    }
}
