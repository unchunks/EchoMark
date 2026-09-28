package com.unchunks.echomark.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val errorMessageFlow = MutableStateFlow<String?>(null)

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
        errorMessageFlow
    ) { (messages, titles), sending, error ->
        ChatUiState(
            messages = messages,
            citationTitles = titles,
            isSending = sending,
            errorMessage = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun sendMessage(text: String) {
        if (text.isBlank() || isSendingFlow.value) return
        viewModelScope.launch {
            isSendingFlow.value = true
            errorMessageFlow.value = null
            try {
                // 会話は最初の送信時に遅延作成する(空の会話を増やさない)
                val id = conversationIdFlow.value
                    ?: repository.createConversation().also { savedStateHandle[ARG_CONVERSATION_ID] = it }
                repository.sendMessage(id, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelNotAvailableException) {
                Timber.w(e, "LLM model not available")
                errorMessageFlow.value = MODEL_NOT_AVAILABLE_MESSAGE
            } catch (e: Exception) {
                Timber.e(e, "sendMessage failed")
                errorMessageFlow.value = "送信に失敗しました: ${e.message ?: "不明なエラー"}"
            } finally {
                isSendingFlow.value = false
            }
        }
    }

    companion object {
        /** ナビゲーション引数(と SavedStateHandle)のキー。 */
        const val ARG_CONVERSATION_ID = "conversationId"

        const val MODEL_NOT_AVAILABLE_MESSAGE =
            "AIモデルが未ダウンロードです。設定からダウンロードしてください"
    }
}
