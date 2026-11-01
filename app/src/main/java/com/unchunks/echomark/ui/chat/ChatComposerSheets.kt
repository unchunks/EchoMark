package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.model.ChatScopeChoices
import com.unchunks.echomark.domain.model.ChatTemplate

private val BookmarkFilter.scopeLabel: String
    get() = when (this) {
        BookmarkFilter.ACTIVE -> "すべて"
        BookmarkFilter.FAVORITES -> "お気に入り"
        BookmarkFilter.ARCHIVED -> "アーカイブ"
    }

/** AI が参照する範囲を選ぶシート。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScopeSheet(
    query: BookmarkQuery,
    choices: ChatScopeChoices,
    count: Int?,
    onChange: (BookmarkQuery) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ChatScopeSheetContent(query, choices, count, onChange)
    }
}

/**
 * 範囲の選択。一覧の絞り込みと同じ条件([BookmarkQuery])を使う。
 * @param count 今の範囲に入る保存の数。数えている間は null
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatScopeSheetContent(
    query: BookmarkQuery,
    choices: ChatScopeChoices,
    count: Int?,
    onChange: (BookmarkQuery) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "参照する範囲",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(BookmarkFilter.ACTIVE, BookmarkFilter.FAVORITES, BookmarkFilter.ARCHIVED).forEach { filter ->
                FilterChip(
                    selected = query.filter == filter,
                    onClick = { onChange(query.copy(filter = filter)) },
                    label = { Text(filter.scopeLabel) }
                )
            }
        }
        if (choices.tags.isNotEmpty()) {
            SectionLabel("タグ")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.tags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in query.tagIds,
                        onClick = { onChange(query.toggleTag(tag.id)) },
                        label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            if (query.tagIds.size >= 2) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = query.tagMatch == TagMatch.ALL,
                        onClick = { onChange(query.copy(tagMatch = TagMatch.ALL)) },
                        label = { Text("すべてのタグが付いたもの") }
                    )
                    FilterChip(
                        selected = query.tagMatch == TagMatch.ANY,
                        onClick = { onChange(query.copy(tagMatch = TagMatch.ANY)) },
                        label = { Text("どれかのタグが付いたもの") }
                    )
                }
            }
        }
        if (choices.categories.isNotEmpty()) {
            SectionLabel("カテゴリ")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.categories.forEach { name ->
                    FilterChip(
                        selected = query.categories.any { it.equals(name, ignoreCase = true) },
                        onClick = { onChange(query.toggleCategory(name)) },
                        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
        count?.let {
            Text(
                text = if (it == 0) "この範囲に保存はありません" else "${it}件の保存から答えます",
                style = MaterialTheme.typography.bodySmall,
                color = if (it == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (query != ChatScopeChoices.ALL) {
            TextButton(onClick = { onChange(ChatScopeChoices.ALL) }) { Text("すべての保存に戻す") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() }
    )
}

/** テンプレートを選ぶシート。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTemplateSheet(
    input: String,
    userTemplates: List<ChatTemplate>,
    onPick: (ChatTemplate) -> Unit,
    onSaveInput: () -> Unit,
    onDelete: (ChatTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ChatTemplateSheetContent(input, userTemplates, onPick, onSaveInput, onDelete)
    }
}

/**
 * テンプレートの一覧。押すと入力欄に入り、そのまま書き足して送れる。入力中の文は自分のテンプレートとして保存できる。
 * @param input 入力中の文
 */
@Composable
internal fun ChatTemplateSheetContent(
    input: String,
    userTemplates: List<ChatTemplate>,
    onPick: (ChatTemplate) -> Unit,
    onSaveInput: () -> Unit,
    onDelete: (ChatTemplate) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "テンプレート",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
        )
        if (input.isNotBlank()) {
            FilledTonalButton(
                onClick = onSaveInput,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            ) { Text("入力中の文をテンプレートに保存") }
        }
        if (userTemplates.isNotEmpty()) {
            TemplateSectionLabel("自分のテンプレート")
            userTemplates.forEach { template ->
                TemplateRow(template, onClick = { onPick(template) }, onDelete = { onDelete(template) })
            }
        }
        TemplateSectionLabel("定型")
        ChatTemplates.builtIn.forEach { template ->
            TemplateRow(template, onClick = { onPick(template) }, onDelete = null)
        }
    }
}

@Composable
private fun TemplateSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

@Composable
private fun TemplateRow(template: ChatTemplate, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(template.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = template.text,
                style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = onDelete?.let { delete ->
            {
                IconButton(onClick = delete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "「${template.title}」を削除")
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
