package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unchunks.components.filestorage.formatBytes
import com.unchunks.echomark.data.ai.model.CatalogDownloadState
import com.unchunks.echomark.data.ai.model.InstalledModels
import com.unchunks.echomark.data.ai.model.ModelCatalog
import com.unchunks.echomark.data.ai.model.ModelCatalogEntry
import com.unchunks.echomark.data.ai.model.PublishedModelPerformance
import com.unchunks.echomark.ui.common.openUrl
import com.unchunks.echomark.ui.settings.japaneseParagraph
import java.util.Locale

/*
 * AI 設定画面の「おすすめのモデル」。公開されているモデルの一覧と、ダウンロード。
 */

/** [entry] を取り込み済みか(ファイル名と大きさで見る。手で取り込んだものも含む)。 */
internal fun InstalledModels.contains(entry: ModelCatalogEntry): Boolean =
    models.any { it.displayName == entry.fileName && it.sizeBytes == entry.sizeBytes }

/** 公開ページの性能の1行。 */
internal fun publishedPerformanceSummary(published: PublishedModelPerformance): String = buildString {
    append("公開の測定例(${published.device} の ${published.backend.name}): 出力 ")
    append(String.format(Locale.ROOT, "%.1f", published.decodeTokensPerSecond))
    append(" トークン/秒")
    published.memoryMegabytes?.let { append("・メモリ $it MB") }
}

/** おすすめのモデルの一覧。ダウンロードするのは一度に1つだけ。 */
@Composable
internal fun ModelCatalogSection(
    entries: List<ModelCatalogEntry>,
    installed: InstalledModels,
    state: CatalogDownloadState,
    onDownload: (ModelCatalogEntry) -> Unit,
    onCancel: () -> Unit
) {
    var confirming by remember { mutableStateOf<ModelCatalogEntry?>(null) }
    entries.forEach { entry ->
        ModelCatalogCard(
            entry = entry,
            isInstalled = installed.contains(entry),
            state = state,
            onDownload = { confirming = entry },
            onCancel = onCancel
        )
    }
    Text(
        "モデルの情報は Hugging Face の公開ページの ${ModelCatalog.CHECKED_ON} 時点のものです。速さとメモリは公開ページの測定例で、" +
            "この端末の値ではありません。日本語の回答の品質はモデルによって違います。\n" +
            "ダウンロードは Wi-Fi のときだけ行います。アプリを閉じても続き、終わった後にこの画面を開くと取り込みます。",
        style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    confirming?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("「${entry.name}」をダウンロードしますか?") },
            text = {
                Text(
                    "${formatBytes(entry.sizeBytes)}を Hugging Face からダウンロードします(ライセンス: ${entry.license})。" +
                        "取り込むまでの間は、その2倍の空き容量を使います。",
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        onDownload(entry)
                    }
                ) { Text("ダウンロード") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun ModelCatalogCard(
    entry: ModelCatalogEntry,
    isInstalled: Boolean,
    state: CatalogDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatBytes(entry.sizeBytes)}・.${entry.format}・${entry.license}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(entry.suitedFor, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
            entry.published?.let {
                Text(
                    publishedPerformanceSummary(it),
                    style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CatalogAction(entry, isInstalled, state, onDownload, onCancel)
            TextButton(
                onClick = { openUrl(context, entry.pageUrl) },
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
            ) {
                ButtonIcon(Icons.AutoMirrored.Outlined.OpenInNew)
                Text("モデルのページを開く")
            }
        }
    }
}

/** [entry] でできること(取り込み済み・ダウンロード中・ダウンロードする・ブラウザで入手する)。 */
@Composable
private fun CatalogAction(
    entry: ModelCatalogEntry,
    isInstalled: Boolean,
    state: CatalogDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    when {
        isInstalled -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("取り込み済み", style = MaterialTheme.typography.bodyMedium)
        }
        state is CatalogDownloadState.Downloading && state.entry == entry -> {
            LinearProgressIndicator(
                progress = { (state.downloadedBytes.toFloat() / entry.sizeBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (state.waitingForNetwork) {
                    "Wi-Fi の接続を待っています"
                } else {
                    "ダウンロード中… ${formatBytes(state.downloadedBytes)} / ${formatBytes(entry.sizeBytes)}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onCancel) { Text("ダウンロードを中止") }
        }
        state is CatalogDownloadState.Installing && state.entry == entry -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("ファイルを確かめて取り込んでいます…", style = MaterialTheme.typography.bodySmall)
        }
        entry.requiresAgreement -> Text(
            "入手には Hugging Face へのログインと利用規約への同意が必要です。下のページからダウンロードし、" +
                "「別のモデルを追加する」で取り込んでください。",
            style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> {
            if (state is CatalogDownloadState.Failed && state.entry == entry) {
                StatusLine(
                    icon = {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    text = state.message,
                    isError = true
                )
            }
            OutlinedButton(
                onClick = onDownload,
                enabled = state !is CatalogDownloadState.Downloading && state !is CatalogDownloadState.Installing
            ) {
                ButtonIcon(Icons.Outlined.Download)
                Text("ダウンロード")
            }
        }
    }
}
