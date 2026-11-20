package com.unchunks.echomark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.MAX_ATTACHMENT_BYTES
import com.unchunks.echomark.ui.common.SelectedFile
import com.unchunks.echomark.ui.common.icon

/**
 * ファイルの小さなサムネイル。画像なら [imageModel](content:// の URI・File など)を読み込み、
 * それ以外(と読み込み中・失敗時)は種類のアイコンを見せる。
 */
@Composable
fun FileTypeThumbnail(
    type: BookmarkType?,
    imageModel: Any?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            type?.icon() ?: Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size / 2.2f)
        )
        if (type == BookmarkType.IMAGE && imageModel != null) {
            // 装飾扱い(内容はファイル名で伝わる)
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 保存しようとしているファイル1件(サムネイル・ファイル名・種類とサイズ)。
 * 保存できない(大きすぎる・未対応の形式)ときは理由を出す。[onRemove] を渡すと「外す」ボタンを出す。
 * 画像を別に大きく見せているときは [showThumbnail] を false にする。
 */
@Composable
fun SelectedFileRow(
    file: SelectedFile,
    modifier: Modifier = Modifier,
    showThumbnail: Boolean = true,
    onRemove: (() -> Unit)? = null
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showThumbnail) {
            FileTypeThumbnail(type = file.type, imageModel = file.uri, size = 48.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                file.displayName ?: "名前のないファイル",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            val problem = when {
                file.type == null -> "この形式は保存できません"
                file.isTooLarge -> "${MAX_ATTACHMENT_BYTES / (1024 * 1024)}MB を超えるため保存できません"
                else -> null
            }
            Text(
                if (problem != null) "${file.description} · $problem" else file.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "「${file.displayName ?: "ファイル"}」を外す")
            }
        }
    }
}
