package com.unchunks.echomark.ui.chat

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.ui.common.formatRelativeTime
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.LoadingState

/**
 * 会話の一覧(チャットタブのトップ)。ViewModel をつなぐ薄いラッパー。見た目は [ConversationListContent]。
 * @param onOpenConversation 既存会話を開く
 * @param onNewConversation 新しい会話を始める(会話自体は初回送信時に作成される)
 * @param onOpenAiSettings AI が未設定のときの案内から AI 設定を開く
 */
@Composable
fun ConversationListScreen(
    onOpenConversation: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onOpenAiSettings: () -> Unit = {},
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 削除したら「元に戻す」を出す。閉じたら取り消し用の控えを捨てる
    val deleted = uiState.recentlyDeleted
    LaunchedEffect(deleted) {
        if (deleted == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "「${deleted.conversation.title}」を削除しました",
            actionLabel = "元に戻す",
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.clearRecentlyDeleted()
    }

    ConversationListContent(
        uiState = uiState,
        nowMillis = remember(uiState.conversations) { System.currentTimeMillis() },
        onOpenConversation = onOpenConversation,
        onNewConversation = onNewConversation,
        onOpenAiSettings = onOpenAiSettings,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        snackbarHostState = snackbarHostState
    )
}

/** 会話一覧の中身。状態とコールバックを受け取るだけなので、スクリーンショットテストで描画できる。 */
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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val isEmpty = !uiState.isLoading && uiState.conversations.isEmpty()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("チャット") }) },
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
                            nowMillis = nowMillis,
                            onOpen = { onOpenConversation(preview.conversation.id) },
                            onRename = { renameTargetId = preview.conversation.id },
                            onDelete = { onDelete(preview) },
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

/** 左右どちらへスワイプしても削除する(削除後は Snackbar で元に戻せる)。 */
@Composable
private fun SwipeToDeleteConversation(
    preview: ConversationPreview,
    nowMillis: Long,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState()
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
            nowMillis = nowMillis,
            onOpen = onOpen,
            onRename = onRename,
            onDelete = onDelete
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
    nowMillis: Long,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val conversation = preview.conversation
    val excerpt = conversationExcerpt(preview)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuExpanded = true },
                onLongClickLabel = "操作メニューを開く"
            )
            .semantics {
                customActions = listOf(
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
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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

/**
 * 一覧に出す最後のメッセージの抜粋。Markdown の記法を外して1行にまとめ、自分の発言には「あなた: 」を付ける。
 * メッセージが無ければ null。
 */
internal fun conversationExcerpt(preview: ConversationPreview, maxChars: Int = EXCERPT_MAX_CHARS): String? {
    val raw = preview.lastMessage?.takeIf { it.isNotBlank() } ?: return null
    val text = ChatMarkdown.toPlainText(raw).replace(Regex("\\s+"), " ").trim().take(maxChars)
    return if (preview.lastMessageRole == ChatRole.USER) "あなた: $text" else text
}

private const val EXCERPT_MAX_CHARS = 120
