package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.SearchSuggestion
import com.unchunks.echomark.ui.common.displayName
import com.unchunks.echomark.ui.common.icon

/** 保存した日からの期間の表示名 */
fun CreatedPeriod.label(): String = when (this) {
    CreatedPeriod.PAST_WEEK -> "1週間以内"
    CreatedPeriod.PAST_MONTH -> "1か月以内"
    CreatedPeriod.PAST_3_MONTHS -> "3か月以内"
    CreatedPeriod.PAST_YEAR -> "1年以内"
}

/**
 * 検索欄の下に出す候補を横に並べる(#55)。入力が空のときは最近の検索(1件ずつ消せて、最後に「履歴をすべて削除」)、
 * 入力があるときは履歴・タグ・カテゴリの候補。
 * @param canRemoveHistory 履歴を消せる形にするか(入力が空のとき)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSuggestionRow(
    suggestions: List<SearchSuggestion>,
    canRemoveHistory: Boolean,
    onSelect: (SearchSuggestion) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(suggestions, key = { suggestionKey(it) }) { suggestion ->
            when (suggestion) {
                is SearchSuggestion.History -> if (canRemoveHistory) {
                    InputChip(
                        selected = false,
                        onClick = { onSelect(suggestion) },
                        label = { Text(suggestion.query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(InputChipDefaults.IconSize)
                                    .clickable(onClickLabel = "「${suggestion.query}」を履歴から消す") { onRemoveHistory(suggestion.query) }
                            )
                        },
                        modifier = Modifier.semantics { contentDescription = "最近の検索「${suggestion.query}」" }
                    )
                } else {
                    AssistChip(
                        onClick = { onSelect(suggestion) },
                        label = { Text(suggestion.query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        modifier = Modifier.semantics { contentDescription = "最近の検索「${suggestion.query}」" }
                    )
                }
                is SearchSuggestion.Tag -> AssistChip(
                    onClick = { onSelect(suggestion) },
                    label = { Text("#${suggestion.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    modifier = Modifier.semantics { contentDescription = "タグ「${suggestion.name}」で絞り込む" }
                )
                is SearchSuggestion.Category -> AssistChip(
                    onClick = { onSelect(suggestion) },
                    label = { Text(suggestion.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Outlined.Category, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    modifier = Modifier.semantics { contentDescription = "カテゴリ「${suggestion.name}」で絞り込む" }
                )
            }
        }
        if (canRemoveHistory) {
            item(key = "clear_history") {
                TextButton(onClick = onClearHistory) { Text("履歴をすべて削除") }
            }
        }
    }
}

private fun suggestionKey(suggestion: SearchSuggestion): String = when (suggestion) {
    is SearchSuggestion.History -> "history_${suggestion.query}"
    is SearchSuggestion.Tag -> "tag_${suggestion.id}"
    is SearchSuggestion.Category -> "category_${suggestion.name}"
}

/**
 * 選んでいる種類・期間を、外せるチップにして並べる(絞り込みのチップの列に足す)。
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.conditionChips(
    selectedTypes: Set<BookmarkType>,
    period: CreatedPeriod?,
    onTypeToggle: (BookmarkType) -> Unit,
    onPeriodToggle: (CreatedPeriod) -> Unit
) {
    items(BookmarkType.entries.filter { it in selectedTypes }, key = { "type_${it.name}" }) { type ->
        ConditionChip(label = type.displayName(), onRemove = { onTypeToggle(type) })
    }
    if (period != null) {
        item(key = "period") {
            ConditionChip(label = period.label(), onRemove = { onPeriodToggle(period) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, maxLines = 1) },
        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
        modifier = Modifier.semantics { contentDescription = "${label}で絞り込み中。タップで外す" }
    )
}

/**
 * 種類(リンク・メモ・画像など。複数選べる)と、保存した日からの期間(1つ選ぶ)を選ぶシート(#56)。
 * 選ぶとすぐ一覧(検索中なら検索結果)に反映される。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ConditionSheet(
    selectedTypes: Set<BookmarkType>,
    period: CreatedPeriod?,
    onTypeToggle: (BookmarkType) -> Unit,
    onPeriodToggle: (CreatedPeriod) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text("種類", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BookmarkType.entries.forEach { type ->
                    val selected = type in selectedTypes
                    FilterChip(
                        selected = selected,
                        onClick = { onTypeToggle(type) },
                        label = { Text(type.displayName()) },
                        leadingIcon = {
                            Icon(
                                if (selected) Icons.Filled.Check else type.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("保存した時期", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CreatedPeriod.entries.forEach { entry ->
                    val selected = entry == period
                    FilterChip(
                        selected = selected,
                        onClick = { onPeriodToggle(entry) },
                        label = { Text(entry.label()) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClear, enabled = selectedTypes.isNotEmpty() || period != null) { Text("種類と時期を外す") }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}
