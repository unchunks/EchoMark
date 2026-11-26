package com.unchunks.echomark.ui.bookmark

import com.unchunks.echomark.ui.design.DesignTopBarSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.ui.design.EchoMarkTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListTopBar(
    sortOrder: BookmarkSortOrder,
    display: BookmarkListDisplay,
    scrollBehavior: TopAppBarScrollBehavior,
    onSearchClick: () -> Unit,
    onSortOrderChange: (BookmarkSortOrder) -> Unit,
    onDisplayChange: (BookmarkListDisplay) -> Unit,
    onOpenTagManagement: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenCollections: () -> Unit,
    onOpenBrokenLinks: () -> Unit,
    onOpenPodcasts: () -> Unit,
    canSelect: Boolean,
    onEnterSelection: () -> Unit
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var displayMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    EchoMarkTopAppBar(
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
                        text = { Text("選択") },
                        leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                        enabled = canSelect,
                        onClick = {
                            overflowOpen = false
                            onEnterSelection()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("資料を探す") },
                        leadingIcon = { Icon(Icons.Outlined.TravelExplore, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenDiscover()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("コレクション") },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenCollections()
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
                        text = { Text("リンク切れ") },
                        leadingIcon = { Icon(Icons.Outlined.LinkOff, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenBrokenLinks()
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
                    DropdownMenuItem(
                        text = { Text("ライブラリの分析") },
                        leadingIcon = { Icon(Icons.Outlined.Insights, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenInsights()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("読む・ふり返る") },
                        leadingIcon = { Icon(Icons.Outlined.AutoStories, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenReview()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("ポッドキャスト") },
                        leadingIcon = { Icon(Icons.Outlined.Podcasts, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onOpenPodcasts()
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
internal fun SearchTopBar(
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

    // 検索中の上の帯。地色と下端の区切りは、ふだんの上のバーと同じ
    DesignTopBarSurface {
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
