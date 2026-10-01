package com.unchunks.echomark.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationListUiState(
    val isLoading: Boolean = true,
    /** 最終更新の新しい順。 */
    val conversations: List<ConversationPreview> = emptyList(),
    val aiSetup: AiSetupState = AiSetupState.READY
)

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ChatRepository,
    aiSetupRepository: AiSetupRepository
) : ViewModel() {

    /** 削除の取り消し用に、削除前の会話とメッセージを保持する。 */
    private class DeletedSnapshot(val preview: ConversationPreview, val messages: List<ChatMessage>)

    /** 取り消しできる直前の削除。Snackbar を閉じたら捨てる */
    private var lastDeleted: DeletedSnapshot? = null

    private val deletedChannel = Channel<ConversationPreview>(Channel.BUFFERED)

    /**
     * 会話を削除したという一度きりの知らせ(「元に戻す」の Snackbar を出す)。
     * 状態として持たないので、Snackbar の表示中に画面を離れても、戻ったときに同じ知らせを出し直さない。
     */
    val deletedEvents: Flow<ConversationPreview> = deletedChannel.receiveAsFlow()

    val uiState: StateFlow<ConversationListUiState> = combine(
        repository.observeConversationPreviews(),
        aiSetupRepository.setupState
    ) { conversations, setup ->
        ConversationListUiState(isLoading = false, conversations = conversations, aiSetup = setup)
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
            lastDeleted = DeletedSnapshot(preview, messages)
            deletedChannel.send(preview)
        }
    }

    /**
     * [conversationId] の削除を取り消す。続けて別の会話を削除したあとの古い Snackbar から呼ばれても、
     * 別の会話を復元しないよう ID で確かめる。
     */
    fun undoDelete(conversationId: Long) {
        val snapshot = lastDeleted?.takeIf { it.preview.conversation.id == conversationId } ?: return
        lastDeleted = null
        viewModelScope.launch {
            repository.restoreConversation(snapshot.preview.conversation, snapshot.messages)
        }
    }

    /** [conversationId] の取り消し用の控えを捨てる(その Snackbar が閉じた) */
    fun clearRecentlyDeleted(conversationId: Long) {
        if (lastDeleted?.preview?.conversation?.id == conversationId) lastDeleted = null
    }
}
