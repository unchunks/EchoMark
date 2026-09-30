package com.unchunks.echomark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.common.formatRelativeTime
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/** カードに表示するタグの最大数。残りは「+N」で示す */
private const val MAX_VISIBLE_TAGS = 3

/**
 * ブックマーク一覧の1件分のカード。
 * サムネイル(OG 画像。無ければドメインの頭文字/種類アイコン)・サイト名と相対日時・タイトル・要約2行・タグ・AI 状態を表示する。
 *
 * @param onToggleFavorite 渡すと右上にお気に入りの星ボタンを出す。null ならお気に入りのときだけ小さな星を表示する
 * @param nowMillis 相対日時の基準時刻。プレビューやスクリーンショットでは固定値を渡す
 */
@Composable
fun BookmarkCard(
    bookmark: Bookmark,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (() -> Unit)? = null,
    nowMillis: Long = System.currentTimeMillis()
) {
    val domain = bookmark.displaySource()
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                BookmarkThumbnail(bookmark = bookmark, domain = domain, size = 72.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SourceLine(
                        source = domain,
                        relativeTime = formatRelativeTime(bookmark.createdAt, nowMillis),
                        showFavoriteMark = onToggleFavorite == null && bookmark.isFavorite
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = bookmark.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onToggleFavorite != null) {
                    FavoriteButton(isFavorite = bookmark.isFavorite, onClick = onToggleFavorite)
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }

            val summary = bookmark.summary
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // 完了済み(DONE)は要約が見えていれば十分なのでバッジを出さない
            val showStatus = bookmark.aiStatus != AiStatus.DONE
            if (bookmark.tags.isNotEmpty() || showStatus) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (showStatus) AiStatusBadge(bookmark.aiStatus)
                    val visibleTags = bookmark.tags.take(MAX_VISIBLE_TAGS)
                    visibleTags.forEachIndexed { index, tag ->
                        // 最後のタグだけ残り幅に合わせて縮める(前のタグは本来の幅で表示する)
                        val chipModifier =
                            if (index == visibleTags.lastIndex) Modifier.weight(1f, fill = false) else Modifier
                        TagChip(name = tag, modifier = chipModifier)
                    }
                    val hidden = bookmark.tags.size - MAX_VISIBLE_TAGS
                    if (hidden > 0) {
                        Text(
                            text = "+$hidden",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** サイト名 > URL のドメイン > 種類名 の順で「どこから来たか」を返す */
private fun Bookmark.displaySource(): String =
    siteName?.takeIf { it.isNotBlank() }
        ?: contentUri?.let { extractDomain(it) }
        ?: type.displayName()

private fun BookmarkType.displayName(): String = when (this) {
    BookmarkType.URL -> "リンク"
    BookmarkType.TEXT -> "メモ"
    BookmarkType.IMAGE -> "画像"
    BookmarkType.PDF -> "PDF"
    BookmarkType.AUDIO -> "音声"
}

private fun BookmarkType.icon(): ImageVector = when (this) {
    BookmarkType.URL -> Icons.Outlined.Link
    BookmarkType.TEXT -> Icons.AutoMirrored.Outlined.Notes
    BookmarkType.IMAGE -> Icons.Outlined.Image
    BookmarkType.PDF -> Icons.Outlined.PictureAsPdf
    BookmarkType.AUDIO -> Icons.Outlined.Audiotrack
}

@Composable
private fun SourceLine(source: String, relativeTime: String, showFavoriteMark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = source,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = " · $relativeTime",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (showFavoriteMark) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "お気に入り",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            contentDescription = if (isFavorite) "お気に入りから外す" else "お気に入りに追加",
            tint = if (isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * サムネイル。OG 画像があれば読み込み、読み込み中・失敗時・画像なしのときはプレースホルダー
 * (URL はドメインの頭文字、それ以外は種類アイコン)を見せる。色はドメインごとに固定で散らす。
 */
@Composable
fun BookmarkThumbnail(
    bookmark: Bookmark,
    domain: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val (container, content) = placeholderColors(domain)
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        val initial = domain.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        if (bookmark.type == BookmarkType.URL && initial != null) {
            Text(
                text = initial.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = content
            )
        } else {
            Icon(bookmark.type.icon(), contentDescription = null, tint = content, modifier = Modifier.size(size / 2.5f))
        }
        val imageUrl = bookmark.imageUrl
        if (!imageUrl.isNullOrBlank()) {
            // 装飾扱い(内容はタイトルで伝わる)。失敗時は何も描かれず下のプレースホルダーが見える
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun placeholderColors(key: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer
    )
    return palette[Math.floorMod(key.hashCode(), palette.size)]
}

@PreviewLightDark
@Composable
private fun BookmarkCardPreview() {
    EchoMarkTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BookmarkCard(
                    bookmark = PreviewSamples.urlBookmark,
                    onClick = {},
                    onToggleFavorite = {},
                    nowMillis = PreviewSamples.NOW
                )
                BookmarkCard(bookmark = PreviewSamples.processingBookmark, onClick = {}, nowMillis = PreviewSamples.NOW)
                BookmarkCard(bookmark = PreviewSamples.textBookmark, onClick = {}, nowMillis = PreviewSamples.NOW)
            }
        }
    }
}
