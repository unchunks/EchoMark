package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unchunks.components.composeui.formatRelativeTime
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.ReadingTime
import com.unchunks.echomark.domain.bookmark.model.Recommendation
import com.unchunks.echomark.domain.bookmark.model.RecommendationReason
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.components.CompactBookmarkCard
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.theme.EchoBubbleShape
import com.unchunks.echomark.ui.theme.EchoMarkDesign

@Composable
internal fun SearchResultHeader(count: Int, isSearching: Boolean, semanticAvailable: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("検索中…", style = MaterialTheme.typography.titleSmall)
            } else {
                Text(
                    "${count}件見つかりました",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (semanticAvailable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(
                if (semanticAvailable) Icons.Outlined.AutoAwesome else Icons.Outlined.Info,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (semanticAvailable) {
                    "キーワードと意味の近さで検索しています"
                } else {
                    "キーワードで検索しています(意味での検索は AI の準備ができると使えます)"
                },
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

/** 意味の近さだけで見つかった結果に付ける小さな目印 */
@Composable
internal fun SemanticMatchLabel() {
    Row(
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text("意味が近い結果", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
internal fun ListHeader(count: Int, sortOrder: BookmarkSortOrder) {
    Text(
        text = "${count}件 · ${sortOrder.label()}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp)
    )
}

/** 「次に読む」(#61): まだ読んでいないものから、最近読んだものに近い・お気に入り・すぐ読める・新しいものを選んで横に並べる */
@Composable
internal fun ReadNextSection(items: List<Recommendation>, onOpen: (Long) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "次に読む",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "まだ読んでいないブックマークから",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.bookmark.id }) { recommendation ->
                CompactBookmarkCard(
                    bookmark = recommendation.bookmark,
                    onClick = { onOpen(recommendation.bookmark.id) },
                    caption = recommendation.reason.caption(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            }
        }
    }
}

/** おすすめの理由の言い方 */
internal fun RecommendationReason.caption(): String = when (this) {
    is RecommendationReason.SharedTag -> "最近「#${tag}」を読んでいます"
    is RecommendationReason.SharedCategory -> "最近「${category}」を読んでいます"
    RecommendationReason.Favorite -> "お気に入りで、まだ読んでいません"
    is RecommendationReason.Short -> "${ReadingTime.label(minutes)}で読めます"
    RecommendationReason.Recent -> "最近保存しました"
}

/**
 * 「今日の再発見」: しばらく開いていないブックマークを横に並べる。
 * エコーのデザインでは、吹き出し形のトーナルな帯に載せて一覧の主役にする。
 */
@Composable
internal fun RediscoverSection(items: List<Bookmark>, nowMillis: Long, onOpen: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val emphasized = EchoMarkDesign.style == DesignStyle.ECHO
    val accent = if (emphasized) colors.onPrimaryContainer else colors.primary
    val supporting = if (emphasized) colors.onPrimaryContainer else colors.onSurfaceVariant
    val sectionModifier = if (emphasized) {
        Modifier
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
            .background(colors.primaryContainer, EchoBubbleShape)
            .padding(bottom = 16.dp)
    } else {
        Modifier.padding(top = 4.dp, bottom = 8.dp)
    }
    Column(sectionModifier) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (emphasized) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "今日の再発見",
                    style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = accent,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "しばらく開いていないブックマーク",
                    style = MaterialTheme.typography.bodySmall,
                    color = supporting
                )
            }
        }
        LazyRow(
            // ソフトのカードは外に光と影を描くので、横並びの切り抜きで欠けないよう上下にも余白を取る
            contentPadding = PaddingValues(
                horizontal = if (emphasized) 8.dp else 16.dp,
                vertical = if (EchoMarkDesign.style == DesignStyle.SOFT) 12.dp else 0.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { bookmark ->
                CompactBookmarkCard(
                    bookmark = bookmark,
                    onClick = { onOpen(bookmark.id) },
                    caption = rediscoverCaption(bookmark, nowMillis),
                    // クラシックは一覧のカードより一段濃く、エコーは帯の上で地色から浮かせる。
                    // ほかは一覧のカードと同じ描き方(ペーパーは罫線、クリアは白、ソフトは押し出し)
                    containerColor = when (EchoMarkDesign.style) {
                        DesignStyle.CLASSIC -> colors.surfaceContainer
                        DesignStyle.ECHO -> colors.surface
                        else -> null
                    }
                )
            }
        }
    }
}

private fun rediscoverCaption(bookmark: Bookmark, nowMillis: Long): String =
    if (bookmark.lastAccessedAt <= bookmark.createdAt) {
        "${formatRelativeTime(bookmark.createdAt, nowMillis)}に保存してから未読"
    } else {
        "最後に開いたのは${formatRelativeTime(bookmark.lastAccessedAt, nowMillis)}"
    }

@Composable
internal fun ListEmptyState(uiState: BookmarkListUiState, callbacks: BookmarkListCallbacks) {
    when (uiState.emptyKind) {
        ListEmptyKind.FIRST_RUN -> EmptyState(
            icon = Icons.Outlined.BookmarkAdd,
            title = "まだブックマークがありません",
            description = "ブラウザなどの共有メニューから「EchoMarkに保存」を選ぶと、記事を保存して AI が要約します。",
            actionLabel = "URL を追加",
            actionIcon = Icons.Filled.Add,
            onAction = callbacks.onAddClick
        )
        ListEmptyKind.ALL_ARCHIVED -> EmptyState(
            icon = Icons.Outlined.Archive,
            title = "すべてアーカイブ済みです",
            description = "アーカイブしたブックマークは「アーカイブ」で見られます。",
            actionLabel = "アーカイブを見る",
            onAction = { callbacks.onFilterChange(BookmarkFilter.ARCHIVED) }
        )
        ListEmptyKind.NO_FAVORITES -> EmptyState(
            icon = Icons.Outlined.StarOutline,
            title = "お気に入りはまだありません",
            description = "カードの☆をタップすると、ここにまとまります。",
            actionLabel = "すべて表示",
            onAction = { callbacks.onFilterChange(BookmarkFilter.ACTIVE) }
        )
        ListEmptyKind.NO_ARCHIVED -> EmptyState(
            icon = Icons.Outlined.Archive,
            title = "アーカイブは空です",
            description = "カードを長押しして「アーカイブ」を選ぶと、ここへ移せます。"
        )
        ListEmptyKind.NO_FILTER_MATCH -> EmptyState(
            icon = Icons.AutoMirrored.Outlined.Label,
            title = "当てはまるブックマークがありません",
            description = filterSummary(uiState)?.let { "「${it}」に当てはまるブックマークは、この絞り込みにはありません。" },
            actionLabel = "絞り込みを解除",
            onAction = callbacks.onClearFilters
        )
        ListEmptyKind.NO_SEARCH_RESULT -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = "「${uiState.searchQuery.trim()}」は見つかりませんでした",
            description = if (uiState.isNarrowed) {
                "${filterSummary(uiState) ?: "選択した条件"}で絞り込んでいます。検索語をそのままに、絞り込みを解除して探せます。"
            } else if (uiState.filter != BookmarkFilter.ACTIVE) {
                "「${uiState.filter.label()}」の中を検索しています。検索語をそのままに、「すべて」の一覧から探せます。"
            } else if (uiState.semanticSearchAvailable) {
                "別の言葉や、探している内容を文章で入力してみてください。"
            } else {
                "別の言葉で試してみてください。AI の準備ができると、意味の近いものも探せます。"
            },
            actionLabel = when {
                uiState.isNarrowed -> "絞り込みを解除して検索"
                uiState.filter != BookmarkFilter.ACTIVE -> "すべてから検索"
                else -> "検索を閉じる"
            },
            onAction = {
                when {
                    uiState.isNarrowed -> callbacks.onClearFilters()
                    uiState.filter != BookmarkFilter.ACTIVE -> callbacks.onFilterChange(BookmarkFilter.ACTIVE)
                    else -> callbacks.onSearchActiveChange(false)
                }
            }
        )
        ListEmptyKind.NONE -> Unit
    }
}
