package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.domain.bookmark.model.NoteText
import com.unchunks.components.composeui.formatRelativeTime

/** 引用を何行まで見せるか(長い引用は省略して、ノートの文章を読みやすくする) */
private const val QUOTE_MAX_LINES = 4

/**
 * 自分のノート・注釈の欄(#95)。AI の要約・タグ・本文とは別の欄で、AI の再処理では変わらない。
 * 引用の付いたノートは、引用を左に線を付けて見せる。
 */
@Composable
fun NotesSection(
    notes: List<BookmarkNote>,
    nowMillis: Long,
    onAdd: () -> Unit,
    onEdit: (BookmarkNote) -> Unit,
    onDelete: (BookmarkNote) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailSectionTitle(if (notes.isEmpty()) "ノート" else "ノート(${notes.size})")
        if (notes.isEmpty()) {
            Text(
                text = "読んだ感想や、あとで調べたいことを書き留められます。AI の要約や本文は変わりません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        notes.forEach { note ->
            NoteCard(note = note, nowMillis = nowMillis, onEdit = { onEdit(note) }, onDelete = { onDelete(note) })
        }
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("ノートを追加")
        }
    }
}

@Composable
private fun NoteCard(note: BookmarkNote, nowMillis: Long, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            note.quote?.let { quote ->
                Row(Modifier.height(IntrinsicSize.Min).padding(end = 12.dp)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = quote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = QUOTE_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = note.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatRelativeTime(note.updatedAt, nowMillis) + if (note.isEdited) "に編集" else "に書きました",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "ノートのメニュー")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("編集") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * ノートを書く・直すシート。[noteId] が null なら新しいノート。[initialQuote] があれば引用つき(外すこともできる)。
 * 保存すると [onSave] に文章と引用(無ければ null)を渡す。空のノートは保存できない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorSheet(
    noteId: Long?,
    initialText: String,
    initialQuote: String?,
    onDismiss: () -> Unit,
    onSave: (text: String, quote: String?) -> Unit
) {
    var text by rememberSaveable(noteId) { mutableStateOf(initialText) }
    var quote by rememberSaveable(noteId) { mutableStateOf(initialQuote) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        NoteEditorSheetContent(
            isNew = noteId == null,
            text = text,
            quote = quote,
            onTextChange = { text = it },
            onQuoteChange = { quote = it },
            onCancel = onDismiss,
            onSave = { onSave(text, quote) }
        )
    }
}

/** ノートの編集シートの中身(状態を受け取って描くだけ)。[quote] が null なら引用の欄は出さない。 */
@Composable
fun NoteEditorSheetContent(
    isNew: Boolean,
    text: String,
    quote: String?,
    onTextChange: (String) -> Unit,
    onQuoteChange: (String?) -> Unit,
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
        Text(
            text = if (isNew) "ノートを追加" else "ノートを編集",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )
        if (quote != null) {
            OutlinedTextField(
                value = quote,
                onValueChange = { onQuoteChange(it.take(NoteText.MAX_QUOTE)) },
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                label = { Text("引用") },
                maxLines = 6,
                supportingText = { Text("本文などから引いた文章です") }
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { onTextChange(it.take(NoteText.MAX_TEXT)) },
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            label = { Text("ノート") },
            minLines = 4,
            supportingText = { Text("AI の要約や本文は変わりません") }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (quote == null) {
                TextButton(onClick = { onQuoteChange("") }) { Text("引用を付ける") }
            } else {
                TextButton(onClick = { onQuoteChange(null) }) { Text("引用を外す") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("キャンセル") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onSave, enabled = text.isNotBlank()) { Text("保存") }
            }
        }
    }
}
