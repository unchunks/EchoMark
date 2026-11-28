package com.unchunks.echomark.ui.collections

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.export.BookmarkExporter
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.ui.components.CompactBookmarkRow
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.common.shareBookmark
import com.unchunks.echomark.ui.selection.BulkActionBar
import com.unchunks.echomark.ui.selection.BulkActionCallbacks
import com.unchunks.echomark.ui.selection.BulkBookmarkActions
import com.unchunks.echomark.ui.selection.SelectionMark
import com.unchunks.echomark.ui.selection.SelectionTopBar
import com.unchunks.echomark.ui.selection.showBulkMessage
import com.unchunks.echomark.ui.shortcut.PinnedShortcuts
import kotlinx.coroutines.launch
import com.unchunks.echomark.ui.design.EchoMarkTopAppBar

/**
 * コレクションの中身(#91)。入れたブックマークを入れた順に並べ、外す・開く・ファイルに書き出すができる。
 * 書き出しは、Markdown と、ブラウザのブックマーク(HTML)から選ぶ。
 */
@Composable
fun CollectionDetailScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: CollectionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val title = uiState.collection?.name.orEmpty()

    // 書き出し先を選ぶ画面は MIME タイプごとに作る(形式ごとに用意)
    val markdownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.MARKDOWN.mimeType)) { uri ->
        if (uri != null) viewModel.export(ExportFormat.MARKDOWN, uri.toString())
    }
    val htmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.BROWSER_HTML.mimeType)) { uri ->
        if (uri != null) viewModel.export(ExportFormat.BROWSER_HTML, uri.toString())
    }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is CollectionDetailMessage.Removed -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "「${message.bookmark.title.take(20)}」をコレクションから外しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.add(message.bookmark)
                    }
                    is CollectionDetailMessage.Exported -> snackbarHostState.showSnackbar(exportedText(message.result.exported, message.result.skipped))
                    is CollectionDetailMessage.Failed -> snackbarHostState.showSnackbar(message.message)
                    is CollectionDetailMessage.Bulk -> snackbarHostState.showBulkMessage(
                        message.message,
                        onUndoArchive = viewModel::undoBulkArchive,
                        onUndoDelete = viewModel::undoBulkDelete,
                        onUndoRemoveFromCollection = viewModel::undoBulkRemoveFromCollection
                    )
                }
            }
        }
    }

    CollectionDetailContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenBookmark = onOpenBookmark,
        onRemove = viewModel::remove,
        onEnterSelection = viewModel::onEnterSelection,
        onToggleSelection = viewModel::onToggleSelection,
        onSelectAll = viewModel::onSelectAll,
        onClearSelection = viewModel::onClearSelection,
        onExitSelection = viewModel::onExitSelection,
        bulk = BulkActionCallbacks(
            onSetFavorite = viewModel::bulkSetFavorite,
            onSetRead = viewModel::bulkSetRead,
            onSetArchived = viewModel::bulkSetArchived,
            onDelete = viewModel::bulkDelete,
            onAddTag = viewModel::bulkAddTag,
            onRemoveTag = viewModel::bulkRemoveTag,
            onSetCategory = viewModel::bulkSetCategory,
            onAddToCollection = viewModel::bulkAddToCollection,
            onCreateCollectionAndAdd = viewModel::bulkCreateCollectionAndAdd,
            onRemoveFromCollection = viewModel::bulkRemoveFromCollection,
            onReprocess = viewModel::bulkReprocess,
            onExport = viewModel::bulkExport,
            onShare = { bookmark -> shareBookmark(context, bookmark) }
        ),
        onPinShortcut = {
            uiState.collection?.let { PinnedShortcuts.request(context, PinnedShortcuts.forCollection(it.id, it.name)) }
        },
        onExport = { format ->
            val fileName = BookmarkExporter.fileName(title, format)
            when (format) {
                ExportFormat.MARKDOWN -> markdownLauncher.launch(fileName)
                ExportFormat.BROWSER_HTML -> htmlLauncher.launch(fileName)
            }
        }
    )
}

/** 書き出し後の知らせ。形式に入れられず飛ばした分があれば添える。 */
internal fun exportedText(exported: Int, skipped: Int): String =
    BulkBookmarkActions.exportedText(exported, skipped)

/** コレクションの中身の本体(状態を受け取って描くだけ)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailContent(
    uiState: CollectionDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onExport: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    onPinShortcut: () -> Unit = {},
    /** 複数選択のモードに入る(長押しならそのブックマークの ID、メニューの「選択」なら null) */
    onEnterSelection: (Long?) -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onExitSelection: () -> Unit = {},
    bulk: BulkActionCallbacks = BulkActionCallbacks()
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var exportMenuOpen by remember { mutableStateOf(false) }
    val collection = uiState.collection

    // 戻るでまず選択をやめる
    BackHandler(enabled = uiState.isSelecting, onBack = onExitSelection)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (uiState.isSelecting) {
                SelectionTopBar(
                    count = uiState.selectedIds.size,
                    total = uiState.bookmarks.size,
                    onClose = onExitSelection,
                    onSelectAll = onSelectAll,
                    onClear = onClearSelection,
                    scrollBehavior = scrollBehavior
                )
            } else EchoMarkTopAppBar(
                title = { Text(collection?.name ?: "コレクション", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (collection != null) {
                        IconButton(onClick = { onEnterSelection(null) }, enabled = uiState.bookmarks.isNotEmpty()) {
                            Icon(Icons.Outlined.Checklist, contentDescription = "選択")
                        }
                        IconButton(onClick = onPinShortcut) {
                            Icon(Icons.Outlined.AddToHomeScreen, contentDescription = "ホーム画面にショートカットを置く")
                        }
                        Box {
                            IconButton(onClick = { exportMenuOpen = true }, enabled = uiState.bookmarks.isNotEmpty()) {
                                Icon(Icons.Outlined.FileUpload, contentDescription = "ファイルに書き出す")
                            }
                            DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                                ExportFormat.entries.forEach { format ->
                                    DropdownMenuItem(
                                        text = {
                                            androidx.compose.foundation.layout.Column {
                                                Text(format.label)
                                                Text(
                                                    format.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            exportMenuOpen = false
                                            onExport(format)
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            if (uiState.isSelecting) {
                BulkActionBar(
                    selected = uiState.selectedBookmarks,
                    tagSuggestions = uiState.bookmarks.flatMap { it.tags }.distinct(),
                    categories = uiState.categoryChoices,
                    collections = uiState.collections,
                    callbacks = bulk
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage)
                collection == null -> EmptyState(
                    icon = Icons.Outlined.Folder,
                    title = "コレクションが見つかりません",
                    description = "削除された可能性があります。",
                    actionLabel = "戻る",
                    onAction = onBack
                )
                uiState.bookmarks.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Folder,
                    title = "まだ何も入っていません",
                    description = "ブックマークの詳細画面の「⋮」→「コレクションに追加」で、このコレクションに入れられます。"
                )
                else -> ReadableLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    collection.description?.takeIf { it.isNotBlank() }?.let { description ->
                        item(key = "description") {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(uiState.bookmarks.size, key = { uiState.bookmarks[it].id }) { index ->
                        val bookmark = uiState.bookmarks[index]
                        val selecting = uiState.isSelecting
                        val checked = bookmark.id in uiState.selectedIds
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).animateItem(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 複数選択の間は、タップも長押しも選ぶ・外す。そうでなければ、タップで開き、長押しで選択を始める
                            Box(Modifier.weight(1f)) {
                                CompactBookmarkRow(
                                    bookmark = bookmark,
                                    onClick = if (selecting) {
                                        { onToggleSelection(bookmark.id) }
                                    } else {
                                        { onOpenBookmark(bookmark.id) }
                                    },
                                    nowMillis = nowMillis,
                                    onLongClick = if (selecting) {
                                        { onToggleSelection(bookmark.id) }
                                    } else {
                                        { onEnterSelection(bookmark.id) }
                                    },
                                    onLongClickLabel = if (selecting) "選択を切り替える" else "選択を始める",
                                    selected = checked,
                                    modifier = if (selecting) {
                                        Modifier.semantics { stateDescription = if (checked) "選択中" else "選択していません" }
                                    } else {
                                        Modifier
                                    }
                                )
                                if (selecting) SelectionMark(checked, Modifier.align(Alignment.TopStart).padding(8.dp))
                            }
                            if (!selecting) {
                                IconButton(onClick = { onRemove(bookmark) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "「${bookmark.title.take(20)}」をコレクションから外す")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
