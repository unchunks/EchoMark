package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.domain.model.CategoryNames

/**
 * ブックマークのカテゴリのシート: 今のカテゴリの説明・だれが決めたか・カテゴリとは何か、別のカテゴリへの変更、
 * AI に決め直してもらう操作。
 * @param current 今のカテゴリ名
 * @param isEdited 自分で選んだカテゴリか(false なら AI が決めた)
 * @param choices 選べるカテゴリ(自分のカテゴリ → 標準のカテゴリ。AI が付けた分類は今のものだけ)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategorySheet(
    current: String,
    isEdited: Boolean,
    choices: List<CategoryEntry>,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentKey = CategoryNames.key(current)
    val currentEntry = choices.firstOrNull { CategoryNames.key(it.name) == currentKey }
    val explanation = currentEntry?.description?.takeIf { it.isNotEmpty() } ?: CategoryNames.AI_OTHER_DESCRIPTION
    // 選べるのは自分のカテゴリと標準のカテゴリ。AI が付けた分類は、今のものだけ(選び直さなくてよいように残す)
    val selectable = choices.filter { it.kind != CategoryKind.AI_OTHER || CategoryNames.key(it.name) == currentKey }
    val shown = if (selectable.any { CategoryNames.key(it.name) == currentKey }) {
        selectable
    } else {
        listOf(CategoryEntry(current, CategoryNames.AI_OTHER_DESCRIPTION, CategoryKind.AI_OTHER, 0)) + selectable
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("カテゴリ「${current}」", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(explanation, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (isEdited) "自分で選んだカテゴリです。AI の再処理でも変わりません。" else "AI が内容から選んだカテゴリです。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("カテゴリとは", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                CategoryNames.ABOUT_CATEGORY.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("カテゴリを変える", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .selectableGroup()
            ) {
                items(shown, key = { "${it.kind}-${it.name}" }) { entry ->
                    val selected = CategoryNames.key(entry.name) == currentKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onSelect(entry.name) })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (entry.description.isNotEmpty()) {
                                Text(
                                    entry.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            if (isEdited) {
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("AI に決め直してもらう") }
                Text(
                    "AI がタグとカテゴリを付け直します。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
