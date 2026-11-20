package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.SmartFolder
import com.unchunks.echomark.domain.bookmark.model.SmartFolderNames
import com.unchunks.echomark.domain.bookmark.model.SmartFolderResult

/**
 * スマートフォルダ(#34)を横に並べる。開いているフォルダは選択中で、右端の「⋮」から名前の変更・削除ができる。
 * 開いたフォルダの条件を変えたときは、先頭に「(フォルダ名)を更新」を出し、押すと今の条件で上書きする。
 * 条件を付けていて、同じ条件のフォルダがまだ無いときは、「この条件を保存」(新しいフォルダ)も出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartFolderRow(
    folders: List<SmartFolder>,
    activeId: Long?,
    canSave: Boolean,
    folderToUpdate: SmartFolder?,
    onSelect: (SmartFolder) -> Unit,
    onSave: () -> Unit,
    onUpdate: (SmartFolder) -> Unit,
    onManage: (SmartFolder) -> Unit,
    modifier: Modifier = Modifier
) {
    if (folders.isEmpty() && !canSave) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (folderToUpdate != null) {
            item(key = "update_current") {
                AssistChip(
                    onClick = { onUpdate(folderToUpdate) },
                    label = { Text("「${folderToUpdate.name}」を更新", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(Icons.Outlined.SaveAs, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                    modifier = Modifier.semantics { contentDescription = "今の条件でスマートフォルダ「${folderToUpdate.name}」を上書き" }
                )
            }
        }
        if (canSave) {
            item(key = "save_current") {
                AssistChip(
                    onClick = onSave,
                    label = { Text("この条件を保存") },
                    leadingIcon = {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                    modifier = Modifier.semantics { contentDescription = "今の絞り込み条件をスマートフォルダとして保存" }
                )
            }
        }
        items(folders, key = { "folder_${it.id}" }) { folder ->
            val selected = folder.id == activeId
            // 開いているフォルダと、条件を変えてもまだ「更新」できるフォルダには、操作の入り口を出す
            val manageable = selected || folder.id == folderToUpdate?.id
            FilterChip(
                selected = selected,
                onClick = { onSelect(folder) },
                label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(
                        if (selected) Icons.Filled.Check else Icons.Outlined.FolderSpecial,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                },
                trailingIcon = if (manageable) {
                    {
                        // チップ全体(フォルダを開く・閉じる)とは別に押せる。TalkBack でも別の項目として読ませる
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "「${folder.name}」の操作を開く",
                            modifier = Modifier
                                .size(FilterChipDefaults.IconSize)
                                .clickable(role = Role.Button) { onManage(folder) }
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.semantics {
                    contentDescription = "スマートフォルダ「${folder.name}」" + if (selected) "(開いています。タップで閉じる)" else ""
                }
            )
        }
    }
}

/**
 * スマートフォルダの名前を入れるダイアログ(保存・名前の変更)。
 * @param sortOrder 保存するときの今の並び順。渡すと「並び順も保存する」を出す(名前の変更では null)
 * @param error 同じ名前がある・長すぎる、などの知らせ(null なら出さない)
 */
@Composable
internal fun SmartFolderNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    sortOrder: BookmarkSortOrder?,
    error: String?,
    onConfirm: (name: String, includeSort: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var includeSort by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("フォルダの名前") },
                    supportingText = {
                        Text(error ?: "${SmartFolderNames.normalize(name)?.length ?: name.trim().length} / ${SmartFolderNames.MAX_CHARS}文字")
                    },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                if (sortOrder != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeSort = !includeSort }
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = includeSort, onCheckedChange = null)
                        Text(
                            "並び順(${sortOrder.label()})も保存する",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, includeSort) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** スマートフォルダの操作(名前を変える・削除)を選ぶシート。条件の上書きは、条件を変えたときに出る「更新」のチップから。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartFolderActionSheet(
    folder: SmartFolder,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ActionRow(Icons.Outlined.DriveFileRenameOutline, "名前を変える") {
                onDismiss()
                onRename()
            }
            ActionRow(Icons.Outlined.Delete, "削除する", color = MaterialTheme.colorScheme.error) {
                onDismiss()
                onDelete()
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = color, leadingIconColor = color),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
    )
}

/** 保存・名前の変更の結果に応じた、ダイアログに出す知らせ。保存できたとき(ダイアログを閉じるとき)は null。 */
internal fun SmartFolderResult.errorMessage(): String? = when (this) {
    is SmartFolderResult.Saved -> null
    SmartFolderResult.InvalidName -> "名前は1〜${SmartFolderNames.MAX_CHARS}文字で入力してください"
    SmartFolderResult.NameTaken -> "同じ名前のフォルダがあります"
}
