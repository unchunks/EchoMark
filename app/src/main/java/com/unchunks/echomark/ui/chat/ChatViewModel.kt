package com.unchunks.echomark.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.MODEL_NOT_AVAILABLE_USER_MESSAGE
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    aiSetupRepository: AiSetupRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 既存会話は "chat/{conversationId}" の引数で渡る。"chat/new" では未設定(null)で、
    // 最初の送信時に作成した ID を SavedStateHandle に保存する(プロセス破棄後も同じ会話に戻れる)
    private val conversationIdFlow: StateFlow<Long?> =
        savedStateHandle.getStateFlow<Long?>(ARG_CONVERSATION_ID, null)

    /** 「このブックマークについて質問」の対象("chat/new/about/{id}" の引数)。 */
    private val aboutBookmarkId: Long? = savedStateHandle.get<Long>(Routes.ARG_ABOUT_BOOKMARK_ID)

    private val isSendingFlow = MutableStateFlow(false)
    private val streamingTextFlow = MutableStateFlow<String?>(null)
    private val pendingReferenceCountFlow = MutableStateFlow<Int?>(null)
    private val errorFlow = MutableStateFlow<ChatError?>(null)

    /** 生成中の送信処理。停止ボタンでキャンセルする。 */
    private var sendJob: Job? = null

    /** 対象のブックマーク(削除済みなら null)。 */
    private val aboutBookmarkFlow: Flow<Bookmark?> =
        if (aboutBookmarkId == null) flowOf(null)
        else flow { emit(repository.getBookmarks(listOf(aboutBookmarkId))[aboutBookmarkId]) }

    private val suggestionsFlow: Flow<List<String>> =
        if (aboutBookmarkId != null) flowOf(ChatSuggestions.forBookmark)
        else flow {
            emit(ChatSuggestions.forLibrary(emptyList()))
            val recent = try {
                repository.getRecentBookmarks(SUGGESTION_SOURCE_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "質問の例の元になるブックマークを取得できなかった")
                emptyList()
            }
            emit(ChatSuggestions.forLibrary(recent))
        }

    private val conversationFlow: Flow<Conversation?> = conversationIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.observeConversation(id)
    }

    /** メッセージ本文と引用カード用のブックマーク。読み込み中は null。 */
    private val messagesWithReferences: Flow<Pair<List<ChatMessage>, Map<Long, Bookmark>>?> =
        conversationIdFlow.flatMapLatest { id ->
            if (id == null) flowOf(emptyList<ChatMessage>())
            else repository.observeMessages(id)
        }.map<List<ChatMessage>, Pair<List<ChatMessage>, Map<Long, Bookmark>>?> { messages ->
            val ids = messages.flatMap { it.referencedBookmarkIds }.distinct()
            messages to repository.getBookmarks(ids)
        }.onStart { if (conversationIdFlow.value != null) emit(null) }

    private val sendingState = combine(
        isSendingFlow,
        streamingTextFlow,
        pendingReferenceCountFlow,
        errorFlow
    ) { sending, streaming, pendingCount, error -> SendingState(sending, streaming, pendingCount, error) }

    private val contextState = combine(
        conversationFlow,
        aboutBookmarkFlow,
        suggestionsFlow,
        aiSetupRepository.setupState
    ) { conversation, about, suggestions, setup -> ContextState(conversation, about, suggestions, setup) }

    val uiState: StateFlow<ChatUiState> = combine(
        messagesWithReferences,
        sendingState,
        contextState
    ) { messagesAndRefs, sending, context ->
        ChatUiState(
            title = context.conversation?.title,
            hasConversation = context.conversation != null,
            isLoading = messagesAndRefs == null,
            messages = messagesAndRefs?.first.orEmpty(),
            referencedBookmarks = messagesAndRefs?.second.orEmpty(),
            aboutBookmark = context.aboutBookmark,
            suggestions = context.suggestions,
            isSending = sending.isSending,
            streamingText = sending.streamingText,
            pendingReferenceCount = sending.pendingReferenceCount,
            error = sending.error,
            aiSetup = context.aiSetup
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatUiState(isLoading = conversationIdFlow.value != null)
    )

    fun sendMessage(text: String) {
        send(text.trim(), isRetry = false)
    }

    /** 失敗した最後の質問を送り直す(保存済みのユーザー発言は使い回す)。 */
    fun retry() {
        val failed = errorFlow.value?.failedMessage ?: return
        send(failed, isRetry = true)
    }

    private fun send(text: String, isRetry: Boolean) {
        if (text.isBlank() || isSendingFlow.value) return
        isSendingFlow.value = true
        errorFlow.value = null
        pendingReferenceCountFlow.value = null
        sendJob = viewModelScope.launch {
            try {
                // 会話は最初の送信時に遅延作成する(空の会話を増やさない)。
                // ブックマークについての質問なら、そのブックマーク名を既定のタイトルにする
                val id = conversationIdFlow.value
                    ?: repository.createConversation(defaultTitle())
                        .also { savedStateHandle[ARG_CONVERSATION_ID] = it }
                streamingTextFlow.value = ""
                repository.sendMessageStream(id, text, aboutBookmarkId, isRetry).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Started ->
                            pendingReferenceCountFlow.value = event.referencedBookmarkIds.size
                        is ChatStreamEvent.Delta -> streamingTextFlow.value = event.textSoFar
                        is ChatStreamEvent.Completed -> streamingTextFlow.value = null
                        is ChatStreamEvent.Failed -> {
                            Timber.w(event.error, "sendMessageStream failed")
                            errorFlow.value = event.error.toChatError(text)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "sendMessage failed")
                errorFlow.value = e.toChatError(text)
            } finally {
                streamingTextFlow.value = null
                pendingReferenceCountFlow.value = null
                isSendingFlow.value = false
            }
        }
    }

    private suspend fun defaultTitle(): String? {
        val id = aboutBookmarkId ?: return null
        val bookmark = repository.getBookmarks(listOf(id))[id] ?: return null
        return RagSupport.aboutBookmarkTitle(bookmark.title)
    }

    /** 生成を止める。途中までの回答は「(停止)」付きで保存される。 */
    fun stopGenerating() {
        sendJob?.cancel()
    }

    /** エラー表示を閉じる。 */
    fun dismissError() {
        errorFlow.value = null
    }

    fun rename(title: String) {
        val id = conversationIdFlow.value ?: return
        if (title.isBlank()) return
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    /** 会話を削除する。完了後に [onDeleted] を呼ぶ(画面を閉じる)。 */
    fun deleteConversation(onDeleted: () -> Unit) {
        val id = conversationIdFlow.value ?: return
        sendJob?.cancel()
        viewModelScope.launch {
            repository.deleteConversation(id)
            onDeleted()
        }
    }

    private fun Throwable.toChatError(failedMessage: String) = ChatError(
        message = toLlmUserMessage(SEND_FAILED_PREFIX),
        failedMessage = failedMessage,
        needsAiSettings = needsAiSettings()
    )

    private class SendingState(
        val isSending: Boolean,
        val streamingText: String?,
        val pendingReferenceCount: Int?,
        val error: ChatError?
    )

    private class ContextState(
        val conversation: Conversation?,
        val aboutBookmark: Bookmark?,
        val suggestions: List<String>,
        val aiSetup: AiSetupState
    )

    companion object {
        /** ナビゲーション引数(と SavedStateHandle)のキー。 */
        const val ARG_CONVERSATION_ID = "conversationId"

        const val MODEL_NOT_AVAILABLE_MESSAGE = MODEL_NOT_AVAILABLE_USER_MESSAGE

        private const val SEND_FAILED_PREFIX = "送信に失敗しました"

        /** 質問の例を作るときに見る、最近のブックマークの件数。 */
        private const val SUGGESTION_SOURCE_LIMIT = 50
    }
}

/** AI 設定を見直せば直る失敗か(キー未設定・無効、モデル未取り込み、モデル ID の誤りなど)。 */
internal fun Throwable.needsAiSettings(): Boolean = when (this) {
    is ModelNotAvailableException,
    is LlmException.ApiKeyMissing,
    is LlmException.InvalidApiKey,
    is LlmException.BadRequest -> true
    else -> false
}
