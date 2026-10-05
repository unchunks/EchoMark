package com.unchunks.echomark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Movie
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.bookmarkTypeOfMimeType
import com.unchunks.echomark.ui.attachment.rememberAttachmentFile
import com.unchunks.echomark.ui.attachment.rememberMediaDuration
import com.unchunks.echomark.ui.attachment.rememberPdfPage
import com.unchunks.echomark.ui.attachment.rememberVideoFrame
import com.unchunks.echomark.ui.common.displayName
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.common.fileKindName
import com.unchunks.echomark.ui.common.formatDuration
import com.unchunks.echomark.ui.common.formatFileSize
import com.unchunks.echomark.ui.common.icon
import coil3.request.ImageRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import java.io.File
import com.unchunks.echomark.ui.common.formatRelativeTime
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/** カードに表示するタグの最大数。残り(と幅に収まらなかった分)は「+N」で示す */
private const val MAX_VISIBLE_TAGS = 3

private val TAG_SPACING = 6.dp

/** 1つもタグが収まらないとき、先頭のタグを省略表示してでも出す最小の幅 */
private val MIN_SHRUNK_TAG_WIDTH = 56.dp

/**
 * ブックマーク一覧の1件分のカード。
 * サムネイル(OG 画像。無ければドメインの頭文字/種類アイコン)・サイト名と相対日時・タイトル・要約2行・タグ・AI 状態を表示する。
 *
 * @param onToggleFavorite 渡すと右上にお気に入りの星ボタンを出す。null ならお気に入りのときだけ小さな星を表示する
 * @param nowMillis 相対日時の基準時刻。プレビューやスクリーンショットでは固定値を渡す
 * @param onLongClick 渡すと長押しできる(操作メニューを出す用)。[onLongClickLabel] は TalkBack で読み上げる操作名
 */
@Composable
fun BookmarkCard(
    bookmark: Bookmark,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (() -> Unit)? = null,
    nowMillis: Long = System.currentTimeMillis(),
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null
) {
    val domain = bookmark.displaySource()
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    if (onLongClick == null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors) {
            BookmarkCardBody(bookmark, domain, onToggleFavorite, nowMillis)
        }
    } else {
        val haptics = LocalHapticFeedback.current
        Card(
            modifier = modifier
                .fillMaxWidth()
                .clip(CardDefaults.shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    onLongClickLabel = onLongClickLabel
                ),
            colors = colors
        ) {
            BookmarkCardBody(bookmark, domain, onToggleFavorite, nowMillis)
        }
    }
}

@Composable
private fun BookmarkCardBody(
    bookmark: Bookmark,
    domain: String,
    onToggleFavorite: (() -> Unit)?,
    nowMillis: Long
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
            TagLine(
                status = bookmark.aiStatus.takeIf { showStatus },
                tags = bookmark.tags,
                aiTags = bookmark.aiTags,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

/**
 * AI の状態とタグを1行に並べる。収まるタグだけを先頭から出し、出せなかった分は「+N」にまとめる
 * (タグが幅 0 に潰れたり、「+N」が縦に割れたりしない)。1つも収まらないときは、先頭のタグを省略表示で入れる。
 * [aiTags] に含まれるタグは AI が付けたものとして印を付ける。
 */
@Composable
private fun TagLine(
    status: AiStatus?,
    tags: List<String>,
    aiTags: Set<String>,
    modifier: Modifier = Modifier
) {
    SubcomposeLayout(modifier) { constraints ->
        val spacing = TAG_SPACING.roundToPx()
        val available = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // 同じ枠を1回の測定で2度組み立てないよう、測った結果を覚えておく
        val measured = mutableMapOf<Any, Placeable>()
        fun measure(slot: Any, maxWidth: Int = available, content: @Composable () -> Unit): Placeable =
            measured.getOrPut(slot) {
                subcompose(slot, content).first().measure(loose.copy(maxWidth = maxWidth.coerceAtLeast(0)))
            }
        fun rowWidth(items: List<Placeable>): Int =
            items.sumOf { it.width } + spacing * (items.size - 1).coerceAtLeast(0)
        fun more(hidden: Int): Placeable? =
            if (hidden > 0) measure("more_$hidden") { MoreTagsLabel(hidden) } else null

        val badge = status?.let { measure("badge") { AiStatusBadge(it) } }
        val candidates = tags.take(MAX_VISIBLE_TAGS)
        val chips = candidates.mapIndexed { index, tag -> measure("tag_$index") { TagChip(name = tag, isAi = tag in aiTags) } }

        var shown = chips.size
        var items: List<Placeable>
        while (true) {
            items = listOfNotNull(badge) + chips.take(shown) + listOfNotNull(more(tags.size - shown))
            if (shown == 0 || rowWidth(items) <= available) break
            shown--
        }
        if (shown == 0 && tags.isNotEmpty()) {
            val moreLabel = more(tags.size - 1)
            val fixed = listOfNotNull(badge, moreLabel)
            val remaining = available - rowWidth(fixed) - if (fixed.isEmpty()) 0 else spacing
            if (remaining >= MIN_SHRUNK_TAG_WIDTH.roundToPx()) {
                val chip = measure("tag_0_shrunk", maxWidth = remaining) { TagChip(name = tags.first(), isAi = tags.first() in aiTags) }
                items = listOfNotNull(badge, chip, moreLabel)
            }
        }

        val height = items.maxOfOrNull { it.height } ?: 0
        layout(rowWidth(items).coerceAtMost(available), height) {
            var x = 0
            items.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + spacing
            }
        }
    }
}

/** 表示しきれなかったタグの数 */
@Composable
private fun MoreTagsLabel(hidden: Int) {
    Text(
        text = "+$hidden",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.semantics { contentDescription = "ほか${hidden}件のタグ" }
    )
}

/** サイト名 > URL のドメイン > ファイルの種類とサイズ > 種類名 の順で「どこから来たか」を返す */
internal fun Bookmark.displaySource(): String =
    siteName?.takeIf { it.isNotBlank() }
        ?: contentUri?.let { extractDomain(it) }
        ?: fileSourceLabel()
        ?: type.displayName()

/** 保存したファイルなら「PDF · 2.1 MB」 */
private fun Bookmark.fileSourceLabel(): String? {
    if (filePath == null) return null
    val kind = bookmarkTypeOfMimeType(mimeType)?.fileKindName() ?: type.displayName()
    return listOfNotNull(kind, fileSize?.takeIf { it > 0 }?.let(::formatFileSize)).joinToString(" · ")
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
        // 保存したファイルがあれば、その種類で見せる(リンク先が PDF などのときも)
        val file = rememberAttachmentFile(bookmark)
        val fileType = if (file != null) bookmarkTypeOfMimeType(bookmark.mimeType) else null
        val initial = domain.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        if (bookmark.type == BookmarkType.URL && fileType == null && initial != null) {
            Text(
                text = initial.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = content
            )
        } else {
            Icon((fileType ?: bookmark.type).icon(), contentDescription = null, tint = content, modifier = Modifier.size(size / 2.5f))
        }
        val imageUrl = bookmark.imageUrl
        if (file != null && fileType != null) {
            FileThumbnailContent(file, fileType, size)
        } else if (!imageUrl.isNullOrBlank()) {
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

/**
 * 保存したファイルのサムネイル。画像は縮小して、PDF は1ページ目、動画は1秒目のコマ(再生の印つき)、
 * 音声は長さを見せる。作れなかったときは何も描かず、下の種類アイコンが見える。
 */
@Composable
private fun BoxScope.FileThumbnailContent(file: File, type: BookmarkType, size: Dp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    when (type) {
        BookmarkType.IMAGE -> AsyncImage(
            // 一覧では表示サイズまで縮小して読み込む(大きな写真をそのまま読まない)
            model = remember(file, sizePx) { ImageRequest.Builder(context).data(file).size(sizePx).build() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        BookmarkType.PDF -> {
            val page by rememberPdfPage(file, pageIndex = 0, widthPx = sizePx * 2)
            page?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        BookmarkType.VIDEO -> {
            val frame by rememberVideoFrame(file, widthPx = sizePx * 2)
            frame?.let {
                Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size / 2.8f)
                )
            }
            DurationLabel(file)
        }
        BookmarkType.AUDIO -> DurationLabel(file)
        else -> Unit
    }
}

/** 音声・動画の長さを右下に小さく出す(取れなければ出さない) */
@Composable
private fun BoxScope.DurationLabel(file: File) {
    val duration by rememberMediaDuration(file)
    duration?.takeIf { it > 0 }?.let { millis ->
        Text(
            text = formatDuration(millis),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
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
