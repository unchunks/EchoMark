package com.unchunks.echomark.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository
) : ViewModel() {

    private val conversationIdFlow = MutableStateFlow<Long?>(null)
    private val isSendingFlow = MutableStateFlow(false)
    private val errorMessageFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ChatUiState> = combine(
        conversationIdFlow.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.observeMessages(id)
        },
        isSendingFlow,
        errorMessageFlow
    ) { messages, sending, error ->
        ChatUiState(messages = messages, isSending = sending, errorMessage = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun sendMessage(text: String) {
        if (text.isBlank() || isSendingFlow.value) return
        viewModelScope.launch {
            isSendingFlow.value = true
            errorMessageFlow.value = null
            try {
                // 会話は最初の送信時に遅延作成する(空の会話を増やさない)
                val id = conversationIdFlow.value
                    ?: repository.createConversation().also { conversationIdFlow.value = it }
                repository.sendMessage(id, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "sendMessage failed")
                errorMessageFlow.value = "送信に失敗しました: ${e.message ?: "不明なエラー"}"
            } finally {
                isSendingFlow.value = false
            }
        }
    }
}
