package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.common.displayName
import com.unchunks.echomark.ui.design.EchoMarkAssistChip
import com.unchunks.echomark.ui.design.EchoMarkFilterChip

/**
 * 絞り込み(すべて/お気に入り/アーカイブ)・カテゴリ・タグのチップを横スクロールで並べる。
 * カテゴリ・タグは複数選べる(タグは、2つ以上選ぶと下に「すべて含む / どれかを含む」の切り替えを出す)。
 * タグかカテゴリで絞り込んでいる間は、右端に「すべて解除」を固定して出す(選んだチップは先頭へ移るため、
 * 右へスクロールして選んだあとは見えなくなる。スクロールし直さずに一度で絞り込みを外せるように)。押すと、選んでいるタグとカテゴリをすべて外す
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterBar(
    filter: BookmarkFilter,
    tags: List<Tag>,
    selectedTagIds: Set<Long>,
    tagMatch: TagMatch,
    categories: List<String>,
    selectedCategories: Set<String>,
    selectedTypes: Set<BookmarkType>,
    period: CreatedPeriod?,
    readState: ReadState?,
    onFilterChange: (BookmarkFilter) -> Unit,
    onTagToggle: (Long) -> Unit,
    onCategoryToggle: (String) -> Unit,
    onTypeToggle: (BookmarkType) -> Unit,
    onPeriodToggle: (CreatedPeriod) -> Unit,
    onReadStateToggle: (ReadState) -> Unit,
    onOpenConditions: () -> Unit,
    onTagMatchChange: (TagMatch) -> Unit,
    onClearFilters: () -> Unit,
    showClear: Boolean
) {
    val narrowed = selectedTagIds.isNotEmpty() || selectedCategories.isNotEmpty() || selectedTypes.isNotEmpty() || period != null ||
        readState != null
    val clearVisible = showClear && narrowed
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChipRow(
                filter = filter,
                tags = tags,
                selectedTagIds = selectedTagIds,
                categories = categories,
                selectedCategories = selectedCategories,
                selectedTypes = selectedTypes,
                period = period,
                readState = readState,
                onFilterChange = onFilterChange,
                onTagToggle = onTagToggle,
                onCategoryToggle = onCategoryToggle,
                onTypeToggle = onTypeToggle,
                onPeriodToggle = onPeriodToggle,
                onReadStateToggle = onReadStateToggle,
                onOpenConditions = onOpenConditions,
                // 「すべて解除」を出す間は、右の余白を詰めてそちらに譲る
                endPadding = if (clearVisible) 8.dp else 16.dp,
                modifier = Modifier.weight(1f)
            )
            if (clearVisible) {
                ClearConditionsChip(onClick = onClearFilters, modifier = Modifier.padding(end = 16.dp))
            }
        }
        if (selectedTagIds.size >= 2) {
            TagMatchRow(count = selectedTagIds.size, tagMatch = tagMatch, onChange = onTagMatchChange)
        }
    }
}

/** タグ・カテゴリ・種類・期間・読んだかの絞り込みを一度に外すチップ */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClearConditionsChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    EchoMarkAssistChip(
        onClick = onClick,
        label = { Text("すべて解除") },
        leadingIcon = {
            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        modifier = modifier.semantics { contentDescription = "タグ・カテゴリ・種類・期間・読んだかの絞り込みをすべて解除" }
    )
}

/** 複数のタグを選んだときの組み合わせ方(すべて含む=AND / どれかを含む=OR)の切り替え。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagMatchRow(count: Int, tagMatch: TagMatch, onChange: (TagMatch) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = "選んだタグの組み合わせ方" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "選んだ${count}個のタグを",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        EchoMarkFilterChip(
            selected = tagMatch == TagMatch.ALL,
            onClick = { onChange(TagMatch.ALL) },
            label = { Text("すべて含む") }
        )
        EchoMarkFilterChip(
            selected = tagMatch == TagMatch.ANY,
            onClick = { onChange(TagMatch.ANY) },
            label = { Text("どれかを含む") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    filter: BookmarkFilter,
    tags: List<Tag>,
    selectedTagIds: Set<Long>,
    categories: List<String>,
    selectedCategories: Set<String>,
    selectedTypes: Set<BookmarkType>,
    period: CreatedPeriod?,
    readState: ReadState?,
    onFilterChange: (BookmarkFilter) -> Unit,
    onTagToggle: (Long) -> Unit,
    onCategoryToggle: (String) -> Unit,
    onTypeToggle: (BookmarkType) -> Unit,
    onPeriodToggle: (CreatedPeriod) -> Unit,
    onReadStateToggle: (ReadState) -> Unit,
    onOpenConditions: () -> Unit,
    endPadding: Dp,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(FILTER_ORDER, key = { "filter_${it.name}" }) { item ->
            val selected = filter == item
            EchoMarkFilterChip(
                selected = selected,
                onClick = { onFilterChange(item) },
                label = { Text(item.label()) },
                leadingIcon = item.icon(selected)?.let { icon ->
                    { Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                }
            )
        }
        item(key = "conditions") {
            val count = selectedTypes.size + (if (period != null) 1 else 0) + (if (readState != null) 1 else 0)
            EchoMarkAssistChip(
                onClick = onOpenConditions,
                label = { Text(if (count == 0) "条件" else "条件(${count})") },
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.semantics {
                    contentDescription = if (count == 0) "種類・保存した時期・読んだかで絞り込む" else "種類・保存した時期・読んだかで絞り込む(${count}件を選択中)"
                }
            )
        }
        conditionChips(selectedTypes, period, readState, onTypeToggle, onPeriodToggle, onReadStateToggle)
        if (categories.isNotEmpty()) {
            item(key = "divider_categories") {
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            }
            items(categories, key = { "category_${it}" }) { name ->
                val selected = selectedCategories.any { it.equals(name, ignoreCase = true) }
                EchoMarkFilterChip(
                    selected = selected,
                    onClick = { onCategoryToggle(name) },
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(
                            if (selected) Icons.Filled.Check else Icons.Outlined.Category,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    },
                    modifier = Modifier
                        .animateItem()
                        .semantics { contentDescription = "カテゴリ「${name}」" + if (selected) "(選択中。タップで外す)" else "" }
                )
            }
        }
        if (tags.isNotEmpty()) {
            item(key = "divider") {
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            }
        }
        items(tags, key = { "tag_${it.id}" }) { tag ->
            val selected = tag.id in selectedTagIds
            EchoMarkFilterChip(
                selected = selected,
                onClick = { onTagToggle(tag.id) },
                label = { Text("#${tag.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = if (selected) {
                    { Icon(Icons.Outlined.Close, contentDescription = "絞り込みを解除", modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                modifier = Modifier.animateItem()
            )
        }
    }
}

/** 選んでいるタグ・カテゴリを、空状態の説明に出す形("#A と #B / 技術" など)にする。何も選んでいなければ null。 */
internal fun filterSummary(uiState: BookmarkListUiState): String? {
    val tags = uiState.selectedTags
    val tagText = when {
        tags.isEmpty() -> null
        tags.size == 1 -> "#${tags.single().name}"
        else -> tags.joinToString(if (uiState.tagMatch == TagMatch.ALL) " かつ " else " または ") { "#${it.name}" }
    }
    val categoryText = uiState.selectedCategories.takeIf { it.isNotEmpty() }?.joinToString(" または ")?.let { "カテゴリ: ${it}" }
    val typeText = uiState.selectedTypes.takeIf { it.isNotEmpty() }?.joinToString(" または ") { it.displayName() }?.let { "種類: ${it}" }
    val periodText = uiState.period?.let { "保存した時期: ${it.label()}" }
    val readText = uiState.readState?.label()
    return listOfNotNull(tagText, categoryText, typeText, periodText, readText).takeIf { it.isNotEmpty() }?.joinToString("、")
}

/** 絞り込みチップの並び(すべて → お気に入り → アーカイブ) */
private val FILTER_ORDER = listOf(BookmarkFilter.ACTIVE, BookmarkFilter.FAVORITES, BookmarkFilter.ARCHIVED)

internal fun BookmarkFilter.label(): String = when (this) {
    BookmarkFilter.ACTIVE -> "すべて"
    BookmarkFilter.FAVORITES -> "お気に入り"
    BookmarkFilter.ARCHIVED -> "アーカイブ"
}

internal fun BookmarkFilter.icon(selected: Boolean): ImageVector? = when (this) {
    BookmarkFilter.ACTIVE -> null
    BookmarkFilter.FAVORITES -> if (selected) Icons.Filled.Star else Icons.Outlined.StarOutline
    BookmarkFilter.ARCHIVED -> Icons.Outlined.Archive
}
