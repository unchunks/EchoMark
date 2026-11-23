package com.unchunks.echomark.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.MarkdownText
import com.unchunks.echomark.domain.help.AppAction
import com.unchunks.echomark.ui.design.EchoMarkTonalButton
import com.unchunks.echomark.ui.design.EchoMarkOutlinedButton
import com.unchunks.echomark.ui.design.EchoMarkSuggestionChip
import com.unchunks.echomark.ui.design.EchoMarkTopAppBar
import com.unchunks.echomark.ui.design.AccentCard
import com.unchunks.echomark.ui.design.designFieldStyle
import com.unchunks.echomark.ui.design.userBubbleStyle
import com.unchunks.echomark.ui.design.DesignBottomBar

/** 使い方を AI に聞く画面。状態は [AppHelpViewModel]、表示は [AppHelpContent]。 */
@Composable
fun AppHelpScreen(
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onNavigate: (AppAction) -> Unit,
    viewModel: AppHelpViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { currentOnNavigate(it) }
    }
    AppHelpContent(
        uiState = uiState,
        actions = AppHelpActions(
            onBack = onBack,
            onInputChange = viewModel::onInputChange,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onSuggestion = viewModel::askSuggestion,
            onOpenAiSettings = onOpenAiSettings,
            onDismissError = viewModel::dismissError,
            onRunAction = viewModel::runAction
        )
    )
}

/** [AppHelpContent] の操作 */
data class AppHelpActions(
    val onBack: () -> Unit = {},
    val onInputChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSuggestion: (String) -> Unit = {},
    val onOpenAiSettings: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** 回答(id)に出した操作のボタンを押した */
    val onRunAction: (Long, AppAction) -> Unit = { _, _ -> }
)

/** 使い方を AI に聞く画面の本体(状態を受け取って描くだけ) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHelpContent(
    uiState: AppHelpUiState,
    actions: AppHelpActions,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // 新しい質問・回答の続きが来たら、最後までスクロールする
    val last = uiState.messages.lastOrNull()
    LaunchedEffect(uiState.messages.size, last?.text?.length, last?.actions?.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            EchoMarkTopAppBar(
                title = { Text("使い方を AI に聞く") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        bottomBar = {
            HelpInputBar(
                value = uiState.input,
                answering = uiState.answering,
                canSend = uiState.canSend,
                onValueChange = actions.onInputChange,
                onSend = actions.onSend,
                onStop = actions.onStop,
                modifier = Modifier.imePadding()
            )
        }
    ) { padding ->
        if (uiState.messages.isEmpty() && uiState.error == null) {
            HelpIntro(
                suggestions = uiState.suggestions,
                onSuggestion = actions.onSuggestion,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            )
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                when {
                    message.fromUser -> QuestionBubble(message.text)
                    message.isResult -> ResultNotice(message.text)
                    else -> AnswerItem(
                        message = message,
                        answering = uiState.answering,
                        actionsEnabled = !uiState.runningAction,
                        onRunAction = { action -> actions.onRunAction(message.id, action) }
                    )
                }
            }
            uiState.error?.let { error ->
                item("error") { HelpErrorCard(error, actions.onOpenAiSettings, actions.onDismissError) }
            }
        }
    }
}

/** まだ何も聞いていないときの説明と、質問の例 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpIntro(suggestions: List<String>, onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text("アプリの使い方を質問できます", style = MaterialTheme.typography.titleMedium)
        Text(
            "端末内の AI が、アプリの案内をもとに答えます。「ダークモードにして」のように操作を頼むと、実行のボタンを出します。質問も回答も端末の外には送りません。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth()
        ) {
            suggestions.forEach { question ->
                EchoMarkSuggestionChip(onClick = { onSuggestion(question) }, label = { Text(question) })
            }
        }
    }
}

@Composable
private fun QuestionBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        // 会話の画面と同じ、自分の発言の吹き出し(色・形・影はデザインに従う)
        val bubble = userBubbleStyle(classicShape = RoundedCornerShape(20.dp))
        Surface(
            color = bubble.color,
            shape = bubble.shape,
            border = bubble.border,
            modifier = Modifier.widthIn(max = 320.dp).then(bubble.depth)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = bubble.contentColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

/** 回答。作っている途中で、まだ何も出ていなければ読み込み中を出す。下に操作のボタンを並べる */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerItem(
    message: AppHelpMessage,
    answering: Boolean,
    actionsEnabled: Boolean,
    onRunAction: (AppAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (message.text.isBlank() && answering) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "回答を作っています…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (message.text.isNotBlank()) {
            MarkdownText(
                markdown = message.text,
                isCitationAvailable = { false },
                onCitationClick = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (message.actions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message.actions.forEach { action ->
                    ActionButton(action = action, enabled = actionsEnabled, onClick = { onRunAction(action) })
                }
            }
        }
        if (message.sourceTitles.isNotEmpty()) {
            Text(
                "参考にした案内: " + message.sourceTitles.joinToString("・"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 操作のボタン。画面を開くものは控えめに、保存・切り替えは塗りのボタンにする(押すと実際に変わることが分かるように)。
 */
@Composable
private fun ActionButton(action: AppAction, enabled: Boolean, onClick: () -> Unit) {
    val opensScreen = action is AppAction.Open || action is AppAction.Search
    val icon = if (opensScreen) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Outlined.Bolt
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(action.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (opensScreen) {
        EchoMarkOutlinedButton(onClick = onClick, enabled = enabled, content = content)
    } else {
        EchoMarkTonalButton(onClick = onClick, enabled = enabled, content = content)
    }
}

/** 操作を実行した結果の知らせ */
@Composable
private fun ResultNotice(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HelpErrorCard(error: AppHelpError, onOpenAiSettings: () -> Unit, onDismiss: () -> Unit) {
    AccentCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error.message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("閉じる") }
                if (error.needsAiSettings) {
                    TextButton(onClick = onOpenAiSettings) { Text("AI の設定を開く") }
                }
            }
        }
    }
}

@Composable
private fun HelpInputBar(
    value: String,
    answering: Boolean,
    canSend: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 入力のバー。地色・上端の区切りは、ほかの下の操作のバーと同じ
    DesignBottomBar(modifier = modifier.fillMaxWidth(), classic = MaterialTheme.colorScheme.surface, classicTonalElevation = 0.dp) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 会話の画面と同じ入力欄(形・地色・ソフトのくぼみはデザインに従う)
            val field = designFieldStyle(classicContainer = MaterialTheme.colorScheme.surfaceContainerHigh)
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).then(field.depth),
                placeholder = { Text("使い方について質問…", maxLines = 1) },
                shape = field.shape,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = field.containerColor,
                    unfocusedContainerColor = field.containerColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(8.dp))
            if (answering) {
                FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "回答を止める")
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "送信")
                }
            }
        }
    }
}
