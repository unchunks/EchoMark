package com.unchunks.echomark.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.common.formatRelativeTime
import com.unchunks.echomark.ui.common.openAttachment
import com.unchunks.echomark.ui.common.openUrl
import com.unchunks.echomark.ui.attachment.rememberAttachmentFile
import com.unchunks.echomark.ui.common.displayName
import com.unchunks.echomark.ui.common.shareBookmark
import com.unchunks.echomark.ui.components.AiStatusBadge
import com.unchunks.echomark.ui.components.AiTagIcon
import com.unchunks.echomark.ui.components.CompactBookmarkCard
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.statusDescription
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ブックマークの詳細。ViewModel をつなぐだけの薄いラッパーで、描画は [BookmarkDetailContent] が行う。
 *
 * @param onAskAi このブックマークについて AI に質問する(チャットの新規会話を開く)
 * @param onOpenAiSettings AI の準備ができていないときに、AI 設定を開く
 */
@Composable
fun BookmarkDetailScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onAskAi: (Long) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    viewModel: BookmarkDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is BookmarkDetailMessage.ArchiveChanged -> {
                        val result = snackbarHostState.showSnackbar(
                            message = if (message.archived) "アーカイブしました" else "アーカイブから戻しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.setArchived(!message.archived, notify = false)
                        }
                    }
                    BookmarkDetailMessage.Edited -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "保存しました",
                            actionLabel = "AIで要約し直す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.reprocess()
                    }
                    is BookmarkDetailMessage.TagRemoved -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "タグ「${message.name}」を外しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.addTag(message.name)
                    }
                    BookmarkDetailMessage.ReprocessStarted -> snackbarHostState.showSnackbar("AI で処理し直しています…")
                    is BookmarkDetailMessage.Failed -> snackbarHostState.showSnackbar(message.message)
                }
            }
        }
    }

    BookmarkDetailContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        callbacks = BookmarkDetailCallbacks(
            onBack = onBack,
            onOpenBookmark = onOpenBookmark,
            onOpenInBrowser = { url -> openUrl(context, url) },
            onOpenFile = { openAttachment(context, it) },
            onAskAi = onAskAi,
            onOpenAiSettings = onOpenAiSettings,
            onShare = { shareBookmark(context, it) },
            onToggleFavorite = viewModel::toggleFavorite,
            onSetArchived = { viewModel.setArchived(it) },
            onReprocess = viewModel::reprocess,
            onDelete = { viewModel.delete(onDeleted = onBack) },
            onSaveEdit = viewModel::saveEdit,
            onAddTag = viewModel::addTag,
            onRemoveTag = viewModel::removeTag
        )
    )
}

/** 詳細画面の操作。状態を持たない [BookmarkDetailContent] に渡す */
class BookmarkDetailCallbacks(
    val onBack: () -> Unit = {},
    val onOpenBookmark: (Long) -> Unit = {},
    val onOpenInBrowser: (String) -> Unit = {},
    /** 保存したファイルをほかのアプリで開く */
    val onOpenFile: (Bookmark) -> Unit = {},
    val onAskAi: (Long) -> Unit = {},
    val onOpenAiSettings: () -> Unit = {},
    val onShare: (Bookmark) -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onSetArchived: (Boolean) -> Unit = {},
    val onReprocess: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onSaveEdit: (title: String, content: String) -> Unit = { _, _ -> },
    val onAddTag: (String) -> Unit = {},
    val onRemoveTag: (String) -> Unit = {}
)

/**
 * 詳細画面の本体(状態を受け取って描くだけ)。
 * 上から: ファイルのプレビュー(または OG 画像) → 出どころと保存日時 → タイトル → ファイルの情報 → 開く/質問ボタン →
 * AI 要約(または AI の状態) → タグ → 本文/メモ(ファイルから読み取った文字を含む) → 関連。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkDetailContent(
    uiState: BookmarkDetailUiState,
    snackbarHostState: SnackbarHostState,
    callbacks: BookmarkDetailCallbacks,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis()
) {
    val bookmark = uiState.bookmark
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showEditSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            DetailTopBar(
                bookmark = bookmark,
                scrollBehavior = scrollBehavior,
                callbacks = callbacks,
                onEdit = { showEditSheet = true },
                onDelete = { showDeleteDialog = true }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingState(Modifier.padding(innerPadding), message = "読み込み中…")
            bookmark == null -> EmptyState(
                icon = Icons.Outlined.BookmarkRemove,
                title = "ブックマークが見つかりません",
                description = "削除された可能性があります。",
                actionLabel = "戻る",
                onAction = callbacks.onBack,
                modifier = Modifier.padding(innerPadding)
            )
            else -> DetailBody(
                bookmark = bookmark,
                uiState = uiState,
                callbacks = callbacks,
                nowMillis = nowMillis,
                contentPadding = innerPadding
            )
        }
    }

    if (showDeleteDialog && bookmark != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("削除しますか?") },
            text = { Text("「${bookmark.title}」を削除します。一覧に戻ったあと、しばらくは元に戻せます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        callbacks.onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            }
        )
    }

    if (showEditSheet && bookmark != null) {
        EditBookmarkSheet(
            bookmark = bookmark,
            onDismiss = { showEditSheet = false },
            onSave = { title, content ->
                showEditSheet = false
                callbacks.onSaveEdit(title, content)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    bookmark: Bookmark?,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    callbacks: BookmarkDetailCallbacks,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = callbacks.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
            }
        },
        scrollBehavior = scrollBehavior,
        actions = {
            if (bookmark == null) return@TopAppBar
            IconButton(onClick = callbacks.onToggleFavorite) {
                Icon(
                    imageVector = if (bookmark.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (bookmark.isFavorite) "お気に入りから外す" else "お気に入りに追加",
                    tint = if (bookmark.isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { callbacks.onShare(bookmark) }) {
                Icon(Icons.Outlined.Share, contentDescription = "共有")
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "その他のメニュー")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("編集") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (bookmark.isArchived) "アーカイブから戻す" else "アーカイブ") },
                    leadingIcon = {
                        Icon(
                            if (bookmark.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuOpen = false
                        callbacks.onSetArchived(!bookmark.isArchived)
                    }
                )
                DropdownMenuItem(
                    text = { Text("AIで処理し直す") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        callbacks.onReprocess()
                    }
                )
                DropdownMenuItem(
                    text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    )
}

@Composable
private fun DetailBody(
    bookmark: Bookmark,
    uiState: BookmarkDetailUiState,
    callbacks: BookmarkDetailCallbacks,
    nowMillis: Long,
    contentPadding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // キーボードの分だけ表示範囲を縮め、タグの入力欄がキーボードに隠れないようにする
            // (スクロールより前に付ける。入力中の欄は表示範囲に収まるよう自動でスクロールされる)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(bottom = 24.dp)
    ) {
        val file = rememberAttachmentFile(bookmark)
        val fileType = bookmark.attachmentType()
        val imageUrl = bookmark.imageUrl
        if (file != null && fileType != null && fileType != BookmarkType.TEXT) {
            AttachmentPreview(file, fileType, onOpenFile = { callbacks.onOpenFile(bookmark) })
            Spacer(Modifier.height(16.dp))
        } else if (!imageUrl.isNullOrBlank()) {
            // 装飾扱い(内容はタイトルで伝わる)。OG 画像の標準比率 1.91:1 で切り出す
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.91f)
                    .clip(MaterialTheme.shapes.large)
            )
            Spacer(Modifier.height(16.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            SourceAndDate(bookmark, nowMillis)
            Spacer(Modifier.height(6.dp))
            Text(
                text = bookmark.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            if (bookmark.filePath != null) {
                Spacer(Modifier.height(12.dp))
                FileInfoCard(bookmark, file, onOpenFile = { callbacks.onOpenFile(bookmark) })
            }
            Spacer(Modifier.height(16.dp))
            PrimaryActions(bookmark, hasFile = file != null, callbacks = callbacks)
            Spacer(Modifier.height(20.dp))
            AiSection(bookmark, callbacks)
            Spacer(Modifier.height(24.dp))
            TagSection(
                tags = bookmark.tags,
                aiTags = bookmark.aiTags,
                allTagNames = uiState.allTagNames,
                onAddTag = callbacks.onAddTag,
                onRemoveTag = callbacks.onRemoveTag
            )
            val content = bookmark.content
            if (!content.isNullOrBlank()) {
                Spacer(Modifier.height(24.dp))
                ContentSection(label = contentLabel(bookmark), text = content)
            }
        }

        if (uiState.related.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            DetailSectionTitle("関連するブックマーク", Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.related, key = { it.id }) { related ->
                    CompactBookmarkCard(bookmark = related, onClick = { callbacks.onOpenBookmark(related.id) })
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "保存: ${formatDateTime(bookmark.createdAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

@Composable
private fun SourceAndDate(bookmark: Bookmark, nowMillis: Long) {
    val domain = bookmark.contentUri?.let { extractDomain(it) }
    val source = listOfNotNull(bookmark.siteName?.takeIf { it.isNotBlank() }, domain)
        .distinct()
        .joinToString(" · ")
        .ifEmpty { bookmark.attachmentType()?.displayName() ?: bookmark.type.displayName() }
    Text(
        text = source,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${formatRelativeTime(bookmark.createdAt, nowMillis)}に保存",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (bookmark.isArchived) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    "アーカイブ済み",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun PrimaryActions(bookmark: Bookmark, hasFile: Boolean, callbacks: BookmarkDetailCallbacks) {
    val url = bookmark.contentUri
    AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
        if (url != null) {
            Button(onClick = { callbacks.onOpenInBrowser(url) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("ブラウザで開く", textAlign = TextAlign.Center)
            }
        } else if (hasFile) {
            Button(onClick = { callbacks.onOpenFile(bookmark) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("ファイルを開く", textAlign = TextAlign.Center)
            }
        }
        FilledTonalButton(onClick = { callbacks.onAskAi(bookmark.id) }) {
            Icon(Icons.Outlined.QuestionAnswer, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("AIに質問", textAlign = TextAlign.Center)
        }
    }
}

/**
 * ボタンを同じ幅で横に並べる。文字サイズを大きくしているなどで、どれかの文字が等分の幅に収まらないときは、
 * 文字を切らずに幅いっぱいのボタンを縦に積む。
 */
@Composable
private fun AdaptiveButtonRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val spacingPx = spacing.roundToPx()
        val width = constraints.maxWidth
        val count = measurables.size
        val cellWidth = (width - spacingPx * (count - 1)) / count
        val fitsInRow = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= cellWidth }
        if (fitsInRow) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(cellWidth)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(index * (cellWidth + spacingPx), (height - placeable.height) / 2)
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + spacingPx * (count - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + spacingPx
                }
            }
        }
    }
}

/** AI 要約(完了時)か、AI の状態と次の行動(処理中・失敗・準備待ち) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSection(bookmark: Bookmark, callbacks: BookmarkDetailCallbacks) {
    val summary = bookmark.summary
    if (bookmark.aiStatus == AiStatus.DONE && !summary.isNullOrBlank()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "AI要約",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() }
                    )
                    bookmark.category?.takeIf { it.isNotBlank() }?.let { category ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Text(
                                category,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(summary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }
    if (bookmark.aiStatus == AiStatus.DONE) return

    val isFailed = bookmark.aiStatus == AiStatus.FAILED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isFailed) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            AiStatusBadge(bookmark.aiStatus)
            bookmark.aiStatus.statusDescription()?.let { description ->
                Spacer(Modifier.height(10.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            when (bookmark.aiStatus) {
                AiStatus.PENDING, AiStatus.PROCESSING -> {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                AiStatus.FAILED -> {
                    Spacer(Modifier.height(12.dp))
                    // 文字が大きいときは折り返して次の行に並べる(ボタンの文字を切らない)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = callbacks.onReprocess) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("再試行")
                        }
                        OutlinedButton(onClick = callbacks.onOpenAiSettings) { Text("AI設定を開く") }
                    }
                }
                AiStatus.WAITING_MODEL -> {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = callbacks.onOpenAiSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("AI設定を開く")
                        }
                        OutlinedButton(onClick = callbacks.onReprocess) { Text("再試行") }
                    }
                }
                AiStatus.DONE -> Unit
            }
        }
    }
}

/**
 * タグ: × で外し(取り消せる)、入力欄で追加(既存タグを候補に出す)。
 * AI が付けたタグ([aiTags])には印を付ける。AI のタグは再処理で付け直されるが、自分で付けたタグはそのまま残る
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSection(
    tags: List<String>,
    aiTags: Set<String>,
    allTagNames: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(input, allTagNames, tags) { tagSuggestions(input, allTagNames, tags) }
    val submit = {
        if (input.isNotBlank()) {
            onAddTag(input)
            input = ""
        }
    }

    DetailSectionTitle("タグ")
    Spacer(Modifier.height(8.dp))
    if (tags.isEmpty()) {
        Text(
            "タグはまだありません。下の欄から追加できます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                RemovableTagChip(name = tag, isAi = tag in aiTags, onRemove = { onRemoveTag(tag) })
            }
        }
        if (tags.any { it in aiTags }) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AiTagIcon(size = 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    "AIが付けたタグ(再処理で付け直されます)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("タグを追加") },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = submit, enabled = input.isNotBlank()) {
                Icon(Icons.Outlined.Add, contentDescription = "タグを追加")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() })
    )
    if (suggestions.isNotEmpty()) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { name ->
                SuggestionChip(
                    onClick = {
                        onAddTag(name)
                        input = ""
                    },
                    label = { Text("#$name", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

/**
 * 付いているタグ。外せるのは × だけにする(チップ本体を押しても外れない。誤って触れて消えるのを防ぐ)。
 * × は見た目 32dp だが、タッチは周囲を含めて 48dp まで受け付ける(Compose の最小タッチ領域)。
 * [isAi] なら AI が付けたタグとして先頭に ✨ を付ける。
 */
@Composable
private fun RemovableTagChip(name: String, isAi: Boolean, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .padding(start = if (isAi) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isAi) {
                AiTagIcon(size = 16.dp, modifier = Modifier.padding(end = 4.dp), describe = true)
            }
            Text(
                "#$name",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 長いタグ名は省略して、× が画面の外に出ないようにする
                modifier = Modifier.widthIn(max = TAG_CHIP_LABEL_MAX_WIDTH)
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "タグ「$name」を外す" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 入力に部分一致する既存タグ(付いていないもの)を、前方一致を優先して最大3件 */
internal fun tagSuggestions(input: String, allTagNames: List<String>, current: List<String>): List<String> {
    val query = input.trim().removePrefix("#").trim()
    if (query.isEmpty()) return emptyList()
    return allTagNames
        .filter { it !in current && it.contains(query, ignoreCase = true) && it != query }
        .sortedWith(compareByDescending<String> { it.startsWith(query, ignoreCase = true) }.thenBy { it.length })
        .take(3)
}

/** 本文・メモ。長いときは6行で畳み、「もっと見る」で開く */
@Composable
private fun ContentSection(label: String, text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    DetailSectionTitle(label)
    Spacer(Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        modifier = Modifier.animateContentSize()
    )
    if (overflowing || expanded) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
            Text(if (expanded) "折りたたむ" else "もっと見る")
        }
    }
}

@Composable
private fun DetailSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() }
    )
}

private const val COLLAPSED_LINES = 6

private val TAG_CHIP_LABEL_MAX_WIDTH = 220.dp

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d H:mm")

private fun formatDateTime(millis: Long): String =
    DATE_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
