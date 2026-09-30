package com.unchunks.echomark.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.MODEL_NOT_AVAILABLE_USER_MESSAGE
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 既存会話は "chat/{conversationId}" の引数で渡る。"chat/new" では未設定(null)で、
    // 最初の送信時に作成した ID を SavedStateHandle に保存する(プロセス破棄後も同じ会話に戻れる)
    private val conversationIdFlow: StateFlow<Long?> =
        savedStateHandle.getStateFlow<Long?>(ARG_CONVERSATION_ID, null)
    private val isSendingFlow = MutableStateFlow(false)
    private val streamingTextFlow = MutableStateFlow<String?>(null)
    private val errorMessageFlow = MutableStateFlow<String?>(null)

    /** 生成中の送信処理。停止ボタンでキャンセルする。 */
    private var sendJob: Job? = null

    // メッセージ本文と、引用チップ用のブックマークタイトルをまとめて解決する
    private val messagesWithTitles = conversationIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<ChatMessage>())
        else repository.observeMessages(id)
    }.map { messages ->
        val ids = messages.flatMap { it.referencedBookmarkIds }.distinct()
        messages to repository.getBookmarkTitles(ids)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        messagesWithTitles,
        isSendingFlow,
        streamingTextFlow,
        errorMessageFlow
    ) { (messages, titles), sending, streaming, error ->
        ChatUiState(
            messages = messages,
            citationTitles = titles,
            isSending = sending,
            streamingText = streaming,
            errorMessage = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun sendMessage(text: String) {
        if (text.isBlank() || isSendingFlow.value) return
        isSendingFlow.value = true
        errorMessageFlow.value = null
        sendJob = viewModelScope.launch {
            try {
                // 会話は最初の送信時に遅延作成する(空の会話を増やさない)
                val id = conversationIdFlow.value
                    ?: repository.createConversation().also { savedStateHandle[ARG_CONVERSATION_ID] = it }
                streamingTextFlow.value = ""
                repository.sendMessageStream(id, text).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Started -> Unit
                        is ChatStreamEvent.Delta -> streamingTextFlow.value = event.textSoFar
                        is ChatStreamEvent.Completed -> streamingTextFlow.value = null
                        is ChatStreamEvent.Failed -> {
                            Timber.w(event.error, "sendMessageStream failed")
                            errorMessageFlow.value = event.error.toLlmUserMessage(SEND_FAILED_PREFIX)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "sendMessage failed")
                errorMessageFlow.value = e.toLlmUserMessage(SEND_FAILED_PREFIX)
            } finally {
                streamingTextFlow.value = null
                isSendingFlow.value = false
            }
        }
    }

    /** 生成を止める。途中までの回答は「(停止)」付きで保存される。 */
    fun stopGenerating() {
        sendJob?.cancel()
    }

    companion object {
        /** ナビゲーション引数(と SavedStateHandle)のキー。 */
        const val ARG_CONVERSATION_ID = "conversationId"

        const val MODEL_NOT_AVAILABLE_MESSAGE = MODEL_NOT_AVAILABLE_USER_MESSAGE

        private const val SEND_FAILED_PREFIX = "送信に失敗しました"
    }
}
