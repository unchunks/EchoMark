package com.unchunks.echomark.ui.chat

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch

/**
 * 1つの会話の画面(ViewModel をつなぐ薄いラッパー。見た目は [ChatContent])。
 * @param onBack 会話一覧へ戻る
 * @param onOpenBookmark 引用・参照カード(ブックマーク)タップ時に、そのIDの詳細を開く
 * @param onOpenAiSettings AI 未設定・キー無効などのときに AI 設定を開く
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit = {},
    onOpenBookmark: (Long) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    ChatContent(
        uiState = uiState,
        input = input,
        onInputChange = { input = it },
        onSend = { text ->
            viewModel.sendMessage(text)
            input = ""
        },
        onStop = viewModel::stopGenerating,
        onRetry = viewModel::retry,
        onBack = onBack,
        onOpenBookmark = onOpenBookmark,
        onOpenAiSettings = onOpenAiSettings,
        onRename = viewModel::rename,
        onDelete = { viewModel.deleteConversation(onDeleted = onBack) },
        onCopy = { text ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("EchoMark", text)))
                snackbarHostState.showSnackbar("コピーしました")
            }
        },
        snackbarHostState = snackbarHostState
    )
}

/**
 * 会話画面の中身。状態とコールバックを受け取るだけなので、スクリーンショットテストで描画できる。
 *
 * インセット: 親の Scaffold(EchoMarkNavHost)がシステムバーの分を付けて consumeWindowInsets 済みのため、
 * ここの Scaffold・TopAppBar は追加の余白を付けず、imePadding は「キーボード高 - 消費済みの分」だけを足す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    uiState: ChatUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    listState: LazyListState = rememberLazyListState()
) {
    val title = uiState.title ?: DEFAULT_CHAT_TITLE
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 自動スクロール: 最下部付近にいる間だけ新しい内容に追従する。
    // ユーザーが上(過去)へスクロールしたら追従をやめ、最下部に戻ったら再開する
    var autoFollow by remember { mutableStateOf(true) }
    val bottomThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            !listState.canScrollForward ||
                (last.index == info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + bottomThresholdPx)
        }
    }
    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 指を下へ動かす = 過去のメッセージ側へ戻る
                if (source == NestedScrollSource.UserInput && available.y > 0f) autoFollow = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom }.collect { if (it) autoFollow = true }
    }
    LaunchedEffect(uiState.messages.size, uiState.streamingText?.length, uiState.error, uiState.isSending) {
        if (autoFollow) listState.scrollToBottom()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ChatTopBar(
                title = title,
                canEdit = uiState.hasConversation,
                onBack = onBack,
                onRename = { showRename = true },
                onDelete = { showDelete = true }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            val about = uiState.aboutBookmark
            if (about != null || !uiState.aiSetup.isReady) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiSetupBanner(state = uiState.aiSetup, onOpenAiSettings = onOpenAiSettings, compact = true)
                    if (about != null) AboutBookmarkBar(bookmark = about, onClick = { onOpenBookmark(about.id) })
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    uiState.isLoading -> LoadingState(message = "会話を読み込んでいます…")
                    uiState.isEmptyConversation -> ChatWelcome(
                        isAboutBookmark = uiState.aboutBookmark != null,
                        suggestions = uiState.suggestions,
                        onSuggestionClick = { suggestion ->
                            autoFollow = true
                            onSend(suggestion)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                    )
                    else -> ChatMessageList(
                        uiState = uiState,
                        listState = listState,
                        onRetry = {
                            autoFollow = true
                            onRetry()
                        },
                        onOpenBookmark = onOpenBookmark,
                        onOpenAiSettings = onOpenAiSettings,
                        onCopy = onCopy,
                        modifier = Modifier.nestedScroll(userScrollConnection)
                    )
                }

                ScrollToLatestButton(
                    visible = !isAtBottom && !uiState.isEmptyConversation && !uiState.isLoading,
                    onClick = {
                        autoFollow = true
                        scope.launch { listState.scrollToBottom(animate = true) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                )
            }

            ChatInputBar(
                value = input,
                onValueChange = onInputChange,
                isSending = uiState.isSending,
                onSend = {
                    autoFollow = true
                    onSend(input)
                },
                onStop = onStop
            )
        }
    }

    if (showRename) {
        RenameConversationDialog(
            initialTitle = title,
            onConfirm = { onRename(it); showRename = false },
            onDismiss = { showRename = false }
        )
    }
    if (showDelete) {
        DeleteConversationDialog(
            title = title,
            onConfirm = { showDelete = false; onDelete() },
            onDismiss = { showDelete = false }
        )
    }
}

/** 最後の項目の末尾までスクロールする(大きなオフセットを渡すと末尾で止まる)。 */
private suspend fun LazyListState.scrollToBottom(animate: Boolean = false) {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (animate) animateScrollToItem(lastIndex)
    scrollToItem(lastIndex, scrollOffset = BOTTOM_SCROLL_OFFSET)
}

/** 上へスクロールしているときに出す「最新へ」ボタン。 */
@Composable
private fun ScrollToLatestButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = "最新のメッセージへ")
        }
    }
}

private const val BOTTOM_SCROLL_OFFSET = 100_000
internal const val DEFAULT_CHAT_TITLE = "新しいチャット"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    canEdit: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (canEdit) Modifier.clickable(onClickLabel = "名前を変更", onClick = onRename) else Modifier
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
            }
        },
        actions = {
            if (canEdit) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "その他の操作")
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
    )
}

@Composable
private fun ChatMessageList(
    uiState: ChatUiState,
    listState: LazyListState,
    onRetry: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(uiState.messages, key = { it.id }, contentType = { it.role }) { message ->
            if (message.role == ChatRole.USER) {
                UserMessageItem(text = message.content, onCopy = { onCopy(message.content) })
            } else {
                AssistantMessageItem(
                    message = message,
                    referencedBookmarks = uiState.referencedBookmarks,
                    onOpenBookmark = onOpenBookmark,
                    onCopy = { onCopy(ChatMarkdown.toPlainText(message.content)) }
                )
            }
        }
        // 生成中の回答。最初の文字が届くまでは進み具合を出す
        val streaming = uiState.streamingText
        if (uiState.isSending) {
            item(key = "streaming", contentType = "streaming") {
                StreamingMessageItem(
                    text = streaming.orEmpty(),
                    pendingReferenceCount = uiState.pendingReferenceCount
                )
            }
        }
        uiState.error?.let { error ->
            item(key = "error", contentType = "error") {
                ChatErrorItem(
                    error = error,
                    onRetry = onRetry,
                    onOpenAiSettings = onOpenAiSettings,
                    // 上部に AI 未設定の案内が出ているときは、同じボタンを重ねて出さない
                    showAiSettingsButton = error.needsAiSettings && uiState.aiSetup.isReady
                )
            }
        }
    }
}

/**
 * 入力欄。複数行の丸い入力欄と、送信ボタン(空なら無効)。生成中は送信ボタンが停止ボタンに変わる。
 * 生成中も次の質問を打ち始められるよう、入力欄自体は無効にしない。
 */
@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("保存したブックマークについて質問…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = RoundedCornerShape(28.dp),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(8.dp))
            // テキスト欄(最小 56dp)の下端にそろえ、48dp のタップ領域を確保する
            Box(modifier = Modifier.padding(bottom = 4.dp)) {
                if (isSending) {
                    FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = "生成を停止")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = value.isNotBlank(),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "送信")
                    }
                }
            }
        }
    }
}
