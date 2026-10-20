package com.unchunks.echomark.ui.bookmark

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.common.formatRelativeTime
import com.unchunks.echomark.ui.common.shareBookmark
import com.unchunks.echomark.ui.common.shortTitle
import com.unchunks.echomark.ui.components.BookmarkCard
import com.unchunks.echomark.ui.components.CompactBookmarkCard
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch

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
            onOpenBookmark = onOpenBookmark,
            onSearchActiveChange = viewModel::onSearchActiveChange,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onFilterChange = viewModel::onFilterChange,
            onSortOrderChange = viewModel::onSortOrderChange,
            onTagSelected = viewModel::onTagSelected,
            onToggleFavorite = viewModel::toggleFavorite,
            onSetArchived = setArchived,
            onDelete = delete,
            onLongPress = { actionTarget = it },
            onAddClick = { showAddSheet = true },
            onOpenTagManagement = onOpenTagManagement,
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
            onToggleArchive = { setArchived(target, !target.isArchived) },
            onShare = { shareBookmark(context, target) },
            onDelete = { delete(target) }
        )
    }
}

/** 一覧画面の操作。状態を持たない [BookmarkListContent] に渡す */
class BookmarkListCallbacks(
    val onOpenBookmark: (Long) -> Unit = {},
    val onSearchActiveChange: (Boolean) -> Unit = {},
    val onSearchQueryChange: (String) -> Unit = {},
    val onFilterChange: (BookmarkFilter) -> Unit = {},
    val onSortOrderChange: (BookmarkSortOrder) -> Unit = {},
    val onTagSelected: (Long?) -> Unit = {},
    val onToggleFavorite: (Bookmark) -> Unit = {},
    val onSetArchived: (Bookmark, Boolean) -> Unit = { _, _ -> },
    val onDelete: (Bookmark) -> Unit = {},
    val onLongPress: (Bookmark) -> Unit = {},
    val onAddClick: () -> Unit = {},
    val onOpenTagManagement: () -> Unit = {},
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

    // 絞り込み・検索の切り替えや空状態への切り替えで、スクロールで隠れたトップバーを戻す
    // (空状態はスクロールできないことが多く、そのままだとトップバーが隠れたままになる)
    val showingList = !uiState.isLoading && uiState.errorMessage == null && uiState.emptyKind == ListEmptyKind.NONE
    LaunchedEffect(uiState.filter, uiState.selectedTagId, uiState.isSearchActive, showingList) {
        scrollBehavior.state.heightOffset = 0f
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (uiState.isSearchActive) {
                SearchTopBar(
                    query = uiState.searchQuery,
                    onQueryChange = callbacks.onSearchQueryChange,
                    onClose = { callbacks.onSearchActiveChange(false) }
                )
            } else {
                ListTopBar(
                    sortOrder = uiState.sortOrder,
                    scrollBehavior = scrollBehavior,
                    onSearchClick = { callbacks.onSearchActiveChange(true) },
                    onSortOrderChange = callbacks.onSortOrderChange,
                    onOpenTagManagement = callbacks.onOpenTagManagement
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
            FilterBar(
                filter = uiState.filter,
                tags = uiState.allTags,
                selectedTagId = uiState.selectedTagId,
                onFilterChange = callbacks.onFilterChange,
                onTagSelected = callbacks.onTagSelected
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
        columns = StaggeredGridCells.Adaptive(BOOKMARK_CARD_MIN_WIDTH),
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
        staggeredItems(uiState.bookmarks, key = { it.id }, contentType = { "bookmark" }) { bookmark ->
            val onArchiveToggle = { callbacks.onSetArchived(bookmark, !bookmark.isArchived) }
            val onDelete = { callbacks.onDelete(bookmark) }
            val archiveLabel = if (bookmark.isArchived) "アーカイブから戻す" else "アーカイブ"
            SwipeableBookmarkItem(
                bookmark = bookmark,
                onArchiveToggle = onArchiveToggle,
                onDelete = onDelete,
                modifier = Modifier.animateItem()
            ) {
                Column {
                    if (bookmark.id in uiState.semanticMatchIds) SemanticMatchLabel()
                    BookmarkCard(
                        bookmark = bookmark,
                        selected = bookmark.id == selectedBookmarkId,
                        onClick = { callbacks.onOpenBookmark(bookmark.id) },
                        onToggleFavorite = { callbacks.onToggleFavorite(bookmark) },
                        onLongClick = { callbacks.onLongPress(bookmark) },
                        onLongClickLabel = "操作メニューを開く",
                        nowMillis = nowMillis,
                        // スワイプできない人向けに、同じ操作を TalkBack のカスタムアクションにも出す。
                        // TalkBack がフォーカスするのはカード(クリックできるノード)なので、カードに付ける
                        modifier = Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(archiveLabel) { onArchiveToggle(); true },
                                CustomAccessibilityAction("削除") { onDelete(); true }
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListTopBar(
    sortOrder: BookmarkSortOrder,
    scrollBehavior: TopAppBarScrollBehavior,
    onSearchClick: () -> Unit,
    onSortOrderChange: (BookmarkSortOrder) -> Unit,
    onOpenTagManagement: () -> Unit
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
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
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "その他のメニュー")
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("タグを管理") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenTagManagement()
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
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

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
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text("キーワードや内容で検索", maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
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
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
            )
        }
    }
}

/** 絞り込み(すべて/お気に入り/アーカイブ)とタグのチップを横スクロールで並べる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    filter: BookmarkFilter,
    tags: List<Tag>,
    selectedTagId: Long?,
    onFilterChange: (BookmarkFilter) -> Unit,
    onTagSelected: (Long?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
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
        if (tags.isNotEmpty()) {
            item(key = "divider") {
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            }
        }
        items(tags, key = { "tag_${it.id}" }) { tag ->
            val selected = tag.id == selectedTagId
            FilterChip(
                selected = selected,
                onClick = { onTagSelected(if (selected) null else tag.id) },
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
        ListEmptyKind.NO_TAG_MATCH -> EmptyState(
            icon = Icons.AutoMirrored.Outlined.Label,
            title = "当てはまるブックマークがありません",
            description = uiState.selectedTag?.let { "「#${it.name}」のブックマークは、この絞り込みにはありません。" },
            actionLabel = "タグの絞り込みを解除",
            onAction = { callbacks.onTagSelected(null) }
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
