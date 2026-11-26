package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import com.unchunks.echomark.ui.navigation.LocalBottomBarOverlap
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.ui.navigation.bottomBarAwareContentInsets
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.SearchSuggestion
import com.unchunks.echomark.domain.bookmark.model.SmartFolder
import com.unchunks.echomark.domain.bookmark.model.SmartFolderResult
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.common.shareBookmark
import com.unchunks.echomark.ui.selection.BulkActionBar
import com.unchunks.echomark.ui.selection.BulkActionCallbacks
import com.unchunks.echomark.ui.selection.SelectionMark
import com.unchunks.echomark.ui.selection.SelectionTopBar
import com.unchunks.echomark.ui.selection.showBulkMessage
import com.unchunks.echomark.ui.settings.DesignStyleDialog
import com.unchunks.echomark.ui.common.shortTitle
import com.unchunks.echomark.ui.components.BookmarkCard
import com.unchunks.echomark.ui.components.BookmarkGridTile
import com.unchunks.echomark.ui.components.CompactBookmarkRow
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.review.ReadLaterViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import com.unchunks.echomark.ui.design.EchoMarkExtendedFab
import com.unchunks.echomark.ui.design.EchoMarkSmallFab

/**
 * ブックマーク一覧(ホーム)。ViewModel をつなぐだけの薄いラッパーで、描画は [BookmarkListContent] が行う。
 * Snackbar(保存・削除・アーカイブの結果と取り消し)、追加シート、長押しの操作メニューをここで扱う。
 *
 * @param selectedBookmarkId 2 画面表示で右側に詳細を出しているブックマーク(カードを強調する)
 * @param onBookmarkRemoved 一覧での操作(削除・アーカイブ/戻す)でブックマークが今の一覧から外れたとき。
 *   2 画面表示で、右側に出しているものなら右側を空にするために使う
 * @param readLaterViewModel 1件だけ選んだときの「その他」の「あとで読む」に使う。null なら項目を出さない(Hilt を使わないテスト用)
 * @param designIntroViewModel 新しいデザインの案内(一度だけ出す)。null なら案内を出さない(Hilt を使わないテスト用)
 */
@Composable
fun BookmarkListScreen(
    onOpenBookmark: (Long) -> Unit = {},
    onOpenTagManagement: () -> Unit = {},
    selectedBookmarkId: Long? = null,
    onBookmarkRemoved: (Long) -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onOpenDuplicates: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    onOpenReview: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenBrokenLinks: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    onMakePodcast: (List<Long>) -> Unit = {},
    viewModel: BookmarkViewModel = hiltViewModel(),
    readLaterViewModel: ReadLaterViewModel? = null,
    designIntroViewModel: DesignIntroViewModel? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val designIntro = designIntroViewModel?.state?.collectAsState()?.value ?: DesignIntroState()
    var showDesignDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    val currentOnOpenBookmark by rememberUpdatedState(onOpenBookmark)

    // ショートカット・ウィジェットの「URL を追加」から起動したら追加シートを開く
    LaunchedEffect(viewModel) {
        viewModel.addSheetRequests.collect { showAddSheet = true }
    }

    // 選択中の「その他」の「あとで読む」の結果
    LaunchedEffect(readLaterViewModel, snackbarHostState) {
        readLaterViewModel?.messages?.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(message) }
        }
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
                    is BookmarkListMessage.Bulk -> snackbarHostState.showBulkMessage(
                        message.message,
                        onUndoArchive = viewModel::undoBulkArchive,
                        onUndoDelete = viewModel::undoBulkDelete
                    )
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
    // 複数選択の操作。アーカイブ・削除で今の一覧から外れるものは、2 画面表示の右側に出していたら空にする
    val selectedIds = uiState.selectedIds
    val bulkCallbacks = BulkActionCallbacks(
        onSetFavorite = viewModel::bulkSetFavorite,
        onSetRead = viewModel::bulkSetRead,
        onSetArchived = { archived ->
            viewModel.bulkSetArchived(archived)
            selectedIds.forEach(onBookmarkRemoved)
        },
        onDelete = {
            viewModel.bulkDelete()
            selectedIds.forEach(onBookmarkRemoved)
        },
        onAddTag = viewModel::bulkAddTag,
        onRemoveTag = viewModel::bulkRemoveTag,
        onSetCategory = viewModel::bulkSetCategory,
        onAddToCollection = viewModel::bulkAddToCollection,
        onCreateCollectionAndAdd = viewModel::bulkCreateCollectionAndAdd,
        onReprocess = viewModel::bulkReprocess,
        onExport = viewModel::bulkExport,
        onShare = { bookmark -> shareBookmark(context, bookmark) },
        onToggleReadingList = readLaterViewModel?.let { readLater -> readLater::toggleReadingList },
        // 一覧に並んでいる順に紹介する
        onMakePodcast = { onMakePodcast(uiState.bookmarks.filter { it.id in selectedIds }.map { it.id }) }
    )

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
            onEnterSelection = viewModel::onEnterSelection,
            onToggleSelection = viewModel::onToggleSelection,
            onSelectAll = viewModel::onSelectAll,
            onClearSelection = viewModel::onClearSelection,
            onExitSelection = viewModel::onExitSelection,
            bulk = bulkCallbacks,
            onAddClick = { showAddSheet = true },
            onOpenTagManagement = onOpenTagManagement,
            onOpenDiscover = onOpenDiscover,
            onOpenDuplicates = onOpenDuplicates,
            onOpenInsights = onOpenInsights,
            onOpenReview = onOpenReview,
            onOpenCollections = onOpenCollections,
            onOpenBrokenLinks = onOpenBrokenLinks,
            onOpenPodcasts = onOpenPodcasts,
            onRetry = viewModel::retry
        ),
        selectedBookmarkId = selectedBookmarkId,
        topNotice = if (designIntro.show) {
            { DesignIntroBanner(onChoose = { showDesignDialog = true }, onDismiss = { designIntroViewModel?.dismiss() }) }
        } else {
            null
        }
    )

    // 案内から開いたデザインの選択。選んだらすぐ適用し、案内は見終えたことにする
    if (showDesignDialog) {
        DesignStyleDialog(
            selected = DesignStyle.CLASSIC,
            dynamicColor = designIntro.dynamicColor,
            onSelect = {
                designIntroViewModel?.choose(it)
                showDesignDialog = false
            },
            onDismiss = { showDesignDialog = false }
        )
    }

    if (showAddSheet) {
        AddBookmarkSheet(
            onDismiss = { showAddSheet = false },
            onSave = { input ->
                viewModel.save(input)
                showAddSheet = false
            }
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
    /** 複数選択のモードに入る(長押しならそのブックマークの ID、メニューの「選択」なら null) */
    val onEnterSelection: (Long?) -> Unit = {},
    /** 複数選択の間のタップ(選ぶ・外す) */
    val onToggleSelection: (Long) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onExitSelection: () -> Unit = {},
    /** 複数選択の操作バーの操作 */
    val bulk: BulkActionCallbacks = BulkActionCallbacks(),
    val onAddClick: () -> Unit = {},
    val onOpenTagManagement: () -> Unit = {},
    val onOpenDiscover: () -> Unit = {},
    val onOpenDuplicates: () -> Unit = {},
    val onOpenInsights: () -> Unit = {},
    val onOpenReview: () -> Unit = {},
    val onOpenCollections: () -> Unit = {},
    val onOpenBrokenLinks: () -> Unit = {},
    val onOpenPodcasts: () -> Unit = {},
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
    selectedBookmarkId: Long? = null,
    /** 一覧の先頭(再発見などの上)に出すお知らせ。null なら出さない。検索中や絞り込み中は出さない */
    topNotice: (@Composable () -> Unit)? = null
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    // 一番上にいるか、上へ戻る方向にスクロールしたときだけ、追加ボタンを文字つきで大きく出す
    val fabExpanded by remember { derivedStateOf { !listState.canScrollBackward || listState.lastScrolledBackward } }

    BackHandler(enabled = uiState.isSearchActive && !uiState.isSelecting) { callbacks.onSearchActiveChange(false) }
    // 戻るでまず選択をやめる(検索中なら、その次の戻るで検索を閉じる)
    BackHandler(enabled = uiState.isSelecting, onBack = callbacks.onExitSelection)
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
        // クリアのガラスのボトムバーは中身の上に重なるので、その分を下に空ける(追加ボタン・スナックバーもバーの上に出す)
        contentWindowInsets = bottomBarAwareContentInsets(),
        topBar = {
            if (uiState.isSelecting) {
                // 複数選択の間は、検索中でも通常のバーでもなく、選んだ件数のバーにする
                SelectionTopBar(
                    count = uiState.selectedIds.size,
                    total = uiState.bookmarks.size,
                    onClose = callbacks.onExitSelection,
                    onSelectAll = callbacks.onSelectAll,
                    onClear = callbacks.onClearSelection,
                    scrollBehavior = scrollBehavior
                )
            } else if (uiState.isSearchActive) {
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
                    onOpenDuplicates = callbacks.onOpenDuplicates,
                    onOpenInsights = callbacks.onOpenInsights,
                    onOpenReview = callbacks.onOpenReview,
                    onOpenCollections = callbacks.onOpenCollections,
                    onOpenBrokenLinks = callbacks.onOpenBrokenLinks,
                    onOpenPodcasts = callbacks.onOpenPodcasts,
                    canSelect = uiState.bookmarks.isNotEmpty(),
                    onEnterSelection = { callbacks.onEnterSelection(null) }
                )
            }
        },
        bottomBar = {
            if (uiState.isSelecting) {
                BulkActionBar(
                    selected = uiState.selectedBookmarks,
                    tagSuggestions = uiState.allTags.map { it.name },
                    categories = uiState.categoryChoices,
                    collections = uiState.collections,
                    callbacks = callbacks.bulk
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showingList && showScrollToTop) {
                    EchoMarkSmallFab(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(0)
                                scrollBehavior.state.heightOffset = 0f
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Outlined.VerticalAlignTop, contentDescription = "一覧の先頭に戻る")
                    }
                }
                if (!uiState.isSearchActive && !uiState.isSelecting) {
                    EchoMarkExtendedFab(
                        text = { Text("追加") },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        expanded = fabExpanded,
                        onClick = callbacks.onAddClick,
                        modifier = Modifier.semantics { contentDescription = "ブックマークを追加" }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            Modifier
                // ガラスのボトムバーに覆われる分は、ここでは空けずに一覧の contentPadding で空ける(中身がバーの裏を通るように)
                .padding(innerPadding.withoutBottom(LocalBottomBarOverlap.current))
                .fillMaxSize()
        ) {
            if (!uiState.isSearchActive) {
                // 「この条件を保存」と「すべて解除」は同じ行・同じ高さに並べる(条件を付けている間はどちらも出る)。
                // 「すべて解除」は右端に固定し、フォルダが多くて行が横に動いても隠れない
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.isNarrowed) {
                        // 行に何も出ない(フォルダが無く保存もできない)ときも、右端に寄せる
                        if (uiState.smartFolders.isEmpty() && !uiState.canSaveSmartFolder) Spacer(Modifier.weight(1f))
                        ClearConditionsChip(onClick = callbacks.onClearFilters, modifier = Modifier.padding(start = 8.dp, end = 16.dp))
                    }
                }
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
                onClearFilters = callbacks.onClearFilters,
                // 検索中はスマートフォルダの行が無いので、ここに出す
                showClear = uiState.isSearchActive
            )
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage, onRetry = callbacks.onRetry)
                uiState.emptyKind != ListEmptyKind.NONE -> ListEmptyState(uiState, callbacks)
                else -> BookmarkList(uiState, callbacks, listState, nowMillis, selectedBookmarkId, topNotice)
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
    selectedBookmarkId: Long?,
    topNotice: (@Composable () -> Unit)?
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(if (uiState.display == BookmarkListDisplay.GRID) BOOKMARK_TILE_MIN_WIDTH else BOOKMARK_CARD_MIN_WIDTH),
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 通常表示では追加・先頭へ戻るボタンの2段分を空け、最後のカードを操作できるようにする。
        contentPadding = PaddingValues(
            start = LIST_SIDE_PADDING,
            end = LIST_SIDE_PADDING,
            // クリアのガラスのボトムバーに覆われる分も足す(一覧はバーの裏まで続け、最後のカードは上までスクロールできる)
            bottom = (if (uiState.isSearchActive) 96.dp else 152.dp) + LocalBottomBarOverlap.current
        ),
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
            if (topNotice != null && !uiState.isSearchActive && !uiState.isSelecting && uiState.filter == BookmarkFilter.ACTIVE &&
                !uiState.isNarrowed && uiState.bookmarks.isNotEmpty()
            ) {
                item(key = "top_notice", contentType = "notice", span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.ignoreListSidePadding()) { topNotice() }
                }
            }
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
            val selecting = uiState.isSelecting
            val checked = bookmark.id in uiState.selectedIds
            // 複数選択の間は、タップも長押しも選ぶ・外す。そうでなければ、タップで開き、長押しで選択を始める
            // (これまで長押しで出していた操作メニューの操作は、選択モードの操作バーから行う)
            val selected = bookmark.id == selectedBookmarkId || checked
            val onClick = if (selecting) {
                { callbacks.onToggleSelection(bookmark.id) }
            } else {
                { callbacks.onOpenBookmark(bookmark.id) }
            }
            val onLongClick = if (selecting) {
                { callbacks.onToggleSelection(bookmark.id) }
            } else {
                { callbacks.onEnterSelection(bookmark.id) }
            }
            val longClickLabel = if (selecting) "選択を切り替える" else "選択を始める"
            // 複数選択の間は、選んでいるかを TalkBack で読む。そうでないときは、操作の入り口(スワイプや長押しのメニューの代わり)を
            // TalkBack のカスタムアクションに出す。TalkBack がフォーカスするのはカード(クリックできるノード)なので、カードに付ける
            val accessibility = if (selecting) {
                Modifier.semantics { stateDescription = if (checked) "選択中" else "選択していません" }
            } else {
                Modifier.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(if (bookmark.isRead) "未読に戻す" else "既読にする") { callbacks.onToggleRead(bookmark); true },
                        CustomAccessibilityAction(archiveLabel) { onArchiveToggle(); true },
                        CustomAccessibilityAction("削除") { onDelete(); true }
                    )
                }
            }
            // 選んでいる間は、星のボタンで1件だけが変わらないよう出さない
            val onToggleFavorite = if (selecting) null else ({ callbacks.onToggleFavorite(bookmark) })
            val item: @Composable () -> Unit = {
                Column {
                    if (bookmark.id in uiState.semanticMatchIds) SemanticMatchLabel()
                    Box {
                        when (uiState.display) {
                            BookmarkListDisplay.CARD -> BookmarkCard(
                                bookmark = bookmark,
                                searchTerms = searchTerms,
                                searchMatch = searchMatch,
                                selected = selected,
                                onClick = onClick,
                                onToggleFavorite = onToggleFavorite,
                                onLongClick = onLongClick,
                                onLongClickLabel = longClickLabel,
                                nowMillis = nowMillis,
                                modifier = accessibility
                            )
                            BookmarkListDisplay.COMPACT -> CompactBookmarkRow(
                                bookmark = bookmark,
                                searchTerms = searchTerms,
                                searchMatch = searchMatch,
                                selected = selected,
                                onClick = onClick,
                                onToggleFavorite = onToggleFavorite,
                                onLongClick = onLongClick,
                                onLongClickLabel = longClickLabel,
                                nowMillis = nowMillis,
                                modifier = accessibility
                            )
                            BookmarkListDisplay.GRID -> BookmarkGridTile(
                                bookmark = bookmark,
                                searchTerms = searchTerms,
                                selected = selected,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                onLongClickLabel = longClickLabel,
                                nowMillis = nowMillis,
                                modifier = accessibility
                            )
                        }
                        if (selecting) SelectionMark(checked, Modifier.align(Alignment.TopStart).padding(8.dp))
                    }
                }
            }
            Box(Modifier.animateItem()) { item() }
        }
    }
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

/** [PaddingValues] の下から [amount] を引く(0 未満にはしない) */
@Composable
private fun PaddingValues.withoutBottom(amount: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
        bottom = (calculateBottomPadding() - amount).coerceAtLeast(0.dp)
    )
}
