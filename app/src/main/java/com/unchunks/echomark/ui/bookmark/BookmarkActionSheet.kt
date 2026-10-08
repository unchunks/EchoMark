package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark

/** カードの長押しで出す操作メニュー(お気に入り・アーカイブ・共有・削除)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkActionSheet(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleArchive: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BookmarkActionSheetContent(
            bookmark = bookmark,
            onAction = { action ->
                onDismiss()
                action()
            },
            onToggleFavorite = onToggleFavorite,
            onToggleArchive = onToggleArchive,
            onShare = onShare,
            onDelete = onDelete
        )
    }
}

@Composable
internal fun BookmarkActionSheetContent(
    bookmark: Bookmark,
    onAction: (() -> Unit) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleArchive: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(
            text = bookmark.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        ActionItem(
            icon = if (bookmark.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            label = if (bookmark.isFavorite) "お気に入りから外す" else "お気に入りに追加",
            onClick = { onAction(onToggleFavorite) }
        )
        ActionItem(
            icon = if (bookmark.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
            label = if (bookmark.isArchived) "アーカイブから戻す" else "アーカイブ",
            onClick = { onAction(onToggleArchive) }
        )
        ActionItem(icon = Icons.Outlined.Share, label = "共有", onClick = { onAction(onShare) })
        ActionItem(
            icon = Icons.Outlined.Delete,
            label = "削除",
            onClick = { onAction(onDelete) },
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = color,
            leadingIconColor = color
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
    )
}
