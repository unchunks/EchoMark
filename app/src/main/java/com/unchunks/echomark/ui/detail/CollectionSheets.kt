package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.CollectionNames
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.export.ExportFormat

/** ブックマークをコレクションに入れる・外すシート(#91)。チェックの付け外しがそのまま反映される。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionSheet(
    collections: List<BookmarkCollection>,
    memberOf: Set<Long>,
    onSetInCollection: (BookmarkCollection, Boolean) -> Unit,
    onCreate: (name: String, onResult: (CollectionResult) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        AddToCollectionSheetContent(
            collections = collections,
            memberOf = memberOf,
            onSetInCollection = onSetInCollection,
            onCreate = onCreate,
            onClose = onDismiss
        )
    }
}

/** コレクションに追加するシートの中身(状態を受け取って描くだけ)。 */
@Composable
fun AddToCollectionSheetContent(
    collections: List<BookmarkCollection>,
    memberOf: Set<Long>,
    onSetInCollection: (BookmarkCollection, Boolean) -> Unit,
    onCreate: (name: String, onResult: (CollectionResult) -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("コレクションに追加", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (collections.isEmpty()) {
            Text(
                text = "コレクションはまだありません。下で名前を付けて作ると、このブックマークが入ります。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(
                modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())
            ) {
                collections.forEach { collection ->
                    val checked = collection.id in memberOf
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onSetInCollection(collection, it) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "${collection.bookmarkCount}件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = newName,
            onValueChange = {
                newName = it
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("新しいコレクションの名前") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                enabled = newName.isNotBlank(),
                onClick = {
                    onCreate(newName) { result ->
                        when (result) {
                            is CollectionResult.Saved -> newName = ""
                            CollectionResult.InvalidName -> error = "名前は${CollectionNames.MAX_CHARS}文字までで入力してください"
                            CollectionResult.NameTaken -> error = "同じ名前のコレクションがあります"
                            CollectionResult.NotFound -> error = "保存できませんでした"
                        }
                    }
                }
            ) { Text("作って追加") }
            TextButton(onClick = onClose) { Text("閉じる") }
        }
    }
}

/** 書き出す形式を選ぶダイアログ(#91)。形式を選ぶと [onSelect] に渡し、保存先を選ぶ画面へ進む。 */
@Composable
fun ExportFormatDialog(
    onSelect: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ファイルに書き出す") },
        text = {
            Column {
                ExportFormat.entries.forEach { format ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(format) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(format.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = format.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
