package com.unchunks.echomark.ui.snapshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** オフラインコピーの操作 */
class PageSnapshotCallbacks(
    /** ページを読み込んでオフラインコピーを作る(作り直しも) */
    val onSave: () -> Unit = {},
    /** 保存したコピーをアプリ内で開く(通信しない) */
    val onOpen: () -> Unit = {},
    /** 保存した PDF をほかのアプリで開く */
    val onOpenPdf: () -> Unit = {},
    val onRemove: () -> Unit = {}
)

/**
 * 詳細画面の「オフラインコピー」。http / https のリンクのブックマークだけに出す。
 * 保存前は保存のボタン、保存後は開く・PDF・作り直し・削除。
 * @param busy コピーを作っている(または保存している)最中
 */
@Composable
fun PageSnapshotSection(
    bookmark: Bookmark,
    callbacks: PageSnapshotCallbacks,
    modifier: Modifier = Modifier,
    busy: Boolean = false
) {
    if (!canSnapshot(bookmark.contentUri)) return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.OfflinePin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "オフラインコピー",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
            val savedAt = bookmark.snapshotAt
            if (bookmark.hasSnapshot && savedAt != null) {
                Text(
                    text = "${formatSavedAt(savedAt)}に保存しました。通信できなくても読めます" +
                        if (bookmark.snapshotPdfPath == null) "(PDF は作れませんでした)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = callbacks.onOpen, enabled = !busy) { Text("開く") }
                    if (bookmark.snapshotPdfPath != null) {
                        OutlinedButton(onClick = callbacks.onOpenPdf, enabled = !busy) {
                            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("PDF", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = callbacks.onSave, enabled = !busy) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("保存し直す", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                    TextButton(
                        onClick = callbacks.onRemove,
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("削除", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            } else {
                Text(
                    text = "ページを読み込んで、通信できなくても読める形(ページと PDF)で端末に保存します。保存するときは通信します",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = callbacks.onSave, enabled = !busy) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(
                        if (busy) "保存中…" else "オフライン用に保存",
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing)
                    )
                }
            }
        }
    }
}

private fun formatSavedAt(millis: Long): String = SimpleDateFormat("yyyy年M月d日 H:mm", Locale.JAPAN).format(Date(millis))
