package com.unchunks.echomark.ui.bookmark

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.formatRelativeTime
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.ReadingTime
import com.unchunks.echomark.domain.bookmark.model.Recommendation
import com.unchunks.echomark.domain.bookmark.model.RecommendationReason
import com.unchunks.echomark.domain.bookmark.model.SearchSuggestion
import com.unchunks.echomark.domain.bookmark.model.SmartFolder
import com.unchunks.echomark.domain.bookmark.model.SmartFolderResult
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.common.displayName
import com.unchunks.echomark.ui.common.shareBookmark
import com.unchunks.echomark.ui.common.shortTitle
import com.unchunks.echomark.ui.components.BookmarkCard
import com.unchunks.echomark.ui.components.BookmarkGridTile
import com.unchunks.echomark.ui.components.CompactBookmarkRow
import com.unchunks.echomark.ui.components.CompactBookmarkCard
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems

/**
 * ブックマーク一覧(ホーム)。ViewModel をつなぐだけの薄いラッパーで、描画は [BookmarkListContent] が行う。
 * Snackbar(保存・削除・アーカイブの結果と取り消し)、追加シート、長押しの操作メニューをここで扱う。
 *
 * @param selectedBookmarkId 2 画面表示で右側に詳細を出しているブックマーク(カードを強調する)
 * @param onBookmarkRemoved 一覧での操作(削除・アーカイブ/戻す)でブックマークが今の一覧から外れたとき。
 *   2 画面表示で、右側に出しているものなら右側を空にするために使う
 */
@Composable
fun BookmarkListScreen(
    onOpenBookmark: (Long) -> Unit = {},
    onOpenTagManagement: () -> Unit = {},
    selectedBookmarkId: Long? = null,
    onBookmarkRemoved: (Long) -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onOpenDuplicates: () -> Unit = {},
    viewModel: BookmarkViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<Bookmark?>(null) }
    val currentOnOpenBookmark by rememberUpdatedState(onOpenBookmark)

    // ショートカット・ウィジェットの「URL を追加」から起動したら追加シートを開く
    LaunchedEffect(viewModel) {
        viewModel.addSheetRequests.collect { showAddSheet = true }
    }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            // 新しい知らせを優先する(前の Snackbar は閉じる)。表示待ちで次の知らせを止めないよう別コルーチンで出す
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is BookmarkListMessage.Deleted -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "「${message.bookmark.shortTitle()}」を削除しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreBookmark(message.bookmark)
                    }
                    is BookmarkListMessage.ArchiveChanged -> {
                        val result = snackbarHostState.showSnackbar(
                            message = if (message.archived) "アーカイブしました" else "アーカイブから戻しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoArchive(message.bookmark)
                    }
                    is BookmarkListMessage.Saved -> {
                        val result = snackbarHostState.showSnackbar(
                            // AI の準備ができていなければ処理は始まらないため、「要約中」とは言わない
                            // (AI の状態は一覧のカードに出る)
                            message = if (message.isDuplicate) "既に保存済みです" else "保存しました",
                            actionLabel = "開く",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) currentOnOpenBookmark(message.id)
                    }
                    is BookmarkListMessage.SaveFailed -> snackbarHostState.showSnackbar(message.message)
                    is BookmarkListMessage.SmartFolderSaved -> snackbarHostState.showSnackbar("スマートフォルダ「${message.name}」を保存しました")
                    is BookmarkListMessage.SmartFolderUpdated -> snackbarHostState.showSnackbar("スマートフォルダ「${message.name}」を今の条件に更新しました")
                    is BookmarkListMessage.SmartFolderDeleted ->
                        snackbarHostState.showSnackbar("スマートフォルダ「${message.name}」を削除しました(中のブックマークは消えていません)")
                    is BookmarkListMessage.SmartFolderFailed -> snackbarHostState.showSnackbar(message.message)
                    // 結果の知らせ(FilesSaved / SaveFailed)が届くと閉じる
                    is BookmarkListMessage.SavingFiles -> snackbarHostState.showSnackbar(
                        message = if (message.count > 1) "${message.count}件のファイルを保存しています…" else "ファイルを保存しています…",
                        duration = SnackbarDuration.Indefinite
                    )
                    is BookmarkListMessage.FilesSaved -> {
                        val text = buildString {
                            append(if (message.savedCount > 1) "${message.savedCount}件のファイルを保存しました" else "保存しました")
                            if (message.failedCount > 0) append("(${message.failedCount}件は保存できませんでした)")
                            append("。中身を読み取って要約します")
                        }
                        val result = snackbarHostState.showSnackbar(
                            message = text,
                            actionLabel = "開く".takeIf { message.savedCount == 1 },
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) currentOnOpenBookmark(message.firstId)
                    }
                }
            }
        }
    }

    val setArchived = { bookmark: Bookmark, archived: Boolean ->
        viewModel.setArchived(bookmark, archived)
        onBookmarkRemoved(bookmark.id)
    }
    val delete = { bookmark: Bookmark ->
        viewModel.deleteBookmark(bookmark)
        onBookmarkRemoved(bookmark.id)
    }

    BookmarkListContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        callbacks = BookmarkListCallbacks(
            onOpenBookmark = { id ->
                viewModel.onOpenBookmark(id)
                onOpenBookmark(id)
            },
            onSearchActiveChange = viewModel::onSearchActiveChange,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onSearchSubmit = viewModel::onSearchSubmitted,
            onSuggestionSelect = viewModel::onSuggestionSelected,
            onHistoryRemove = viewModel::onHistoryRemoved,
            onHistoryClear = viewModel::onHistoryCleared,
            onTypeToggle = viewModel::onTypeToggled,
            onPeriodToggle = viewModel::onPeriodToggled,
            onReadStateToggle = viewModel::onReadStateToggled,
            onToggleRead = viewModel::toggleRead,
            onClearTypeAndPeriod = viewModel::onClearTypeAndPeriod,
            onFilterChange = viewModel::onFilterChange,
            onSortOrderChange = viewModel::onSortOrderChange,
            onDisplayChange = viewModel::onDisplayChange,
            onSmartFolderSelect = viewModel::onSmartFolderSelected,
            onSaveSmartFolder = viewModel::onSaveSmartFolder,
            onRenameSmartFolder = viewModel::onRenameSmartFolder,
            // 並び順も保存していたフォルダは、並び順も今のものに更新する
            onUpdateSmartFolder = { folder -> viewModel.onOverwriteSmartFolder(folder, includeSort = folder.sortOrder != null) },
            onDeleteSmartFolder = viewModel::onDeleteSmartFolder,
            onTagToggle = viewModel::onTagToggled,
            onCategoryToggle = viewModel::onCategoryToggled,
            onTagMatchChange = viewModel::onTagMatchChange,
            onClearFilters = viewModel::onClearFilters,
            onToggleFavorite = viewModel::toggleFavorite,
            onSetArchived = setArchived,
            onDelete = delete,
            onLongPress = { actionTarget = it },
            onAddClick = { showAddSheet = true },
            onOpenTagManagement = onOpenTagManagement,
            onOpenDiscover = onOpenDiscover,
            onOpenDuplicates = onOpenDuplicates,
            onRetry = viewModel::retry
        ),
        selectedBookmarkId = selectedBookmarkId
    )

    if (showAddSheet) {
        AddBookmarkSheet(
            onDismiss = { showAddSheet = false },
            onSave = { input ->
                viewModel.save(input)
                showAddSheet = false
            }
        )
    }

    actionTarget?.let { target ->
        BookmarkActionSheet(
            bookmark = target,
            onDismiss = { actionTarget = null },
            onToggleFavorite = { viewModel.toggleFavorite(target) },
            onToggleRead = { viewModel.toggleRead(target) },
            onToggleArchive = { setArchived(target, !target.isArchived) },
            onShare = { shareBookmark(context, target) },
            onDelete = { delete(target) }
        )
    }
}

/** 一覧画面で出しているスマートフォルダ関係のダイアログ・シート */
private sealed interface SmartFolderDialog {
    /** 今の条件を保存する名前の入力 */
    data object Save : SmartFolderDialog

    /** フォルダの操作(名前を変える・削除)を選ぶシート */
    data class Manage(val folder: SmartFolder) : SmartFolderDialog

    data class Rename(val folder: SmartFolder) : SmartFolderDialog

    data class ConfirmDelete(val folder: SmartFolder) : SmartFolderDialog
}

/** 一覧画面の操作。状態を持たない [BookmarkListContent] に渡す */
class BookmarkListCallbacks(
    val onOpenBookmark: (Long) -> Unit = {},
    val onSearchActiveChange: (Boolean) -> Unit = {},
    val onSearchQueryChange: (String) -> Unit = {},
    /** 検索欄で検索の操作(キーボードの検索ボタン)をした */
    val onSearchSubmit: () -> Unit = {},
    /** 検索欄の下の候補を選ぶ */
    val onSuggestionSelect: (SearchSuggestion) -> Unit = {},
    val onHistoryRemove: (String) -> Unit = {},
    val onHistoryClear: () -> Unit = {},
    /** 種類(リンク・メモ・画像など)を選ぶ・外す */
    val onTypeToggle: (BookmarkType) -> Unit = {},
    /** 保存した日からの期間を選ぶ・外す */
    val onPeriodToggle: (CreatedPeriod) -> Unit = {},
    /** 読んだかどうか(未読・既読)で絞る・外す */
    val onReadStateToggle: (ReadState) -> Unit = {},
    /** 読んだことにする / 未読に戻す */
    val onToggleRead: (Bookmark) -> Unit = {},
    /** 種類・期間・読んだかを外す */
    val onClearTypeAndPeriod: () -> Unit = {},
    val onFilterChange: (BookmarkFilter) -> Unit = {},
    val onSortOrderChange: (BookmarkSortOrder) -> Unit = {},
    /** 一覧の見せ方(カード・コンパクト・グリッド)を変える */
    val onDisplayChange: (BookmarkListDisplay) -> Unit = {},
    /** スマートフォルダを開く(開いているフォルダをもう一度選ぶと閉じる) */
    val onSmartFolderSelect: (SmartFolder) -> Unit = {},
    /** 今の条件をスマートフォルダとして保存する(名前・並び順も保存するか・結果の受け取り) */
    val onSaveSmartFolder: (String, Boolean, (SmartFolderResult) -> Unit) -> Unit = { _, _, _ -> },
    val onRenameSmartFolder: (Long, String, (SmartFolderResult) -> Unit) -> Unit = { _, _, _ -> },
    /** 開いたあとに条件を変えたフォルダを、今の条件で上書きする */
    val onUpdateSmartFolder: (SmartFolder) -> Unit = {},
    val onDeleteSmartFolder: (SmartFolder) -> Unit = {},
    /** タグを選ぶ・外す(複数選べる) */
    val onTagToggle: (Long) -> Unit = {},
    /** カテゴリを選ぶ・外す(複数選べる) */
    val onCategoryToggle: (String) -> Unit = {},
    /** 複数のタグの組み合わせ方(すべて含む / どれかを含む) */
    val onTagMatchChange: (TagMatch) -> Unit = {},
    /** 選んでいるタグ・カテゴリ・種類・期間をすべて外す */
    val onClearFilters: () -> Unit = {},
    val onToggleFavorite: (Bookmark) -> Unit = {},
    val onSetArchived: (Bookmark, Boolean) -> Unit = { _, _ -> },
    val onDelete: (Bookmark) -> Unit = {},
    val onLongPress: (Bookmark) -> Unit = {},
    val onAddClick: () -> Unit = {},
    val onOpenTagManagement: () -> Unit = {},
    val onOpenDiscover: () -> Unit = {},
    val onOpenDuplicates: () -> Unit = {},
    val onRetry: () -> Unit = {}
)

/**
 * 一覧画面の本体(状態を受け取って描くだけ)。
 * 上から: トップバー(通常/検索) → 絞り込みチップ → 今日の再発見 → 件数と並び順 → カードの一覧。右下に追加ボタン。
 * カードは幅に合わせて複数列に並べる(スマートフォンでは1列。タブレットなど広い画面では、カードが横に間延びしないよう2列以上)。
 *
 * @param nowMillis 相対日時の基準。スクリーンショットでは固定値を渡す
 * @param selectedBookmarkId 2 画面表示で右側に詳細を出しているブックマーク。そのカードを強調する
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkListContent(
    uiState: BookmarkListUiState,
    snackbarHostState: SnackbarHostState,
    callbacks: BookmarkListCallbacks,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    selectedBookmarkId: Long? = null
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyStaggeredGridState()
    // 一番上にいるか、上へ戻る方向にスクロールしたときだけ、追加ボタンを文字つきで大きく出す
    val fabExpanded by remember { derivedStateOf { !listState.canScrollBackward || listState.lastScrolledBackward } }

    BackHandler(enabled = uiState.isSearchActive) { callbacks.onSearchActiveChange(false) }
    var showConditionSheet by rememberSaveable { mutableStateOf(false) }
    var smartFolderDialog by remember { mutableStateOf<SmartFolderDialog?>(null) }
    var smartFolderError by remember { mutableStateOf<String?>(null) }
    smartFolderDialog?.let { dialog ->
        val close = {
            smartFolderDialog = null
            smartFolderError = null
        }
        when (dialog) {
            SmartFolderDialog.Save -> SmartFolderNameDialog(
                title = "スマートフォルダとして保存",
                confirmLabel = "保存",
                initialName = "",
                sortOrder = uiState.sortOrder,
                error = smartFolderError,
                onConfirm = { name, includeSort ->
                    callbacks.onSaveSmartFolder(name, includeSort) { result ->
                        smartFolderError = result.errorMessage()
                        if (smartFolderError == null) close()
                    }
                },
                onDismiss = close
            )
            is SmartFolderDialog.Rename -> SmartFolderNameDialog(
                title = "名前を変える",
                confirmLabel = "変更",
                initialName = dialog.folder.name,
                sortOrder = null,
                error = smartFolderError,
                onConfirm = { name, _ ->
                    callbacks.onRenameSmartFolder(dialog.folder.id, name) { result ->
                        smartFolderError = result.errorMessage()
                        if (smartFolderError == null) close()
                    }
                },
                onDismiss = close
            )
            is SmartFolderDialog.Manage -> SmartFolderActionSheet(
                folder = dialog.folder,
                onRename = { smartFolderDialog = SmartFolderDialog.Rename(dialog.folder) },
                onDelete = { smartFolderDialog = SmartFolderDialog.ConfirmDelete(dialog.folder) },
                onDismiss = { if (smartFolderDialog is SmartFolderDialog.Manage) close() }
            )
            is SmartFolderDialog.ConfirmDelete -> AlertDialog(
                onDismissRequest = close,
                icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                title = { Text("削除しますか?") },
                text = { Text("スマートフォルダ「${dialog.folder.name}」を削除します。中のブックマークは消えません。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            callbacks.onDeleteSmartFolder(dialog.folder)
                            close()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("削除") }
                },
                dismissButton = { TextButton(onClick = close) { Text("キャンセル") } }
            )
        }
    }
    if (showConditionSheet) {
        ConditionSheet(
            selectedTypes = uiState.selectedTypes,
            period = uiState.period,
            readState = uiState.readState,
            onTypeToggle = callbacks.onTypeToggle,
            onPeriodToggle = callbacks.onPeriodToggle,
            onReadStateToggle = callbacks.onReadStateToggle,
            onClear = callbacks.onClearTypeAndPeriod,
            onDismiss = { showConditionSheet = false }
        )
    }

    // 絞り込み・検索の切り替えや空状態への切り替えで、スクロールで隠れたトップバーを戻す
    // (空状態はスクロールできないことが多く、そのままだとトップバーが隠れたままになる)
    val showingList = !uiState.isLoading && uiState.errorMessage == null && uiState.emptyKind == ListEmptyKind.NONE
    LaunchedEffect(uiState.filter, uiState.selectedTagIds, uiState.selectedCategories, uiState.isSearchActive, showingList) {
        scrollBehavior.state.heightOffset = 0f
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (uiState.isSearchActive) {
                SearchTopBar(
                    query = uiState.searchQuery,
                    queryRevision = uiState.queryRevision,
                    onQueryChange = callbacks.onSearchQueryChange,
                    onSubmit = callbacks.onSearchSubmit,
                    onClose = { callbacks.onSearchActiveChange(false) }
                )
            } else {
                ListTopBar(
                    sortOrder = uiState.sortOrder,
                    display = uiState.display,
                    scrollBehavior = scrollBehavior,
                    onSearchClick = { callbacks.onSearchActiveChange(true) },
                    onSortOrderChange = callbacks.onSortOrderChange,
                    onDisplayChange = callbacks.onDisplayChange,
                    onOpenTagManagement = callbacks.onOpenTagManagement,
                    onOpenDiscover = callbacks.onOpenDiscover,
                    onOpenDuplicates = callbacks.onOpenDuplicates
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSearchActive) {
                ExtendedFloatingActionButton(
                    text = { Text("追加") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    expanded = fabExpanded,
                    onClick = callbacks.onAddClick
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (!uiState.isSearchActive) {
                SmartFolderRow(
                    folders = uiState.smartFolders,
                    activeId = uiState.activeSmartFolderId,
                    canSave = uiState.canSaveSmartFolder,
                    folderToUpdate = uiState.smartFolderToUpdate,
                    onSelect = callbacks.onSmartFolderSelect,
                    onSave = {
                        smartFolderError = null
                        smartFolderDialog = SmartFolderDialog.Save
                    },
                    onUpdate = callbacks.onUpdateSmartFolder,
                    onManage = { smartFolderDialog = SmartFolderDialog.Manage(it) },
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (uiState.isSearchActive) {
                SearchSuggestionRow(
                    suggestions = uiState.suggestions,
                    canRemoveHistory = uiState.searchQuery.isBlank(),
                    onSelect = callbacks.onSuggestionSelect,
                    onRemoveHistory = callbacks.onHistoryRemove,
                    onClearHistory = callbacks.onHistoryClear,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            FilterBar(
                filter = uiState.filter,
                tags = uiState.allTags,
                selectedTagIds = uiState.selectedTagIds,
                tagMatch = uiState.tagMatch,
                categories = uiState.allCategories,
                selectedCategories = uiState.selectedCategories,
                selectedTypes = uiState.selectedTypes,
                period = uiState.period,
                readState = uiState.readState,
                onFilterChange = callbacks.onFilterChange,
                onTagToggle = callbacks.onTagToggle,
                onCategoryToggle = callbacks.onCategoryToggle,
                onTypeToggle = callbacks.onTypeToggle,
                onPeriodToggle = callbacks.onPeriodToggle,
                onReadStateToggle = callbacks.onReadStateToggle,
                onOpenConditions = { showConditionSheet = true },
                onTagMatchChange = callbacks.onTagMatchChange,
                onClearFilters = callbacks.onClearFilters
            )
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage, onRetry = callbacks.onRetry)
                uiState.emptyKind != ListEmptyKind.NONE -> ListEmptyState(uiState, callbacks)
                else -> BookmarkList(uiState, callbacks, listState, nowMillis, selectedBookmarkId)
            }
        }
    }
}

/**
 * カードの一覧。幅に合わせて [BOOKMARK_CARD_MIN_WIDTH] 以上の列に分け、高さの違うカードを詰めて並べる(石垣状)。
 * 見出しと「今日の再発見」は全列にまたがる。
 */
@Composable
private fun BookmarkList(
    uiState: BookmarkListUiState,
    callbacks: BookmarkListCallbacks,
    listState: LazyStaggeredGridState,
    nowMillis: Long,
    selectedBookmarkId: Long?
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(if (uiState.display == BookmarkListDisplay.GRID) BOOKMARK_TILE_MIN_WIDTH else BOOKMARK_CARD_MIN_WIDTH),
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 下は追加ボタンに最後のカードが隠れないだけ空ける
        contentPadding = PaddingValues(start = LIST_SIDE_PADDING, end = LIST_SIDE_PADDING, bottom = 96.dp),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (uiState.isShowingSearchResults) {
            item(key = "search_header", contentType = "header", span = StaggeredGridItemSpan.FullLine) {
                Box(Modifier.ignoreListSidePadding()) {
                    SearchResultHeader(
                        count = uiState.bookmarks.size,
                        isSearching = uiState.isSearching,
                        semanticAvailable = uiState.semanticSearchAvailable
                    )
                }
            }
        } else {
            if (uiState.showRecommendations) {
                item(key = "recommendations", contentType = "recommendations", span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.ignoreListSidePadding()) {
                        ReadNextSection(uiState.recommendations, callbacks.onOpenBookmark)
                    }
                }
            }
            if (uiState.showRediscover) {
                item(key = "rediscover", contentType = "rediscover", span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.ignoreListSidePadding()) {
                        RediscoverSection(uiState.rediscover, nowMillis, callbacks.onOpenBookmark)
                    }
                }
            }
            item(key = "list_header", contentType = "header", span = StaggeredGridItemSpan.FullLine) {
                Box(Modifier.ignoreListSidePadding()) {
                    ListHeader(count = uiState.bookmarks.size, sortOrder = uiState.sortOrder)
                }
            }
        }
        staggeredItems(uiState.bookmarks, key = { it.id }, contentType = { "bookmark_${uiState.display.name}" }) { bookmark ->
            val onArchiveToggle = { callbacks.onSetArchived(bookmark, !bookmark.isArchived) }
            val onDelete = { callbacks.onDelete(bookmark) }
            val archiveLabel = if (bookmark.isArchived) "アーカイブから戻す" else "アーカイブ"
            val searchTerms = if (uiState.isShowingSearchResults) uiState.searchTerms else emptyList()
            val searchMatch = if (uiState.isShowingSearchResults) uiState.searchMatches[bookmark.id] else null
            val selected = bookmark.id == selectedBookmarkId
            val onClick = { callbacks.onOpenBookmark(bookmark.id) }
            val onLongClick = { callbacks.onLongPress(bookmark) }
            // スワイプできない人向けに、同じ操作を TalkBack のカスタムアクションにも出す。
            // TalkBack がフォーカスするのはカード(クリックできるノード)なので、カードに付ける
            val accessibility = Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(if (bookmark.isRead) "未読に戻す" else "既読にする") { callbacks.onToggleRead(bookmark); true },
                    CustomAccessibilityAction(archiveLabel) { onArchiveToggle(); true },
                    CustomAccessibilityAction("削除") { onDelete(); true }
                )
            }
            val item: @Composable () -> Unit = {
                Column {
                    if (bookmark.id in uiState.semanticMatchIds) SemanticMatchLabel()
                    when (uiState.display) {
                        BookmarkListDisplay.CARD -> BookmarkCard(
                            bookmark = bookmark,
                            searchTerms = searchTerms,
                            searchMatch = searchMatch,
                            selected = selected,
                            onClick = onClick,
                            onToggleFavorite = { callbacks.onToggleFavorite(bookmark) },
                            onLongClick = onLongClick,
                            onLongClickLabel = "操作メニューを開く",
                            nowMillis = nowMillis,
                            modifier = accessibility
                        )
                        BookmarkListDisplay.COMPACT -> CompactBookmarkRow(
                            bookmark = bookmark,
                            searchTerms = searchTerms,
                            searchMatch = searchMatch,
                            selected = selected,
                            onClick = onClick,
                            onToggleFavorite = { callbacks.onToggleFavorite(bookmark) },
                            onLongClick = onLongClick,
                            onLongClickLabel = "操作メニューを開く",
                            nowMillis = nowMillis,
                            modifier = accessibility
                        )
                        BookmarkListDisplay.GRID -> BookmarkGridTile(
                            bookmark = bookmark,
                            searchTerms = searchTerms,
                            selected = selected,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = "操作メニューを開く",
                            nowMillis = nowMillis,
                            modifier = accessibility
                        )
                    }
                }
            }
            if (uiState.display == BookmarkListDisplay.GRID) {
                // 幅の狭いタイルは、横にスワイプして操作するには向かない。長押しのメニューと TalkBack の操作で同じことができる
                Box(Modifier.animateItem()) { item() }
            } else {
                SwipeableBookmarkItem(
                    bookmark = bookmark,
                    onArchiveToggle = onArchiveToggle,
                    onDelete = onDelete,
                    modifier = Modifier.animateItem()
                ) { item() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListTopBar(
    sortOrder: BookmarkSortOrder,
    display: BookmarkListDisplay,
    scrollBehavior: TopAppBarScrollBehavior,
    onSearchClick: () -> Unit,
    onSortOrderChange: (BookmarkSortOrder) -> Unit,
    onDisplayChange: (BookmarkListDisplay) -> Unit,
    onOpenTagManagement: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenDuplicates: () -> Unit
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var displayMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    TopAppBar(
        // 文字が大きいときも単語の途中で折り返さず、1行に収めて省略する
        title = { Text("ブックマーク", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        scrollBehavior = scrollBehavior,
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Outlined.Search, contentDescription = "検索")
            }
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "並べ替え(${sortOrder.label()})")
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    BookmarkSortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.label()) },
                            onClick = {
                                sortMenuOpen = false
                                onSortOrderChange(order)
                            },
                            leadingIcon = {
                                if (order == sortOrder) {
                                    Icon(Icons.Filled.Check, contentDescription = "選択中")
                                } else {
                                    Spacer(Modifier.size(24.dp))
                                }
                            }
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { displayMenuOpen = true }) {
                    Icon(display.icon(), contentDescription = "表示の切り替え(${display.label()})")
                }
                DropdownMenu(expanded = displayMenuOpen, onDismissRequest = { displayMenuOpen = false }) {
                    BookmarkListDisplay.entries.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(entry.label()) },
                            onClick = {
                                displayMenuOpen = false
                                onDisplayChange(entry)
                            },
                            leadingIcon = {
                                if (entry == display) {
                                    Icon(Icons.Filled.Check, contentDescription = "選択中")
                                } else {
                                    Icon(entry.icon(), contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "その他のメニュー")
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("資料を探す") },
                        leadingIcon = { Icon(Icons.Outlined.TravelExplore, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenDiscover()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("タグを管理") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenTagManagement()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("重複と関連を整理") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenDuplicates()
                        }
                    )
                }
            }
        }
    )
}

/** 検索モードのトップバー。戻る・検索欄・クリアを並べる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    queryRevision: Int,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // 入力中の文字はここで持ち、その場で書き換える。ViewModel の uiState(複数の Flow を合わせたもの)を通して
    // 表示し直すと反映が1テンポ遅れ、その間に古い文字列で上書きされて、日本語入力の変換中の文字が消える・確定されるなど
    // 入力できなくなるため。[query] は検索欄を開いたときの初期値にだけ使う
    var text by rememberSaveable { mutableStateOf(query) }
    // ViewModel の側で検索語を書き換えたとき(候補を選んだとき)だけ、入力中の文字を取り込み直す
    var seenRevision by rememberSaveable { mutableIntStateOf(queryRevision) }
    LaunchedEffect(queryRevision) {
        if (queryRevision != seenRevision) {
            seenRevision = queryRevision
            text = query
        }
    }
    val onTextChange = { value: String ->
        text = value
        onQueryChange(value)
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .height(72.dp)
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "検索を閉じる")
            }
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text("キーワードや内容で検索", maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { onTextChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "検索語を消す")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSubmit()
                })
            )
        }
    }
}

/**
 * 絞り込み(すべて/お気に入り/アーカイブ)・カテゴリ・タグのチップを横スクロールで並べる。
 * カテゴリ・タグは複数選べる(タグは、2つ以上選ぶと下に「すべて含む / どれかを含む」の切り替えを出す)。
 * タグかカテゴリで絞り込んでいる間は、右端に「すべて解除」を固定して出す(選んだチップは先頭へ移るため、
 * 右へスクロールして選んだあとは見えなくなる。スクロールし直さずに一度で絞り込みを外せるように)。押すと、選んでいるタグとカテゴリをすべて外す
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    filter: BookmarkFilter,
    tags: List<Tag>,
    selectedTagIds: Set<Long>,
    tagMatch: TagMatch,
    categories: List<String>,
    selectedCategories: Set<String>,
    selectedTypes: Set<BookmarkType>,
    period: CreatedPeriod?,
    readState: ReadState?,
    onFilterChange: (BookmarkFilter) -> Unit,
    onTagToggle: (Long) -> Unit,
    onCategoryToggle: (String) -> Unit,
    onTypeToggle: (BookmarkType) -> Unit,
    onPeriodToggle: (CreatedPeriod) -> Unit,
    onReadStateToggle: (ReadState) -> Unit,
    onOpenConditions: () -> Unit,
    onTagMatchChange: (TagMatch) -> Unit,
    onClearFilters: () -> Unit
) {
    val narrowed = selectedTagIds.isNotEmpty() || selectedCategories.isNotEmpty() || selectedTypes.isNotEmpty() || period != null ||
        readState != null
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChipRow(
                filter = filter,
                tags = tags,
                selectedTagIds = selectedTagIds,
                categories = categories,
                selectedCategories = selectedCategories,
                selectedTypes = selectedTypes,
                period = period,
                readState = readState,
                onFilterChange = onFilterChange,
                onTagToggle = onTagToggle,
                onCategoryToggle = onCategoryToggle,
                onTypeToggle = onTypeToggle,
                onPeriodToggle = onPeriodToggle,
                onReadStateToggle = onReadStateToggle,
                onOpenConditions = onOpenConditions,
                // 「すべて解除」を出す間は、右の余白を詰めてそちらに譲る
                endPadding = if (narrowed) 8.dp else 16.dp,
                modifier = Modifier.weight(1f)
            )
            if (narrowed) {
                AssistChip(
                    onClick = onClearFilters,
                    label = { Text("すべて解除") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .semantics { contentDescription = "タグ・カテゴリ・種類・期間・読んだかの絞り込みをすべて解除" }
                )
            }
        }
        if (selectedTagIds.size >= 2) {
            TagMatchRow(count = selectedTagIds.size, tagMatch = tagMatch, onChange = onTagMatchChange)
        }
    }
}

/** 複数のタグを選んだときの組み合わせ方(すべて含む=AND / どれかを含む=OR)の切り替え。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagMatchRow(count: Int, tagMatch: TagMatch, onChange: (TagMatch) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = "選んだタグの組み合わせ方" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "選んだ${count}個のタグを",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        FilterChip(
            selected = tagMatch == TagMatch.ALL,
            onClick = { onChange(TagMatch.ALL) },
            label = { Text("すべて含む") }
        )
        FilterChip(
            selected = tagMatch == TagMatch.ANY,
            onClick = { onChange(TagMatch.ANY) },
            label = { Text("どれかを含む") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    filter: BookmarkFilter,
    tags: List<Tag>,
    selectedTagIds: Set<Long>,
    categories: List<String>,
    selectedCategories: Set<String>,
    selectedTypes: Set<BookmarkType>,
    period: CreatedPeriod?,
    readState: ReadState?,
    onFilterChange: (BookmarkFilter) -> Unit,
    onTagToggle: (Long) -> Unit,
    onCategoryToggle: (String) -> Unit,
    onTypeToggle: (BookmarkType) -> Unit,
    onPeriodToggle: (CreatedPeriod) -> Unit,
    onReadStateToggle: (ReadState) -> Unit,
    onOpenConditions: () -> Unit,
    endPadding: Dp,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(FILTER_ORDER, key = { "filter_${it.name}" }) { item ->
            val selected = filter == item
            FilterChip(
                selected = selected,
                onClick = { onFilterChange(item) },
                label = { Text(item.label()) },
                leadingIcon = item.icon(selected)?.let { icon ->
                    { Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                }
            )
        }
        item(key = "conditions") {
            val count = selectedTypes.size + (if (period != null) 1 else 0) + (if (readState != null) 1 else 0)
            AssistChip(
                onClick = onOpenConditions,
                label = { Text(if (count == 0) "条件" else "条件(${count})") },
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.semantics {
                    contentDescription = if (count == 0) "種類・保存した時期・読んだかで絞り込む" else "種類・保存した時期・読んだかで絞り込む(${count}件を選択中)"
                }
            )
        }
        conditionChips(selectedTypes, period, readState, onTypeToggle, onPeriodToggle, onReadStateToggle)
        if (categories.isNotEmpty()) {
            item(key = "divider_categories") {
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            }
            items(categories, key = { "category_${it}" }) { name ->
                val selected = selectedCategories.any { it.equals(name, ignoreCase = true) }
                FilterChip(
                    selected = selected,
                    onClick = { onCategoryToggle(name) },
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(
                            if (selected) Icons.Filled.Check else Icons.Outlined.Category,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    },
                    modifier = Modifier
                        .animateItem()
                        .semantics { contentDescription = "カテゴリ「${name}」" + if (selected) "(選択中。タップで外す)" else "" }
                )
            }
        }
        if (tags.isNotEmpty()) {
            item(key = "divider") {
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            }
        }
        items(tags, key = { "tag_${it.id}" }) { tag ->
            val selected = tag.id in selectedTagIds
            FilterChip(
                selected = selected,
                onClick = { onTagToggle(tag.id) },
                label = { Text("#${tag.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = if (selected) {
                    { Icon(Icons.Outlined.Close, contentDescription = "絞り込みを解除", modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                modifier = Modifier.animateItem()
            )
        }
    }
}

/** 選んでいるタグ・カテゴリを、空状態の説明に出す形("#A と #B / 技術" など)にする。何も選んでいなければ null。 */
private fun filterSummary(uiState: BookmarkListUiState): String? {
    val tags = uiState.selectedTags
    val tagText = when {
        tags.isEmpty() -> null
        tags.size == 1 -> "#${tags.single().name}"
        else -> tags.joinToString(if (uiState.tagMatch == TagMatch.ALL) " かつ " else " または ") { "#${it.name}" }
    }
    val categoryText = uiState.selectedCategories.takeIf { it.isNotEmpty() }?.joinToString(" または ")?.let { "カテゴリ: ${it}" }
    val typeText = uiState.selectedTypes.takeIf { it.isNotEmpty() }?.joinToString(" または ") { it.displayName() }?.let { "種類: ${it}" }
    val periodText = uiState.period?.let { "保存した時期: ${it.label()}" }
    val readText = uiState.readState?.label()
    return listOfNotNull(tagText, categoryText, typeText, periodText, readText).takeIf { it.isNotEmpty() }?.joinToString("、")
}

@Composable
private fun SearchResultHeader(count: Int, isSearching: Boolean, semanticAvailable: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("検索中…", style = MaterialTheme.typography.titleSmall)
            } else {
                Text(
                    "${count}件見つかりました",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (semanticAvailable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(
                if (semanticAvailable) Icons.Outlined.AutoAwesome else Icons.Outlined.Info,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (semanticAvailable) {
                    "キーワードと意味の近さで検索しています"
                } else {
                    "キーワードで検索しています(意味での検索は AI の準備ができると使えます)"
                },
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

/** 意味の近さだけで見つかった結果に付ける小さな目印 */
@Composable
private fun SemanticMatchLabel() {
    Row(
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text("意味が近い結果", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun ListHeader(count: Int, sortOrder: BookmarkSortOrder) {
    Text(
        text = "${count}件 · ${sortOrder.label()}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp)
    )
}

/** 「次に読む」(#61): まだ読んでいないものから、最近読んだものに近い・お気に入り・すぐ読める・新しいものを選んで横に並べる */
@Composable
private fun ReadNextSection(items: List<Recommendation>, onOpen: (Long) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "次に読む",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "まだ読んでいないブックマークから",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.bookmark.id }) { recommendation ->
                CompactBookmarkCard(
                    bookmark = recommendation.bookmark,
                    onClick = { onOpen(recommendation.bookmark.id) },
                    caption = recommendation.reason.caption(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            }
        }
    }
}

/** おすすめの理由の言い方 */
internal fun RecommendationReason.caption(): String = when (this) {
    is RecommendationReason.SharedTag -> "最近「#${tag}」を読んでいます"
    is RecommendationReason.SharedCategory -> "最近「${category}」を読んでいます"
    RecommendationReason.Favorite -> "お気に入りで、まだ読んでいません"
    is RecommendationReason.Short -> "${ReadingTime.label(minutes)}で読めます"
    RecommendationReason.Recent -> "最近保存しました"
}

/** 「今日の再発見」: しばらく開いていないブックマークを横に並べる */
@Composable
private fun RediscoverSection(items: List<Bookmark>, nowMillis: Long, onOpen: (Long) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "今日の再発見",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "しばらく開いていないブックマーク",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { bookmark ->
                CompactBookmarkCard(
                    bookmark = bookmark,
                    onClick = { onOpen(bookmark.id) },
                    caption = rediscoverCaption(bookmark, nowMillis),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            }
        }
    }
}

private fun rediscoverCaption(bookmark: Bookmark, nowMillis: Long): String =
    if (bookmark.lastAccessedAt <= bookmark.createdAt) {
        "${formatRelativeTime(bookmark.createdAt, nowMillis)}に保存してから未読"
    } else {
        "最後に開いたのは${formatRelativeTime(bookmark.lastAccessedAt, nowMillis)}"
    }

/**
 * 右へスワイプでアーカイブ(アーカイブ済みなら戻す)、左へスワイプで削除。どちらも Snackbar で取り消せる。
 * 同じ操作の TalkBack のカスタムアクションは、中のカードに付ける([BookmarkList])。
 */
@Composable
private fun SwipeableBookmarkItem(
    bookmark: Bookmark,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    // 取り消しで同じ ID が戻ってきたとき、スワイプ済みの状態を引き継がないよう保存しない state にする
    val state = remember(bookmark.id, bookmark.isArchived) {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold)
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = { SwipeBackground(state.dismissDirection, bookmark.isArchived) },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> onArchiveToggle()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        }
    ) {
        content()
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, isArchived: Boolean) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val scheme = MaterialTheme.colorScheme
    val isArchive = direction == SwipeToDismissBoxValue.StartToEnd
    val (container, content) = if (isArchive) {
        scheme.tertiaryContainer to scheme.onTertiaryContainer
    } else {
        scheme.errorContainer to scheme.onErrorContainer
    }
    val icon: ImageVector
    val label: String
    if (isArchive) {
        icon = if (isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive
        label = if (isArchived) "戻す" else "アーカイブ"
    } else {
        icon = Icons.Outlined.Delete
        label = "削除"
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .padding(horizontal = 24.dp),
        contentAlignment = if (isArchive) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = content)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

@Composable
private fun ListEmptyState(uiState: BookmarkListUiState, callbacks: BookmarkListCallbacks) {
    when (uiState.emptyKind) {
        ListEmptyKind.FIRST_RUN -> EmptyState(
            icon = Icons.Outlined.BookmarkAdd,
            title = "まだブックマークがありません",
            description = "ブラウザなどの共有メニューから「EchoMarkに保存」を選ぶと、記事を保存して AI が要約します。",
            actionLabel = "URL を追加",
            actionIcon = Icons.Filled.Add,
            onAction = callbacks.onAddClick
        )
        ListEmptyKind.ALL_ARCHIVED -> EmptyState(
            icon = Icons.Outlined.Archive,
            title = "すべてアーカイブ済みです",
            description = "アーカイブしたブックマークは「アーカイブ」で見られます。",
            actionLabel = "アーカイブを見る",
            onAction = { callbacks.onFilterChange(BookmarkFilter.ARCHIVED) }
        )
        ListEmptyKind.NO_FAVORITES -> EmptyState(
            icon = Icons.Outlined.StarOutline,
            title = "お気に入りはまだありません",
            description = "カードの☆をタップすると、ここにまとまります。",
            actionLabel = "すべて表示",
            onAction = { callbacks.onFilterChange(BookmarkFilter.ACTIVE) }
        )
        ListEmptyKind.NO_ARCHIVED -> EmptyState(
            icon = Icons.Outlined.Archive,
            title = "アーカイブは空です",
            description = "一覧でカードを右へスワイプすると、ここへ移せます。"
        )
        ListEmptyKind.NO_FILTER_MATCH -> EmptyState(
            icon = Icons.AutoMirrored.Outlined.Label,
            title = "当てはまるブックマークがありません",
            description = filterSummary(uiState)?.let { "「${it}」に当てはまるブックマークは、この絞り込みにはありません。" },
            actionLabel = "タグとカテゴリの絞り込みを解除",
            onAction = callbacks.onClearFilters
        )
        ListEmptyKind.NO_SEARCH_RESULT -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = "「${uiState.searchQuery.trim()}」は見つかりませんでした",
            description = if (uiState.semanticSearchAvailable) {
                "別の言葉や、探している内容を文章で入力してみてください。"
            } else {
                "別の言葉で試してみてください。AI の準備ができると、意味の近いものも探せます。"
            }
        )
        ListEmptyKind.NONE -> Unit
    }
}

/** 一覧の見せ方の表示名 */
internal fun BookmarkListDisplay.label(): String = when (this) {
    BookmarkListDisplay.CARD -> "カード"
    BookmarkListDisplay.COMPACT -> "コンパクト"
    BookmarkListDisplay.GRID -> "グリッド"
}

/** 一覧の見せ方のアイコン */
internal fun BookmarkListDisplay.icon(): ImageVector = when (this) {
    BookmarkListDisplay.CARD -> Icons.Outlined.ViewAgenda
    BookmarkListDisplay.COMPACT -> Icons.AutoMirrored.Outlined.ViewList
    BookmarkListDisplay.GRID -> Icons.Outlined.GridView
}

internal fun BookmarkSortOrder.label(): String = when (this) {
    BookmarkSortOrder.NEWEST -> "新しい順"
    BookmarkSortOrder.OLDEST -> "古い順"
    BookmarkSortOrder.RECENTLY_OPENED -> "最近開いた順"
    BookmarkSortOrder.TITLE -> "タイトル順"
}

/** 一覧の左右の余白 */
private val LIST_SIDE_PADDING = 16.dp

/**
 * カード1列の最小幅。これより広い列が2つ取れる幅なら2列にする(縦向きのタブレット・スマートフォンの横向きで2列、縦向きでは1列)。
 * 小さめのスマートフォン(幅 360dp)のカードより少し狭いくらい
 */
private val BOOKMARK_CARD_MIN_WIDTH = 300.dp

/** グリッド表示のタイルの最小の幅。スマートフォンで 2 列になる(余白を引いた幅の半分より少し小さい) */
private val BOOKMARK_TILE_MIN_WIDTH = 150.dp

/**
 * 一覧の左右の余白([LIST_SIDE_PADDING])を打ち消して、端から端まで広げる。
 * 見出し・「今日の再発見」(横スクロール)は自分で左右の余白を持っていて、端まで届くほうが自然なため
 */
private fun Modifier.ignoreListSidePadding(): Modifier = layout { measurable, constraints ->
    val side = LIST_SIDE_PADDING.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + side * 2,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + side * 2 else constraints.maxWidth
        )
    )
    val width = (placeable.width - side * 2).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.placeRelative(-side, 0) }
}

/** 絞り込みチップの並び(すべて → お気に入り → アーカイブ) */
private val FILTER_ORDER = listOf(BookmarkFilter.ACTIVE, BookmarkFilter.FAVORITES, BookmarkFilter.ARCHIVED)

private fun BookmarkFilter.label(): String = when (this) {
    BookmarkFilter.ACTIVE -> "すべて"
    BookmarkFilter.FAVORITES -> "お気に入り"
    BookmarkFilter.ARCHIVED -> "アーカイブ"
}

private fun BookmarkFilter.icon(selected: Boolean): ImageVector? = when (this) {
    BookmarkFilter.ACTIVE -> null
    BookmarkFilter.FAVORITES -> if (selected) Icons.Filled.Star else Icons.Outlined.StarOutline
    BookmarkFilter.ARCHIVED -> Icons.Outlined.Archive
}
