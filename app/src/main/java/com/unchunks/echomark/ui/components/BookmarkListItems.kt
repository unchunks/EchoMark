package com.unchunks.echomark.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.unchunks.components.composeui.formatRelativeTime
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.SearchMatch
import com.unchunks.echomark.domain.bookmark.model.readingMinutes
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * 一覧のコンパクト表示(#62)の1件。小さなサムネイルと、タイトル・出どころ・日時だけの1件1行(タイトルは2行まで)で、
 * 画面にたくさん並べる。要約とタグは出さない。
 *
 * @param onToggleFavorite 渡すと右端にお気に入りの星ボタンを出す。null ならお気に入りのときだけ小さな星を表示する
 * @param searchTerms 検索結果として出すときの検索語。タイトルで一致した文字を強調する
 * @param searchMatch 検索結果として出すときの、一致した場所(「一致: タイトル・本文」を1行で出す)
 */
@Composable
fun CompactBookmarkRow(
    bookmark: Bookmark,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (() -> Unit)? = null,
    nowMillis: Long = System.currentTimeMillis(),
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    selected: Boolean = false,
    searchTerms: List<String> = emptyList(),
    searchMatch: SearchMatch? = null
) {
    val domain = bookmark.displaySource()
    ListItemCard(onClick, onLongClick, onLongClickLabel, selected, modifier) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (onToggleFavorite != null) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookmarkThumbnail(bookmark = bookmark, domain = domain, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = rememberHighlighted(bookmark.title, searchTerms),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val readingMinutes = remember(bookmark.content) { bookmark.readingMinutes() }
                SourceLine(
                    source = domain,
                    relativeTime = formatRelativeTime(bookmark.createdAt, nowMillis),
                    showFavoriteMark = onToggleFavorite == null && bookmark.isFavorite,
                    unread = !bookmark.isRead,
                    readingMinutes = readingMinutes
                )
                if (searchMatch != null && !searchMatch.isSemanticOnly) {
                    Text(
                        text = "一致: ${searchMatch.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onToggleFavorite != null) {
                FavoriteButton(isFavorite = bookmark.isFavorite, onClick = onToggleFavorite)
            }
        }
    }
}

/**
 * 一覧のグリッド表示(#62)の1件。上に大きなサムネイル、下にタイトルと出どころ・日時を並べたタイル。
 * 要約・タグ・星ボタンは出さない(お気に入りは出どころの横の小さな星)。星やアーカイブなどの操作は長押しのメニューから。
 */
@Composable
fun BookmarkGridTile(
    bookmark: Bookmark,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    selected: Boolean = false,
    searchTerms: List<String> = emptyList()
) {
    val domain = bookmark.displaySource()
    ListItemCard(onClick, onLongClick, onLongClickLabel, selected, modifier) {
        Column {
            // 幅いっぱい・高さ固定の枠に、サムネイルを切り出して入れる
            BookmarkThumbnail(
                bookmark = bookmark,
                domain = domain,
                size = TILE_THUMBNAIL_DECODE_SIZE,
                modifier = Modifier.fillMaxWidth().height(TILE_THUMBNAIL_HEIGHT)
            )
            Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp)) {
                Text(
                    text = rememberHighlighted(bookmark.title, searchTerms),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val readingMinutes = remember(bookmark.content) { bookmark.readingMinutes() }
                SourceLine(
                    source = domain,
                    relativeTime = formatRelativeTime(bookmark.createdAt, nowMillis),
                    showFavoriteMark = bookmark.isFavorite,
                    unread = !bookmark.isRead,
                    readingMinutes = readingMinutes
                )
            }
        }
    }
}

private val TILE_THUMBNAIL_HEIGHT = 104.dp

/** サムネイルを作るときの画像の大きさの目安(タイルの幅は画面によるので、少し大きめにしておく) */
private val TILE_THUMBNAIL_DECODE_SIZE = 180.dp

/** コンパクト・グリッドで共通の、タップ(と長押し)できるカード。選択中は枠と背景で強調し、TalkBack では「選択済み」と読む。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItemCard(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
    selected: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow
    )
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .clip(CardDefaults.shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { longClick ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        longClick()
                    }
                },
                onLongClickLabel = onLongClickLabel
            ),
        colors = colors,
        border = border
    ) {
        content()
    }
}

@PreviewLightDark
@Composable
private fun BookmarkListItemsPreview() {
    EchoMarkTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactBookmarkRow(PreviewSamples.urlBookmark, onClick = {}, onToggleFavorite = {}, nowMillis = PreviewSamples.NOW)
                CompactBookmarkRow(PreviewSamples.textBookmark, onClick = {}, nowMillis = PreviewSamples.NOW)
                BookmarkGridTile(PreviewSamples.urlBookmark, onClick = {}, nowMillis = PreviewSamples.NOW, modifier = Modifier.width(180.dp))
            }
        }
    }
}
