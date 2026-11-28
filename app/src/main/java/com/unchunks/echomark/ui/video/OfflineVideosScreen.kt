package com.unchunks.echomark.ui.video

import com.unchunks.echomark.ui.components.sectionHeaderColor
import com.unchunks.echomark.ui.components.sectionHeaderStyle
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ListItemDefaults
import com.unchunks.echomark.ui.design.GroupPosition
import com.unchunks.echomark.ui.design.GroupedRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.video.SavedVideoListItem
import com.unchunks.echomark.domain.video.VideoDownloadSettings
import com.unchunks.echomark.ui.common.formatFileSize
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.unchunks.echomark.ui.design.EchoMarkOutlinedButton
import com.unchunks.echomark.ui.design.EchoMarkSwitch
import com.unchunks.echomark.ui.design.EchoMarkTopAppBar
import com.unchunks.echomark.ui.design.DesignSurface

/** 「オフラインの動画」の画面(設定の「データ」の欄から開く) */
@Composable
fun OfflineVideosScreen(
    onBack: () -> Unit,
    viewModel: OfflineVideosViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(message) }
        }
    }

    OfflineVideosContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onWifiOnlyChange = viewModel::setWifiOnly,
        onMaxTotalBytesChange = viewModel::setMaxTotalBytes,
        onPlay = { item -> context.startActivity(VideoPlayerActivity.intent(context, item.path, item.title)) },
        onDelete = viewModel::delete,
        onDeleteAll = viewModel::deleteAll
    )
}

/**
 * 「オフラインの動画」の本体(状態を受け取って描くだけ)。
 * 上から: 使っている量(上限と端末の空き容量) → ダウンロードの設定(Wi-Fi のときだけ・上限) → 保存した動画の一覧 → すべて削除。
 * 削除はどれも確かめてから行う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineVideosContent(
    uiState: OfflineVideosUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onMaxTotalBytesChange: (Long) -> Unit,
    onPlay: (SavedVideoListItem) -> Unit,
    onDelete: (SavedVideoListItem) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var limitDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SavedVideoListItem?>(null) }
    var deleteAllConfirm by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EchoMarkTopAppBar(
                title = { Text("オフラインの動画", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            ReadableLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "usage") { UsageCard(uiState) }
                item(key = "settings-header") { SectionTitle("ダウンロード") }
                // ダウンロードの設定の2行を1つのまとまりにする(地色・区切り・角丸はデザインに従う)
                item(key = "wifi") {
                    GroupedRow(GroupPosition(first = true, last = false), dividerStart = 16.dp) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Wi-Fi のときだけダウンロードする") },
                            supportingContent = { Text("オフにすると、モバイル通信でもダウンロードします(大きなファイルは通信量にご注意ください)") },
                            trailingContent = { EchoMarkSwitch(checked = uiState.settings.wifiOnly, onCheckedChange = null) },
                            modifier = Modifier.toggleable(
                                value = uiState.settings.wifiOnly,
                                role = Role.Switch,
                                onValueChange = onWifiOnlyChange
                            )
                        )
                    }
                }
                item(key = "limit") {
                    GroupedRow(GroupPosition(first = false, last = true), dividerStart = 16.dp) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("保存する上限") },
                            supportingContent = { Text("保存する動画の合計が ${formatFileSize(uiState.settings.maxTotalBytes)} を超えるときは、ダウンロードしません") },
                            modifier = Modifier.clickable(onClickLabel = "上限を変える") { limitDialog = true }
                        )
                    }
                }
                item(key = "list-header") { SectionTitle("保存した動画(${uiState.items.size})") }
                if (uiState.items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "まだありません。動画のファイルへのリンクや、ダウンロードが許されている PeerTube の動画は、" +
                                "ブックマークの詳細の「オフラインで見る」から保存できます。YouTube などの動画は、字幕・要約・サムネイルだけを保存します。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    uiState.items.forEachIndexed { index, item ->
                        item(key = "video-${item.bookmarkId}") {
                            GroupedRow(GroupPosition.of(index, uiState.items.size), dividerStart = 16.dp) {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(listOfNotNull(item.label, formatFileSize(item.size), formatSavedAt(item.savedAt) + "に保存").joinToString("・"))
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { deleteTarget = item }) {
                                            Icon(Icons.Outlined.Delete, contentDescription = "「${item.title}」の動画を削除")
                                        }
                                    },
                                    modifier = Modifier.clickable(onClickLabel = "再生") { onPlay(item) }
                                )
                            }
                        }
                    }
                    item(key = "delete-all") {
                        EchoMarkOutlinedButton(
                            onClick = { deleteAllConfirm = true },
                            contentColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.padding(end = ButtonDefaults.IconSpacing))
                            Text("すべて削除")
                        }
                    }
                }
            }
        }
    }

    if (limitDialog) {
        LimitDialog(
            current = uiState.settings.maxTotalBytes,
            onSelect = {
                limitDialog = false
                onMaxTotalBytesChange(it)
            },
            onDismiss = { limitDialog = false }
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = "保存した動画を削除しますか?",
            text = "「${target.title}」の動画のファイル(${formatFileSize(target.size)})を削除します。ブックマークと字幕・要約・サムネイルは残ります。",
            onConfirm = {
                deleteTarget = null
                onDelete(target)
            },
            onDismiss = { deleteTarget = null }
        )
    }
    if (deleteAllConfirm) {
        ConfirmDeleteDialog(
            title = "保存した動画をすべて削除しますか?",
            text = "保存した動画のファイル(${uiState.items.size} 件、${formatFileSize(uiState.usedBytes)})と、ダウンロードの途中のものを削除します。" +
                "ブックマークと字幕・要約・サムネイルは残ります。",
            onConfirm = {
                deleteAllConfirm = false
                onDeleteAll()
            },
            onDismiss = { deleteAllConfirm = false }
        )
    }
}

/** 使っている量。上限に対する割合のバーと、端末の空き容量 */
@Composable
private fun UsageCard(uiState: OfflineVideosUiState) {
    DesignSurface(
        shape = MaterialTheme.shapes.large,
        classic = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${formatFileSize(uiState.usedBytes)} / 上限 ${formatFileSize(uiState.settings.maxTotalBytes)}",
                style = MaterialTheme.typography.titleMedium
            )
            LinearProgressIndicator(
                progress = { (uiState.usedBytes.toFloat() / uiState.settings.maxTotalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                listOfNotNull(
                    uiState.freeBytes?.let { "端末の空き ${formatFileSize(it)}" },
                    "動画はバックアップに入りません"
                ).joinToString("。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        // 見出しの書体・色はアプリ共通の見出し(SectionHeader)と同じく、デザインに従う
        style = sectionHeaderStyle(),
        color = sectionHeaderColor(),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }
    )
}

@Composable
private fun LimitDialog(current: Long, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存する上限") },
        text = {
            Column(Modifier.selectableGroup()) {
                VideoDownloadSettings.MAX_TOTAL_BYTES_CHOICES.forEach { bytes ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = bytes == current, role = Role.RadioButton, onClick = { onSelect(bytes) })
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(selected = bytes == current, onClick = null)
                        Text(formatFileSize(bytes), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun ConfirmDeleteDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("削除")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

private fun formatSavedAt(millis: Long): String = SimpleDateFormat("yyyy年M月d日", Locale.JAPAN).format(Date(millis))
