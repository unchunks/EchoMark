package com.unchunks.echomark.ui.duplicates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateReason
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.DuplicateMatch
import com.unchunks.echomark.domain.repository.RelatedGroup
import com.unchunks.echomark.ui.components.BookmarkThumbnail
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.displaySource
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 画面の2つの表示(重複の候補・関連のまとまり) */
enum class DuplicatesTab { DUPLICATES, GROUPS }

/**
 * 重複の整理と、関連するブックマークのまとまり(#93, #35)。ViewModel をつなぐだけの薄いラッパー。
 * @param onOpenBookmark ブックマークをタップしたとき(詳細画面を開く)
 */
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is DuplicatesMessage.Merged -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "「${message.keptTitle.shortened()}」に統合しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoMerge(message.undo)
                    }
                    DuplicatesMessage.KeptBoth -> snackbarHostState.showSnackbar("両方残します。この組は次から出しません")
                    DuplicatesMessage.UndoDone -> snackbarHostState.showSnackbar("元に戻しました")
                    is DuplicatesMessage.Failed -> snackbarHostState.showSnackbar(message.message)
                }
            }
        }
    }

    DuplicatesContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenBookmark = onOpenBookmark,
        onRefresh = viewModel::refresh,
        onSensitivity = viewModel::setSensitivity,
        onMerge = viewModel::merge,
        onKeepBoth = viewModel::keepBoth
    )
}

/** 画面の本体(状態を受け取って描くだけ)。統合の確認ダイアログもここで出す。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesContent(
    uiState: DuplicatesUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onRefresh: () -> Unit,
    onSensitivity: (DuplicateSensitivity) -> Unit,
    onMerge: (DuplicateMatch, keepId: Long) -> Unit,
    onKeepBoth: (DuplicateMatch) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: DuplicatesTab = DuplicatesTab.DUPLICATES
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // 確認中の統合は「どの組か」と「どちらを残すか」を持つ。組は探し直しで変わりうるので、ID の組で見分ける
    var confirmPairKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmKeepId by rememberSaveable { mutableStateOf<Long?>(null) }
    val confirmMatch = uiState.duplicates.firstOrNull { it.pair.key == confirmPairKey }
    val confirmKeep = confirmMatch?.let { m -> listOf(m.first, m.second).firstOrNull { it.id == confirmKeepId } }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("重複と関連", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !uiState.isLoading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "探し直す")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            ReadableLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item(key = "sensitivity") {
                    SensitivitySection(uiState.sensitivity, onSensitivity)
                }
                item(key = "tabs") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = tab == DuplicatesTab.DUPLICATES,
                            onClick = { tab = DuplicatesTab.DUPLICATES },
                            label = { Text("重複の候補 ${uiState.duplicates.size}") }
                        )
                        FilterChip(
                            selected = tab == DuplicatesTab.GROUPS,
                            onClick = { tab = DuplicatesTab.GROUPS },
                            label = { Text("関連のまとまり ${uiState.groups.size}") }
                        )
                    }
                }
                if (uiState.indexIncomplete && !uiState.isLoading) {
                    item(key = "index-note") {
                        Text(
                            text = "検索用の索引がまだ全部できていません(${uiState.embeddedCount}/${uiState.totalCount}件)。" +
                                "できていないものは、URL が同じ場合しか見つかりません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                when {
                    uiState.isLoading -> item(key = "loading") {
                        LoadingState(message = "似たブックマークを探しています…", modifier = Modifier.height(240.dp))
                    }
                    uiState.errorMessage != null -> item(key = "error") {
                        ErrorState(message = uiState.errorMessage, onRetry = onRefresh, modifier = Modifier.height(280.dp))
                    }
                    tab == DuplicatesTab.DUPLICATES -> duplicateItems(
                        matches = uiState.duplicates,
                        onOpenBookmark = onOpenBookmark,
                        onKeep = { match, keepId ->
                            confirmPairKey = match.pair.key
                            confirmKeepId = keepId
                        },
                        onKeepBoth = onKeepBoth
                    )
                    else -> groupItems(groups = uiState.groups, onOpenBookmark = onOpenBookmark)
                }
            }
        }
    }

    if (confirmMatch != null && confirmKeep != null) {
        val removed = if (confirmKeep.id == confirmMatch.first.id) confirmMatch.second else confirmMatch.first
        AlertDialog(
            onDismissRequest = { confirmPairKey = null },
            title = { Text("1つにまとめますか?") },
            text = {
                Text(
                    "「${removed.title.shortened()}」を削除して、「${confirmKeep.title.shortened()}」にまとめます。\n\n" +
                        "タグ・お気に入り・読んだ記録・オフラインコピーは、残す方に足されます。まとめたあとも「元に戻す」で戻せます。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPairKey = null
                    onMerge(confirmMatch, confirmKeep.id)
                }) { Text("まとめる") }
            },
            dismissButton = { TextButton(onClick = { confirmPairKey = null }) { Text("やめる") } }
        )
    }
}

@Composable
private fun SensitivitySection(sensitivity: DuplicateSensitivity, onSensitivity: (DuplicateSensitivity) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("似ているかの見分け方", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DuplicateSensitivity.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = sensitivity == entry,
                    onClick = { onSensitivity(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, DuplicateSensitivity.entries.size)
                ) { Text(entry.label()) }
            }
        }
        Text(
            text = "自動では何も消しません。1つずつ確かめて、まとめるか両方残すかを選べます。" +
                "多すぎるときは「厳しめ」、見つからないときは「ゆるめ」にしてみてください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.duplicateItems(
    matches: List<DuplicateMatch>,
    onOpenBookmark: (Long) -> Unit,
    onKeep: (DuplicateMatch, keepId: Long) -> Unit,
    onKeepBoth: (DuplicateMatch) -> Unit
) {
    if (matches.isEmpty()) {
        item(key = "empty-duplicates") {
            EmptyState(
                icon = Icons.Outlined.ContentCopy,
                title = "重複は見つかりませんでした",
                description = "同じページや、内容がほぼ同じブックマークがあると、ここに出ます。",
                modifier = Modifier.height(280.dp)
            )
        }
        return
    }
    items(count = matches.size, key = { "dup-${matches[it].pair.key}" }) { index ->
        val match = matches[index]
        DuplicateCard(
            match = match,
            onOpenBookmark = onOpenBookmark,
            onKeep = { onKeep(match, it) },
            onKeepBoth = { onKeepBoth(match) }
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.groupItems(
    groups: List<RelatedGroup>,
    onOpenBookmark: (Long) -> Unit
) {
    if (groups.isEmpty()) {
        item(key = "empty-groups") {
            EmptyState(
                icon = Icons.Outlined.Hub,
                title = "関連のまとまりはありません",
                description = "近い内容のブックマークが3件以上あると、まとまりとして出ます。",
                modifier = Modifier.height(280.dp)
            )
        }
        return
    }
    item(key = "groups-hint") {
        Text(
            text = "内容が近いブックマークを、自動でまとめて並べています。タップで開けます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
    items(count = groups.size, key = { "group-${groups[it].bookmarks.first().id}" }) { index ->
        GroupCard(group = groups[index], onOpenBookmark = onOpenBookmark)
    }
}

@Composable
private fun DuplicateCard(
    match: DuplicateMatch,
    onOpenBookmark: (Long) -> Unit,
    onKeep: (keepId: Long) -> Unit,
    onKeepBoth: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                text = match.reasonText(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(4.dp))
            listOf(match.first, match.second).forEach { bookmark ->
                DuplicateSide(
                    bookmark = bookmark,
                    suggested = bookmark.id == match.suggestedKeepId,
                    onOpen = { onOpenBookmark(bookmark.id) },
                    onKeep = { onKeep(bookmark.id) }
                )
            }
            TextButton(onClick = onKeepBoth, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("両方残す")
            }
        }
    }
}

@Composable
private fun DuplicateSide(bookmark: Bookmark, suggested: Boolean, onOpen: () -> Unit, onKeep: () -> Unit) {
    val source = bookmark.displaySource()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "開く", onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            BookmarkThumbnail(bookmark = bookmark, domain = source, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(bookmark.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(bookmark.metaLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val label = if (suggested) "こちらを残す(おすすめ)" else "こちらを残す"
        Row(Modifier.padding(horizontal = 16.dp)) {
            if (suggested) {
                FilledTonalButton(onClick = onKeep) { Text(label) }
            } else {
                OutlinedButton(onClick = onKeep) { Text(label) }
            }
        }
    }
}

@Composable
private fun GroupCard(group: RelatedGroup, onOpenBookmark: (Long) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                text = "${group.label.shortened(30)} · ${group.bookmarks.size}件",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            group.bookmarks.forEach { bookmark ->
                val source = bookmark.displaySource()
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "開く") { onOpenBookmark(bookmark.id) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BookmarkThumbnail(bookmark = bookmark, domain = source, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bookmark.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

internal fun DuplicateSensitivity.label(): String = when (this) {
    DuplicateSensitivity.STRICT -> "厳しめ"
    DuplicateSensitivity.STANDARD -> "標準"
    DuplicateSensitivity.LOOSE -> "ゆるめ"
}

/** 候補の理由。内容が近いときは距離も添える(しきい値を調べるときの手がかり) */
internal fun DuplicateMatch.reasonText(): String = when (reason) {
    DuplicateReason.SAME_URL -> "同じページ(URL が同じ)"
    DuplicateReason.SIMILAR_CONTENT -> "内容がよく似ています" + (distance?.let { "(距離 ${"%.3f".format(it)})" } ?: "")
}

/** 「2026/10/1 に保存 · タグ 3 · 要約あり」のような補足 */
internal fun Bookmark.metaLine(zone: ZoneId = ZoneId.systemDefault()): String = buildList {
    add("${DATE_FORMAT.format(Instant.ofEpochMilli(createdAt).atZone(zone))} に保存")
    if (tags.isNotEmpty()) add("タグ ${tags.size}")
    if (!summary.isNullOrBlank()) add("要約あり")
    if (hasSnapshot) add("オフラインコピーあり")
    if (isFavorite) add("お気に入り")
}.joinToString(" · ")

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d")

private fun String.shortened(max: Int = 24): String = if (length <= max) this else take(max) + "…"
