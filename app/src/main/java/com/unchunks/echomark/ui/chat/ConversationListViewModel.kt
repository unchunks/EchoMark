package com.unchunks.echomark.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationListUiState(
    val isLoading: Boolean = true,
    /** 最終更新の新しい順。 */
    val conversations: List<ConversationPreview> = emptyList(),
    val aiSetup: AiSetupState = AiSetupState.READY,
    /** 直前に削除した会話(「元に戻す」の Snackbar を出す)。 */
    val recentlyDeleted: ConversationPreview? = null
)

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ChatRepository,
    aiSetupRepository: AiSetupRepository
) : ViewModel() {

    /** 削除の取り消し用に、削除前の会話とメッセージを保持する。 */
    private class DeletedSnapshot(val preview: ConversationPreview, val messages: List<ChatMessage>)

    private val deletedFlow = MutableStateFlow<DeletedSnapshot?>(null)

    val uiState: StateFlow<ConversationListUiState> = combine(
        repository.observeConversationPreviews(),
        aiSetupRepository.setupState,
        deletedFlow
    ) { conversations, setup, deleted ->
        ConversationListUiState(
            isLoading = false,
            conversations = conversations,
            aiSetup = setup,
            recentlyDeleted = deleted?.preview
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState())

    fun rename(conversationId: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.renameConversation(conversationId, title) }
    }

    /** 削除する。[undoDelete] で元に戻せる(Snackbar を閉じたら [clearRecentlyDeleted])。 */
    fun delete(preview: ConversationPreview) {
        val id = preview.conversation.id
        viewModelScope.launch {
            val messages = repository.observeMessages(id).first()
            repository.deleteConversation(id)
            deletedFlow.value = DeletedSnapshot(preview, messages)
        }
    }

    fun undoDelete() {
        val snapshot = deletedFlow.value ?: return
        deletedFlow.value = null
        viewModelScope.launch {
            repository.restoreConversation(snapshot.preview.conversation, snapshot.messages)
        }
    }

    fun clearRecentlyDeleted() {
        deletedFlow.value = null
    }
}
