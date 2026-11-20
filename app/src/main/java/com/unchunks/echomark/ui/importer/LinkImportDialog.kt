package com.unchunks.echomark.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.importer.ImportParseResult
import com.unchunks.echomark.domain.importer.MAX_IMPORT_LINKS

/**
 * 他のサービスから取り込むときの確認・進み具合・結果のダイアログ。状態を受け取って描くだけなので、
 * スクリーンショットテストで単体描画できる。[LinkImportState.Idle] のときは何も描かない。
 */
@Composable
fun LinkImportDialog(
    state: LinkImportState,
    onFolderAsTagChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit
) {
    when (state) {
        LinkImportState.Idle -> Unit
        LinkImportState.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("ファイルを読み込んでいます") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp)) } },
            confirmButton = {}
        )
        is LinkImportState.Preview -> PreviewDialog(state, onFolderAsTagChange, onImport, onClose)
        is LinkImportState.Importing -> AlertDialog(
            // 保存している間は閉じられない(途中でやめると、一部だけが取り込まれた状態になるため)
            onDismissRequest = {},
            title = { Text("取り込んでいます") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.done} / ${state.total} 件", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
        is LinkImportState.Done -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("取り込みました") },
            text = {
                val result = state.result
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        buildString {
                            append("${result.added} 件を保存しました")
                            if (result.duplicates > 0) append("(保存済みの ${result.duplicates} 件はスキップ)")
                            if (result.failed > 0) append("。${result.failed} 件は保存できませんでした")
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (result.added > 0) {
                        Text(
                            text = if (result.processingStarted) {
                                "ページの取得と要約を、1件ずつ順番に始めました。数が多いと時間がかかります。「処理の状況」から確認・中止できます。"
                            } else {
                                "ページの取得と要約は、設定に合わせて後で行います(「処理の状況」から確認できます)。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("閉じる") } }
        )
        is LinkImportState.Failed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("取り込めませんでした") },
            text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onClose) { Text("閉じる") } }
        )
    }
}

@Composable
private fun PreviewDialog(
    state: LinkImportState.Preview,
    onFolderAsTagChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit
) {
    val parsed = state.parsed
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${parsed.links.size} 件のリンクを取り込みます") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(summaryOf(parsed), style = MaterialTheme.typography.bodyMedium)
                if (parsed.hasFolders) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = state.folderAsTag, role = Role.Checkbox, onValueChange = onFolderAsTagChange),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = state.folderAsTag, onCheckedChange = null)
                        Text("フォルダ名をタグにする", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "保存済みの URL はスキップします。取り込んだリンクは、1件ずつ順番にページを取得して要約・タグ付けします。" +
                        "数が多いと時間がかかるので、「処理の状況」から止めることもできます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onImport) { Text("取り込む") } },
        dismissButton = { TextButton(onClick = onClose) { Text("キャンセル") } }
    )
}

/** 読み取った内容の説明(形式・タグ・フォルダ・アーカイブ・読み飛ばした件数) */
internal fun summaryOf(parsed: ImportParseResult): String {
    val parts = mutableListOf("形式: ${parsed.format.label}")
    val tagged = parsed.links.count { it.tags.isNotEmpty() }
    if (tagged > 0) parts += "タグ付き $tagged 件"
    if (parsed.hasFolders) parts += "フォルダ ${parsed.links.mapNotNull { it.folder }.distinct().size} 個"
    if (parsed.archivedCount > 0) parts += "アーカイブ済み ${parsed.archivedCount} 件(アーカイブとして取り込みます)"
    if (parsed.invalidCount > 0) parts += "URL が読めず読み飛ばしたもの ${parsed.invalidCount} 件"
    if (parsed.truncated) parts += "多すぎるため先頭の ${MAX_IMPORT_LINKS} 件までです"
    return parts.joinToString("\n")
}
