package com.unchunks.echomark.ui.tags

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.domain.model.TagWithCount

/** 名前変更ダイアログ。既存の別タグと同じ名前にすると、統合されることを先に伝える */
@Composable
internal fun RenameTagDialog(
    tag: TagWithCount,
    allTags: List<TagWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable(tag.id) { mutableStateOf(tag.name) }
    val cleaned = TagNames.clean(name)
    val others = allTags.filter { it.id != tag.id }
    val mergeTarget = TagNames.findSame(cleaned, others.map { it.name })?.let { same -> others.first { it.name == same } }
    val canConfirm = cleaned.isNotEmpty() && cleaned != tag.name

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグ名を変更") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("タグ名") },
                supportingText = {
                    Text(
                        when {
                            mergeTarget != null ->
                                "「${mergeTarget.name}」(${mergeTarget.bookmarkCount}件)と統合されます"
                            cleaned.isEmpty() -> "タグ名を入力してください"
                            else -> "既存のタグと同じ名前(大文字小文字・全角半角の違いは無視)にすると、1つに統合されます"
                        }
                    )
                },
                isError = cleaned.isEmpty()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(cleaned) }, enabled = canConfirm) {
                Text(if (mergeTarget != null) "統合" else "変更")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 統合先を選ぶダイアログ。タグが多いときは名前で絞り込める */
@Composable
internal fun MergeIntoDialog(
    tag: TagWithCount,
    candidates: List<TagWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (TagWithCount) -> Unit
) {
    var query by rememberSaveable(tag.id) { mutableStateOf("") }
    var selectedId by rememberSaveable(tag.id) { mutableStateOf<Long?>(null) }
    val queryKey = TagNames.key(query)
    val shown = candidates.filter { queryKey.isEmpty() || TagNames.key(it.name).contains(queryKey) }
    val selected = candidates.firstOrNull { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${tag.name}」の統合先") },
        text = {
            Column {
                Text(
                    "このタグが付いたブックマークは、選んだタグに付け替わります。「${tag.name}」は削除されます。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("タグを絞り込み") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                if (shown.isEmpty()) {
                    Text(
                        "該当するタグがありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                } else {
                    LazyColumn(Modifier.selectableGroup()) {
                        items(shown, key = { it.id }) { candidate ->
                            TagChoiceRow(
                                label = "${candidate.name}(${candidate.bookmarkCount}件)",
                                selected = candidate.id == selectedId,
                                onSelect = { selectedId = candidate.id }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) { Text("統合") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 親のタグを選ぶダイアログ(「なし」で最上位に戻す)。自分と自分の子孫は [candidates] に含めない */
@Composable
internal fun ParentPickerDialog(
    tag: TagWithCount,
    candidates: List<TagWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (TagWithCount?) -> Unit
) {
    var query by rememberSaveable(tag.id) { mutableStateOf("") }
    // 選び直すまでは今の親(最上位なら「なし」)
    var selectedId by rememberSaveable(tag.id) { mutableStateOf(tag.parentId) }
    val queryKey = TagNames.key(query)
    val shown = candidates.filter { queryKey.isEmpty() || TagNames.key(it.name).contains(queryKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${tag.name}」の親のタグ") },
        text = {
            Column {
                Text(
                    "親のタグで絞り込むと、このタグのブックマークも含まれます。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("タグを絞り込み") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                LazyColumn(Modifier.selectableGroup()) {
                    item(key = "none") {
                        TagChoiceRow(label = "なし(最上位)", selected = selectedId == null, onSelect = { selectedId = null })
                    }
                    items(shown, key = { it.id }) { candidate ->
                        TagChoiceRow(
                            label = candidate.name,
                            selected = candidate.id == selectedId,
                            onSelect = { selectedId = candidate.id }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(candidates.firstOrNull { it.id == selectedId }) },
                enabled = selectedId != tag.parentId
            ) { Text("設定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 表記ゆれの候補をまとめるダイアログ。残す名前を選ぶ(最初は先頭の候補) */
@Composable
internal fun UnifyVariantsDialog(
    group: List<TagWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (TagWithCount) -> Unit
) {
    var selectedId by rememberSaveable(group.first().id) { mutableStateOf(group.first().id) }
    val selected = group.firstOrNull { it.id == selectedId } ?: group.first()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("1つにまとめる") },
        text = {
            Column {
                Text(
                    "残す名前を選んでください。ほかのタグが付いたブックマークには、選んだタグが付きます。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Column(
                    Modifier
                        .selectableGroup()
                        .padding(top = 8.dp)
                ) {
                    group.forEach { tag ->
                        TagChoiceRow(
                            label = "${tag.name}(${tag.bookmarkCount}件)",
                            selected = tag.id == selected.id,
                            onSelect = { selectedId = tag.id }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("まとめる") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun TagChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
