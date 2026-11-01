package com.unchunks.echomark.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * 横スクロールの列に並べる小さめのブックマークカード(「今日の再発見」・関連ブックマーク用)。
 * サムネイル・出どころ・タイトル2行と、任意の補足 [caption](「2か月前に開いた」など)を表示する。
 *
 * @param containerColor カードの色。再発見など目立たせたいときに変える
 */
@Composable
fun CompactBookmarkCard(
    bookmark: Bookmark,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
) {
    val source = bookmark.displaySource()
    Card(
        onClick = onClick,
        modifier = modifier.width(264.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                BookmarkThumbnail(bookmark = bookmark, domain = source, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    // 2行分の高さをそろえ、横に並べたときにカードの高さが揃うようにする
                    Text(
                        text = bookmark.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (caption != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = 16.dp)
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun CompactBookmarkCardPreview() {
    EchoMarkTheme {
        Surface {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactBookmarkCard(PreviewSamples.urlBookmark, onClick = {}, caption = "3日前に開いた")
                CompactBookmarkCard(PreviewSamples.textBookmark, onClick = {})
            }
        }
    }
}
