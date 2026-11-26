package com.unchunks.echomark.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unchunks.components.composeui.ChatMarkdown
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.websearch.WebSource
import com.unchunks.echomark.ui.navigation.WideContentMaxWidth
import com.unchunks.echomark.ui.design.EchoMarkSmallFab

/** 上へスクロールしているときに出す「最新へ」ボタン。 */
@Composable
internal fun ScrollToLatestButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        EchoMarkSmallFab(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = "最新のメッセージへ")
        }
    }
}

@Composable
internal fun ChatMessageList(
    uiState: ChatUiState,
    listState: LazyListState,
    onRetry: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEditLast: (String) -> Unit,
    onFollowUp: (String) -> Unit,
    onBranch: (ChatMessage) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onSaveNote: (ChatMessage) -> Unit,
    onToggleSpeaking: (ChatMessage) -> Unit,
    onOpenWebSource: (String) -> Unit,
    onSaveWebSource: (WebSource) -> Unit,
    onFactCheck: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    // 生成中でなければ、最後の質問は編集でき、最後が回答なら作り直せる(失敗の表示中は再試行のボタンを使う)
    val lastUserId = uiState.messages.lastOrNull { it.role == ChatRole.USER }?.id.takeIf { !uiState.isSending }
    val regeneratableId = uiState.messages.lastOrNull()
        ?.takeIf { it.role == ChatRole.ASSISTANT && !uiState.isSending && uiState.error == null }?.id
    // 途中の質問は、手前の発言を残して分け、質問を直して送れる(最初の質問は分けても何も残らないため出さない)
    val firstUserId = uiState.messages.firstOrNull { it.role == ChatRole.USER }?.id
    val idle = !uiState.isSending
    // 広い画面では左右の余白を広げて、メッセージを中央の読みやすい幅に収める(スクロールは幅全体で受ける)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = maxOf(16.dp, (maxWidth - WideContentMaxWidth) / 2 + 16.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(uiState.messages, key = { it.id }, contentType = { it.role }) { message ->
                if (message.role == ChatRole.USER) {
                    UserMessageItem(
                        text = message.content,
                        onCopy = { onCopy(message.content) },
                        onEdit = if (message.id == lastUserId) ({ onEditLast(message.content) }) else null,
                        onBranch = if (idle && message.id != firstUserId) ({ onBranch(message) }) else null
                    )
                } else {
                    AssistantMessageItem(
                        message = message,
                        referencedBookmarks = uiState.referencedBookmarks,
                        review = uiState.answerReviews[message.id],
                        onOpenBookmark = onOpenBookmark,
                        onCopy = { onCopy(ChatMarkdown.toPlainText(message.content)) },
                        onRegenerate = if (message.id == regeneratableId) onRegenerate else null,
                        onBranch = if (idle) ({ onBranch(message) }) else null,
                        onSaveNote = if (idle) ({ onSaveNote(message) }) else null,
                        onFactCheck = if (idle) ({ onFactCheck(message) }) else null,
                        isSpeaking = message.id == uiState.speakingMessageId,
                        onToggleSpeaking = { onToggleSpeaking(message) },
                        savedWebUrls = uiState.webSearch.savedUrls,
                        onOpenWebSource = onOpenWebSource,
                        onSaveWebSource = onSaveWebSource
                    )
                }
                val branches = uiState.branches.filter { it.branchedAt == message.createdAt }
                if (branches.isNotEmpty()) BranchLinks(branches = branches, onOpen = onOpenConversation)
            }
            // 生成中の回答。最初の文字が届くまでは進み具合を出す
            val streaming = uiState.streamingText
            if (uiState.isSending) {
                item(key = "streaming", contentType = "streaming") {
                    StreamingMessageItem(
                        text = streaming.orEmpty(),
                        pendingReferenceCount = uiState.pendingReferenceCount,
                        isSearchingWeb = uiState.isSearchingWeb,
                        pendingWebSourceCount = uiState.pendingWebSourceCount
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
            if (uiState.followUps.isNotEmpty() && !uiState.isSending && uiState.error == null) {
                item(key = "followUps", contentType = "followUps") {
                    FollowUpSuggestions(suggestions = uiState.followUps, onClick = onFollowUp)
                }
            }
        }
    }
}
