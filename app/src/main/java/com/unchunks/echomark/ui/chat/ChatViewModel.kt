package com.unchunks.echomark.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository
) : ViewModel() {

    private val learningItemIdFlow = MutableStateFlow<Long?>(null)
    private val isSendingFlow = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            learningItemIdFlow.value = repository.createLearningItem()
        }
    }

    val uiState: StateFlow<ChatUiState> = combine(
        learningItemIdFlow.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.observeMessages(id)
        },
        isSendingFlow
    ) { messages, sending ->
        ChatUiState(messages = messages, isSending = sending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun sendMessage(text: String) {
        val id = learningItemIdFlow.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            isSendingFlow.value = true
            repository.sendMessage(id, text)
            isSendingFlow.value = false
        }
    }
}