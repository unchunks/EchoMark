package com.unchunks.echomark.ui.linkcheck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.repository.DeadLink
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.common.openUrl
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.detail.archiveSearchUrl
import com.unchunks.echomark.ui.detail.linkCheckedText
import com.unchunks.echomark.ui.snapshot.SnapshotViewerActivity
import kotlinx.coroutines.launch

/**
 * リンク切れの一覧(#94)。夜の確認で、続けて開けなかったと判断したブックマークを並べる。
 * 夜間の確認のオン・オフもここで切り替える。ViewModel をつなぐだけの薄いラッパー。
 * @param onOpenBookmark ブックマークをタップしたとき(詳細画面を開く)
 */
@Composable
fun BrokenLinksScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: BrokenLinksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is BrokenLinksMessage.Muted -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "「${message.title.take(20)}」の確認をやめました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.unmute(message.bookmarkId)
                    }
                    is BrokenLinksMessage.Checked -> snackbarHostState.showSnackbar(linkCheckedText(message.state))
                }
            }
        }
    }

    BrokenLinksContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenBookmark = onOpenBookmark,
        onEnabledChange = viewModel::setEnabled,
        onOpenOfflineCopy = { context.startActivity(SnapshotViewerActivity.intent(context, it.bookmark.id)) },
        onSearchArchive = { link -> link.bookmark.contentUri?.let { openUrl(context, archiveSearchUrl(it)) } },
        onRecheck = viewModel::recheck,
        onMute = viewModel::mute
    )
}

/** リンク切れの一覧の本体(状態を受け取って描くだけ)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrokenLinksContent(
    uiState: BrokenLinksUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onOpenOfflineCopy: (DeadLink) -> Unit,
    onSearchArchive: (DeadLink) -> Unit,
    onRecheck: (DeadLink) -> Unit,
    onMute: (DeadLink) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("リンク切れ", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage)
                else -> ReadableLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item(key = "settings") { CheckSettings(enabled = uiState.enabled, onEnabledChange = onEnabledChange) }
                    if (uiState.links.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = Icons.Outlined.CheckCircle,
                                title = "リンク切れは見つかっていません",
                                description = if (uiState.enabled) {
                                    "保存したリンクを夜に少しずつ確認して、続けて開けなかったものをここにお知らせします。"
                                } else {
                                    "夜の確認がオフです。オンにすると、続けて開けなかったリンクをここにお知らせします。"
                                },
                                // 縦にスクロールする部品を、一覧の中に高さの決まらないまま入れられないので、高さを決める
                                modifier = Modifier.height(320.dp)
                            )
                        }
                    } else {
                        item(key = "count") {
                            Text(
                                text = "${uiState.links.size}件のリンクが開けないようです",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(uiState.links.size, key = { uiState.links[it].bookmark.id }) { index ->
                            val link = uiState.links[index]
                            DeadLinkRow(
                                link = link,
                                checking = link.bookmark.id in uiState.checkingIds,
                                onClick = { onOpenBookmark(link.bookmark.id) },
                                onOpenOfflineCopy = { onOpenOfflineCopy(link) },
                                onSearchArchive = { onSearchArchive(link) },
                                onRecheck = { onRecheck(link) },
                                onMute = { onMute(link) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckSettings(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("夜にリンク切れを確認する", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        Text(
            text = "保存したリンクが開けるかを、夜に少しずつ確認します(1晩に100件まで。同じサイトには間をあけます)。" +
                "充電中だけ・Wi-Fi のときだけ、は設定の「処理」に従います。一時的な不具合で切れたとは言わないよう、" +
                "3回以上続けて、2日以上のあいだ開けなかったものだけをお知らせします。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeadLinkRow(
    link: DeadLink,
    checking: Boolean,
    onClick: () -> Unit,
    onOpenOfflineCopy: () -> Unit,
    onSearchArchive: () -> Unit,
    onRecheck: () -> Unit,
    onMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bookmark = link.bookmark
    ListItem(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Outlined.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        overlineContent = bookmark.contentUri?.let { url -> { Text(extractDomain(url), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        headlineContent = { Text(bookmark.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    text = if (checking) "確認中…" else "${link.failureCount}回続けて開けませんでした" +
                        (link.reason?.takeIf { it.isNotBlank() }?.let { "(${it})" } ?: ""),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (bookmark.hasSnapshot) {
                    Text("オフラインコピーあり", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "「${bookmark.title.take(20)}」のメニュー")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (bookmark.hasSnapshot) {
                        DropdownMenuItem(
                            text = { Text("オフラインコピーを開く") },
                            onClick = {
                                menuOpen = false
                                onOpenOfflineCopy()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Internet Archive で探す") },
                            onClick = {
                                menuOpen = false
                                onSearchArchive()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("もう一度確認") },
                        enabled = !checking,
                        onClick = {
                            menuOpen = false
                            onRecheck()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("確認しない") },
                        onClick = {
                            menuOpen = false
                            onMute()
                        }
                    )
                }
            }
        }
    )
}
