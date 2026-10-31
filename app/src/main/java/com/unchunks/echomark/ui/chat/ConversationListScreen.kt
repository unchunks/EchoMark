package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ChatMarkdown
import com.unchunks.components.composeui.formatRelativeTime
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch

/**
 * 会話の一覧(チャットタブのトップ)。ViewModel をつなぐ薄いラッパー。見た目は [ConversationListContent]。
 * @param onOpenConversation 既存会話を開く
 * @param onNewConversation 新しい会話を始める(会話自体は初回送信時に作成される)
 * @param onOpenAiSettings AI が未設定のときの案内から AI 設定を開く
 * @param selectedConversationId 2 画面表示で右側に開いている会話(その行を強調する)
 * @param onConversationDeleted 一覧で会話を削除したとき(2 画面表示で、右側に開いているものなら右側を空にする)
 */
@Composable
fun ConversationListScreen(
    onOpenConversation: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onOpenAiSettings: () -> Unit = {},
    selectedConversationId: Long? = null,
    onConversationDeleted: (Long) -> Unit = {},
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 削除したら「元に戻す」を出す。閉じたら取り消し用の控えを捨てる。
    // 知らせは一度きり(画面を離れて戻っても、古い「元に戻す」を出し直さない)
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.deletedEvents.collect { deleted ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                val id = deleted.conversation.id
                val result = snackbarHostState.showSnackbar(
                    message = "「${deleted.conversation.title}」を削除しました",
                    actionLabel = "元に戻す",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(id) else viewModel.clearRecentlyDeleted(id)
            }
        }
    }

    ConversationListContent(
        uiState = uiState,
        nowMillis = remember(uiState.conversations) { System.currentTimeMillis() },
        onOpenConversation = onOpenConversation,
        onNewConversation = onNewConversation,
        onOpenAiSettings = onOpenAiSettings,
        onRename = viewModel::rename,
        onDelete = { preview ->
            viewModel.delete(preview)
            onConversationDeleted(preview.conversation.id)
        },
        snackbarHostState = snackbarHostState,
        selectedConversationId = selectedConversationId,
        onQueryChange = viewModel::onQueryChange,
        onSetPinned = viewModel::setPinned
    )
}

/**
 * 会話一覧の中身。状態とコールバックを受け取るだけなので、スクリーンショットテストで描画できる。
 *
 * @param selectedConversationId 2 画面表示で右側に開いている会話。その行を強調する
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListContent(
    uiState: ConversationListUiState,
    nowMillis: Long,
    onOpenConversation: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (ConversationPreview) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    selectedConversationId: Long? = null,
    onQueryChange: (String) -> Unit = {},
    onSetPinned: (Long, Boolean) -> Unit = { _, _ -> }
) {
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val isEmpty = !uiState.isLoading && !uiState.hasConversations && uiState.conversations.isEmpty()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("チャット", maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
        floatingActionButton = {
            if (!isEmpty && !uiState.isLoading) {
                ExtendedFloatingActionButton(
                    onClick = onNewConversation,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("新しいチャット") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                isEmpty -> {
                    AiSetupBanner(
                        state = uiState.aiSetup,
                        onOpenAiSettings = onOpenAiSettings,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    EmptyState(
                        icon = Icons.Outlined.Forum,
                        title = "まだチャットがありません",
                        // 中央寄せの説明は、意味の切れ目で改行して行の長さをそろえる
                        description = "保存したブックマークについて AI に質問できます\n" +
                            "要約・比較・振り返りなど、自分の知識と対話しましょう",
                        actionLabel = "チャットを始める",
                        onAction = onNewConversation,
                        actionIcon = Icons.Outlined.Add
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // FAB と最後の行が重ならないよう下に余白を取る
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "search") {
                        ConversationSearchField(query = uiState.query, onQueryChange = onQueryChange)
                    }
                    if (uiState.conversations.isEmpty()) {
                        item(key = "no_match") {
                            Text(
                                text = "「${uiState.query.trim()}」を含むチャットはありません",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
                            )
                        }
                    }
                    if (!uiState.aiSetup.isReady) {
                        item(key = "ai_setup") {
                            AiSetupBanner(
                                state = uiState.aiSetup,
                                onOpenAiSettings = onOpenAiSettings,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    items(uiState.conversations, key = { it.conversation.id }) { preview ->
                        SwipeToDeleteConversation(
                            preview = preview,
                            selected = preview.conversation.id == selectedConversationId,
                            nowMillis = nowMillis,
                            excerpt = uiState.matchedExcerpts[preview.conversation.id],
                            onOpen = { onOpenConversation(preview.conversation.id) },
                            onRename = { renameTargetId = preview.conversation.id },
                            onDelete = { onDelete(preview) },
                            onTogglePinned = { onSetPinned(preview.conversation.id, !preview.conversation.isPinned) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    val renameTarget = uiState.conversations.firstOrNull { it.conversation.id == renameTargetId }
    if (renameTarget != null) {
        RenameConversationDialog(
            initialTitle = renameTarget.conversation.title,
            onConfirm = { onRename(renameTarget.conversation.id, it); renameTargetId = null },
            onDismiss = { renameTargetId = null }
        )
    }
}

/** タイトルと発言を探す欄。 */
@Composable
private fun ConversationSearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("チャットを検索", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "検索語を消す")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 左右どちらへスワイプしても削除する(削除後は Snackbar で元に戻せる)。
 * @param excerpt 検索語を含む発言の抜粋。最後のメッセージの代わりに出す
 */
@Composable
private fun SwipeToDeleteConversation(
    preview: ConversationPreview,
    selected: Boolean,
    nowMillis: Long,
    excerpt: String?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    modifier: Modifier = Modifier
) {
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    // rememberSwipeToDismissBoxState は状態を保存するため、削除を取り消して同じ key の行が戻ると
    // スワイプ済み(画面外)のまま表示される。保存しない state にして、戻った行は元の位置から始める
    val dismissState = remember(preview.conversation.id) {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold)
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        onDismiss = { onDelete() },
        backgroundContent = {
            val direction = dismissState.dismissDirection
            // 静止中は描かない(角丸の縁から背景の色がにじんで見えるのを防ぐ)
            if (direction != SwipeToDismissBoxValue.Settled) DeleteSwipeBackground(
                alignEnd = direction == SwipeToDismissBoxValue.EndToStart
            )
        }
    ) {
        ConversationCard(
            preview = preview,
            selected = selected,
            nowMillis = nowMillis,
            matchedExcerpt = excerpt,
            onOpen = onOpen,
            onRename = onRename,
            onDelete = onDelete,
            onTogglePinned = onTogglePinned
        )
    }
}

@Composable
private fun DeleteSwipeBackground(alignEnd: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 24.dp),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    preview: ConversationPreview,
    selected: Boolean,
    nowMillis: Long,
    matchedExcerpt: String?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val conversation = preview.conversation
    val excerpt = matchedExcerpt ?: conversationExcerpt(preview)
    val pinLabel = if (conversation.isPinned) "固定を外す" else "一覧の先頭に固定"
    val aboutTitle = preview.aboutBookmarkTitle
    Surface(
        // 2 画面表示で右側に開いている会話は、枠線と背景色で強調する(アイコンの丸と同じ色にならないよう背景は少し濃くするだけ)
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuExpanded = true },
                onLongClickLabel = "操作メニューを開く"
            )
            .semantics {
                if (selected) this.selected = true
                customActions = listOf(
                    CustomAccessibilityAction(pinLabel) { onTogglePinned(); true },
                    CustomAccessibilityAction("名前を変更") { onRename(); true },
                    CustomAccessibilityAction("削除") { onDelete(); true }
                )
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    // 「このブックマークについて質問」の会話はブックマークのアイコンで見分けられるようにする
                    if (aboutTitle != null) Icons.Outlined.BookmarkBorder else Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.isPinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "固定中",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatRelativeTime(conversation.updatedAt, nowMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (aboutTitle != null) {
                    Spacer(Modifier.height(2.dp))
                    AboutBookmarkLabel(bookmarkTitle = aboutTitle)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = excerpt ?: "まだメッセージがありません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "「${conversation.title}」の操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(pinLabel) },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                        onClick = { menuExpanded = false; onTogglePinned() }
                    )
                    DropdownMenuItem(
                        text = { Text("名前を変更") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("削除") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

/** 会話カードに出す「〇〇について」の小さな表示(質問の対象のブックマーク)。 */
@Composable
private fun AboutBookmarkLabel(bookmarkTitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.BookmarkBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        // 長いタイトルを省略しても「について」は残るよう、2つに分けて描く
        Text(
            text = "「${bookmarkTitle.trim()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = "」について",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}

/**
 * 一覧に出す最後のメッセージの抜粋。Markdown の記法を外して1行にまとめ、自分の発言には「あなた: 」を付ける。
 * メッセージが無ければ null。
 */
internal fun conversationExcerpt(preview: ConversationPreview, maxChars: Int = EXCERPT_MAX_CHARS): String? {
    val raw = preview.lastMessage?.takeIf { it.isNotBlank() } ?: return null
    val text = ChatMarkdown.toPlainText(raw).replace(WHITESPACE, " ").trim().take(maxChars)
    return if (preview.lastMessageRole == ChatRole.USER) "あなた: $text" else text
}

/** 検索語の周りを切り出した発言の抜粋。語が見つからなければ冒頭。 */
internal fun conversationMatchExcerpt(message: String, term: String, maxChars: Int = EXCERPT_MAX_CHARS): String {
    val text = ChatMarkdown.toPlainText(message).replace(WHITESPACE, " ").trim()
    val at = text.indexOf(term, ignoreCase = true)
    if (at <= EXCERPT_LEAD_CHARS) return text.take(maxChars)
    return "…" + text.substring(at - EXCERPT_LEAD_CHARS).take(maxChars)
}

private const val EXCERPT_MAX_CHARS = 120

/** 検索語の前に残す文字数 */
private const val EXCERPT_LEAD_CHARS = 16

/** 改行・連続する空白(1つの空白にまとめる) */
private val WHITESPACE = Regex("\\s+")
