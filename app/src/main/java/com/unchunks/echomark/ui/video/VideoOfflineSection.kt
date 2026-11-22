package com.unchunks.echomark.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.video.VideoDownloadWork
import com.unchunks.echomark.domain.video.VideoFileSourceKind
import com.unchunks.echomark.domain.video.VideoOfflineStage
import com.unchunks.echomark.domain.video.videoFileSourceKind
import com.unchunks.echomark.ui.common.formatFileSize
import com.unchunks.echomark.domain.video.VideoOfflineWork
import com.unchunks.echomark.domain.video.hasVideoTranscript
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 動画のオフライン保存の操作 */
class VideoOfflineCallbacks(
    /** 字幕・サムネイルを保存する(保存し直しも) */
    val onSave: () -> Unit = {},
    /** 動いている保存をやめる */
    val onCancel: () -> Unit = {},
    /** 保存したサムネイルと記録を消す(確かめてから) */
    val onRemove: () -> Unit = {},
    /** 動画のファイルを落とす(ダウンロードが認められているものだけ) */
    val onDownloadVideo: () -> Unit = {},
    /** 落とすのをやめる */
    val onCancelDownload: () -> Unit = {},
    /** 保存した動画をアプリの中で再生する */
    val onPlayVideo: () -> Unit = {},
    /** 保存した動画のファイルを消す(確かめてから) */
    val onRemoveVideo: () -> Unit = {}
)

/**
 * 詳細画面の「オフラインで見る」。動画サイトのページのブックマーク(isOfflineVideoTarget)だけに出す。
 * 動画の映像・音声は保存せず、字幕(本文)・要約・サムネイルを保存して、通信できなくても内容を見られるようにする。
 * 保存前は保存のボタン、保存中は進み具合とやめるボタン、保存後は保存したものの一覧・保存し直し・削除。
 * ダウンロードが認められた動画(直接のリンク・PeerTube)では、動画のファイルの保存・再生・削除も出す
 * (直接のリンクは字幕もサムネイルも無いので、動画のファイルの欄だけ)。
 */
@Composable
fun VideoOfflineSection(
    bookmark: Bookmark,
    state: VideoOfflineUiState,
    callbacks: VideoOfflineCallbacks,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.OfflinePin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("オフラインで見る", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            val fileKind = bookmark.videoFileSourceKind()
            if (fileKind != VideoFileSourceKind.DIRECT_FILE) TranscriptBlock(bookmark, state, callbacks, fileKind)
            if (fileKind != null) {
                if (fileKind != VideoFileSourceKind.DIRECT_FILE) HorizontalDivider()
                VideoFileBlock(state, callbacks, fileKind)
            }
        }
    }
}

/** 字幕・要約・サムネイルの保存 */
@Composable
private fun TranscriptBlock(
    bookmark: Bookmark,
    state: VideoOfflineUiState,
    callbacks: VideoOfflineCallbacks,
    fileKind: VideoFileSourceKind?
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val work = state.work
            val saved = state.saved
            when {
                work is VideoOfflineWork.Running -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stageText(work.stage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = callbacks.onCancel) { Text("やめる") }
                }
                saved != null -> {
                    Text(
                        text = "${formatSavedAt(saved.savedAt)}に保存しました。通信できなくても、次のものを見られます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SavedItems(bookmark, hasThumbnail = state.thumbnailFile != null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = callbacks.onSave) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("保存し直す", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                        }
                        TextButton(
                            onClick = callbacks.onRemove,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("削除", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                        }
                    }
                }
                else -> {
                    Text(
                        text = "字幕・要約・サムネイルを端末に保存して、通信できなくても動画の内容を見られるようにします。" +
                            if (fileKind == null) {
                                "動画そのものは保存しません(サイトの規約で認められていないため)。保存するときは通信します"
                            } else {
                                "保存するときは通信します"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = callbacks.onSave) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("オフライン用に保存", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
    }
}

/**
 * 動画のファイル(ダウンロードが認められたものだけ)。保存前は保存のボタン(Wi-Fi のときだけの設定なら、その旨)、
 * 落としている間は進み具合とやめるボタン、保存後は再生・削除。
 */
@Composable
private fun VideoFileBlock(state: VideoOfflineUiState, callbacks: VideoOfflineCallbacks, fileKind: VideoFileSourceKind) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("動画のファイル", style = MaterialTheme.typography.titleSmall)
        val download = state.download
        val video = state.saved?.video
        when {
            download is VideoDownloadWork.Waiting -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = if (state.wifiOnly) "Wi-Fi につながるのを待っています…" else "通信できるようになるのを待っています…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = callbacks.onCancelDownload) { Text("やめる") }
            }
            download is VideoDownloadWork.Downloading -> {
                val total = download.totalBytes
                if (total != null) {
                    LinearProgressIndicator(
                        progress = { (download.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = "ダウンロードしています… " + formatFileSize(download.downloadedBytes) +
                        (total?.let { " / ${formatFileSize(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = callbacks.onCancelDownload) { Text("やめる") }
            }
            video != null && state.videoFile != null -> {
                Text(
                    text = "${formatSavedAt(video.savedAt)}に保存しました(" +
                        listOfNotNull(video.label, formatFileSize(video.size)).joinToString("・") + ")。通信できなくても再生できます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = callbacks.onPlayVideo) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("再生", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                    TextButton(
                        onClick = callbacks.onRemoveVideo,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("動画を削除", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
            else -> {
                Text(
                    text = when (fileKind) {
                        VideoFileSourceKind.DIRECT_FILE -> "リンク先の動画のファイルを端末に保存して、通信できなくても再生できるようにします。"
                        VideoFileSourceKind.PEERTUBE ->
                            "投稿者がダウンロードを許している動画は、ファイルを端末に保存して、通信できなくても再生できます(720p まで)。"
                    } + if (state.wifiOnly) "Wi-Fi につながっているときだけダウンロードします" else "モバイル通信でもダウンロードします",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = callbacks.onDownloadVideo) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("動画を保存", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

/** 保存したもの(字幕・要約・サムネイル)が揃っているか */
@Composable
private fun SavedItems(bookmark: Bookmark, hasThumbnail: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val hasTranscript = hasVideoTranscript(bookmark.content)
        SavedItem("字幕", if (hasTranscript) ItemState.SAVED else ItemState.MISSING, missingText = "この動画からは取れませんでした")
        val summaryState = when {
            !bookmark.summary.isNullOrBlank() -> ItemState.SAVED
            bookmark.aiStatus == AiStatus.PENDING || bookmark.aiStatus == AiStatus.PROCESSING -> ItemState.PENDING
            else -> ItemState.MISSING
        }
        SavedItem("要約", summaryState, missingText = "まだありません")
        SavedItem("サムネイル", if (hasThumbnail) ItemState.SAVED else ItemState.MISSING, missingText = "保存できませんでした")
    }
}

private enum class ItemState { SAVED, PENDING, MISSING }

@Composable
private fun SavedItem(label: String, state: ItemState, missingText: String) {
    val (icon: ImageVector, text: String) = when (state) {
        ItemState.SAVED -> Icons.Outlined.CheckCircle to "保存済み"
        ItemState.PENDING -> Icons.Outlined.Schedule to "作っています"
        ItemState.MISSING -> Icons.Outlined.RemoveCircleOutline to missingText
    }
    val tint = if (state == ItemState.SAVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // 読み上げでは「字幕: 保存済み」のように1つにまとめて読む
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {}
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text("$label: $text", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun stageText(stage: VideoOfflineStage): String = when (stage) {
    VideoOfflineStage.WAITING -> "通信できるようになるのを待っています…"
    VideoOfflineStage.TRANSCRIPT -> "字幕を確かめています…"
    VideoOfflineStage.THUMBNAIL -> "サムネイルを保存しています…"
}

private fun formatSavedAt(millis: Long): String = SimpleDateFormat("yyyy年M月d日 H:mm", Locale.JAPAN).format(Date(millis))
