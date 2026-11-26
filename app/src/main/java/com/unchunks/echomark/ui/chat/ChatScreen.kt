package com.unchunks.echomark.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import com.unchunks.components.composeui.ChatMarkdown
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.repository.ChatModelOption
import com.unchunks.echomark.domain.websearch.WebSource
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.factcheck.FactCheckTarget
import com.unchunks.echomark.ui.factcheck.FactCheckSheet
import com.unchunks.echomark.ui.factcheck.FactCheckViewModel
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.navigation.centeredMaxWidth
import kotlinx.coroutines.launch

/**
 * 1つの会話の画面(ViewModel をつなぐ薄いラッパー。見た目は [ChatContent])。
 * @param onBack 会話一覧へ戻る
 * @param onOpenBookmark 引用・参照カード(ブックマーク)タップ時に、そのIDの詳細を開く
 * @param onOpenAiSettings AI 未設定・キー無効などのときに AI 設定を開く
 * @param showBackButton トップバーに戻るボタンを出すか。2 画面表示の右側では出さない
 *   ([onBack] は会話を削除したあと、右側を空にするのに使われる)
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit = {},
    onOpenBookmark: (Long) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    showBackButton: Boolean = true,
    viewModel: ChatViewModel = hiltViewModel(),
    factCheckViewModel: FactCheckViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val factCheck by factCheckViewModel.uiState.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.attachFile(it.toString()) }
    }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // マイクの許可は、マイクのボタンを押したときにだけ求める
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoiceInput() else viewModel.onMicrophoneDenied()
    }
    // 画面が見えなくなったらマイクと読み上げを止める
    LifecycleStartEffect(viewModel) {
        onStopOrDispose { viewModel.onScreenHidden() }
    }
    var showScope by rememberSaveable { mutableStateOf(false) }
    var showTemplates by rememberSaveable { mutableStateOf(false) }

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
        onRegenerate = viewModel::regenerate,
        onEditLastMessage = viewModel::editLastMessage,
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
        snackbarHostState = snackbarHostState,
        showBackButton = showBackButton,
        onAttachClick = { pickFile.launch(arrayOf("*/*")) },
        onRemoveAttachment = viewModel::removeAttachment,
        onSelectModel = viewModel::selectModel,
        onScopeClick = { showScope = true },
        onTemplateClick = { showTemplates = true },
        onResumePrevious = viewModel::resumeAboutConversation,
        onVoiceClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.startVoiceInput() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        },
        onFinishVoice = viewModel::finishVoiceInput,
        onCancelVoice = viewModel::cancelVoiceInput,
        onToggleSpeaking = viewModel::toggleSpeaking,
        onBranch = { message -> viewModel.branchFrom(message) { input = it } },
        onOpenConversation = viewModel::openConversation,
        onSaveNote = { message ->
            viewModel.saveAsNote(message) { bookmarkId ->
                scope.launch {
                    val result = snackbarHostState.showSnackbar("メモに保存しました", actionLabel = "開く")
                    if (result == SnackbarResult.ActionPerformed) onOpenBookmark(bookmarkId)
                }
            }
        },
        onWebSearchChange = viewModel::setWebSearchEnabled,
        onDismissWebSearchKeyPrompt = viewModel::dismissWebSearchKeyPrompt,
        onOpenWebSource = { url ->
            // ブラウザが無いなどで開けなくても、画面は落とさない
            runCatching { uriHandler.openUri(url) }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("ページを開けませんでした") }
            }
        },
        onSaveWebSource = { source ->
            viewModel.saveWebSource(source) { result ->
                scope.launch {
                    val message = if (result.isDuplicate) "すでに保存しています" else "ブックマークに保存しました"
                    val action = snackbarHostState.showSnackbar(message, actionLabel = "開く")
                    if (action == SnackbarResult.ActionPerformed) onOpenBookmark(result.id)
                }
            }
        },
        onFactCheck = { message ->
            // 引用番号・Markdown の記号を除いた回答を確かめる(回答は書き換えない)
            val text = ChatMarkdown.toPlainText(RagSupport.withoutCitations(message).content)
            factCheckViewModel.open(FactCheckTarget.chatAnswer(message.id), text)
        }
    )

    FactCheckSheet(
        state = factCheck,
        onCheck = factCheckViewModel::check,
        onOpenUrl = { url ->
            runCatching { uriHandler.openUri(url) }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("ページを開けませんでした") }
            }
        },
        onOpenAiSettings = {
            factCheckViewModel.close()
            onOpenAiSettings()
        },
        onDismiss = factCheckViewModel::close
    )

    if (showScope) {
        val scope by viewModel.scope.collectAsState()
        val choices by viewModel.scopeChoices.collectAsState()
        val count by viewModel.scopeCount.collectAsState()
        ChatScopeSheet(
            query = scope,
            choices = choices,
            count = count,
            onChange = viewModel::setScope,
            onDismiss = { showScope = false }
        )
    }
    if (showTemplates) {
        ChatTemplateSheet(
            input = input,
            userTemplates = uiState.userTemplates,
            onPick = { template ->
                input = template.text
                showTemplates = false
            },
            onSaveInput = { viewModel.saveTemplate(input) },
            onDelete = viewModel::deleteTemplate,
            onDismiss = { showTemplates = false }
        )
    }
}

/**
 * 会話画面の中身。状態とコールバックを受け取るだけなので、スクリーンショットテストで描画できる。
 *
 * インセット: 親の Scaffold(EchoMarkAppScaffold)が左右と下(ナビゲーションバー)の分を付けて consumeWindowInsets 済み。
 * 上(ステータスバー)は TopAppBar が受け持ち、imePadding は「キーボード高 - 消費済みの分」だけを足す。
 *
 * 広い画面では、メッセージ・入力欄を中央の読みやすい幅に収める(1行が長くなりすぎないように)。
 * スクロールは画面の幅全体で受ける。
 *
 * @param showBackButton トップバーに戻るボタンを出すか(2 画面表示の右側では出さない)
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
    listState: LazyListState = rememberLazyListState(),
    showBackButton: Boolean = true,
    onRegenerate: () -> Unit = {},
    onEditLastMessage: (String) -> Unit = {},
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: () -> Unit = {},
    onSelectModel: (ChatModelOption) -> Unit = {},
    onScopeClick: () -> Unit = {},
    onTemplateClick: () -> Unit = {},
    onResumePrevious: () -> Unit = {},
    onBranch: (ChatMessage) -> Unit = {},
    onOpenConversation: (Long) -> Unit = {},
    onSaveNote: (ChatMessage) -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onFinishVoice: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
    onToggleSpeaking: (ChatMessage) -> Unit = {},
    onWebSearchChange: (Boolean) -> Unit = {},
    onDismissWebSearchKeyPrompt: () -> Unit = {},
    onOpenWebSource: (String) -> Unit = {},
    onSaveWebSource: (WebSource) -> Unit = {},
    onFactCheck: (ChatMessage) -> Unit = {}
) {
    val title = uiState.title ?: DEFAULT_CHAT_TITLE
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    // 編集中の最後の質問(編集していなければ null)
    var editingText by rememberSaveable { mutableStateOf<String?>(null) }
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
    LaunchedEffect(
        uiState.messages.size,
        uiState.streamingText?.length,
        uiState.error,
        uiState.isSending,
        uiState.answerReviews.size,
        uiState.followUps
    ) {
        if (autoFollow) listState.scrollToBottom()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ChatTopBar(
                title = title,
                canEdit = uiState.hasConversation,
                onBack = onBack.takeIf { showBackButton },
                onRename = { showRename = true },
                onDelete = { showDelete = true },
                modelPicker = {
                    ChatModelPicker(
                        choices = uiState.modelChoices,
                        enabled = !uiState.isSending,
                        onSelect = onSelectModel,
                        onOpenAiSettings = onOpenAiSettings
                    )
                }
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
            uiState.branchParent?.let { parent ->
                BranchParentBar(
                    parent = parent,
                    onOpen = { onOpenConversation(parent.id) },
                    modifier = Modifier.centeredMaxWidth().padding(horizontal = 8.dp)
                )
            }
            val about = uiState.aboutBookmark
            if (about != null || !uiState.aiSetup.isReady) {
                Column(
                    modifier = Modifier
                        .centeredMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
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
                        onResumePrevious = onResumePrevious.takeIf { uiState.previousAboutConversationId != null },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                            .centeredMaxWidth()
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
                        onRegenerate = {
                            autoFollow = true
                            onRegenerate()
                        },
                        onEditLast = { editingText = it },
                        onFollowUp = { question ->
                            autoFollow = true
                            onSend(question)
                        },
                        onBranch = onBranch,
                        onOpenConversation = onOpenConversation,
                        onSaveNote = onSaveNote,
                        onToggleSpeaking = onToggleSpeaking,
                        onOpenWebSource = onOpenWebSource,
                        onSaveWebSource = onSaveWebSource,
                        onFactCheck = onFactCheck,
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

            ChatAttachmentStatus(
                name = uiState.attachmentName,
                isAttaching = uiState.isAttaching,
                error = uiState.attachmentError,
                onRemove = onRemoveAttachment
            )
            uiState.scopeLabel?.takeIf { uiState.aboutBookmark == null }?.let { label ->
                ChatScopeStatus(label = label, onClick = onScopeClick)
            }
            ChatWebSearchStatus(state = uiState.webSearch, onTurnOff = { onWebSearchChange(false) })
            uiState.webSearch.notice?.let { ChatWebSearchNotice(it) }
            uiState.voiceError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.centeredMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)
                )
            }
            if (uiState.voiceState != ChatVoiceState.IDLE) {
                ChatVoiceBar(state = uiState.voiceState, onFinish = onFinishVoice, onCancel = onCancelVoice)
            } else ChatInputBar(
                value = input,
                onValueChange = onInputChange,
                isSending = uiState.isSending,
                onSend = {
                    autoFollow = true
                    onSend(input)
                },
                onStop = onStop,
                onVoiceClick = onVoiceClick,
                onAttachClick = onAttachClick,
                onTemplateClick = onTemplateClick,
                // 質問の対象のブックマークがあるときは、対象を根拠にするため範囲は選ばない
                onScopeClick = onScopeClick.takeIf { uiState.aboutBookmark == null },
                webSearchEnabled = uiState.webSearch.enabled,
                onWebSearchChange = onWebSearchChange
            )
        }
    }

    if (uiState.webSearch.showKeyPrompt) {
        WebSearchKeyPromptDialog(
            onOpenSettings = {
                onDismissWebSearchKeyPrompt()
                onOpenAiSettings()
            },
            onDismiss = onDismissWebSearchKeyPrompt
        )
    }

    if (showRename) {
        RenameConversationDialog(
            initialTitle = title,
            onConfirm = { onRename(it); showRename = false },
            onDismiss = { showRename = false }
        )
    }
    editingText?.let { initial ->
        EditMessageDialog(
            initialText = initial,
            onConfirm = { text ->
                editingText = null
                autoFollow = true
                onEditLastMessage(text)
            },
            onDismiss = { editingText = null }
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

private const val BOTTOM_SCROLL_OFFSET = 100_000

internal const val DEFAULT_CHAT_TITLE = "新しいチャット"
