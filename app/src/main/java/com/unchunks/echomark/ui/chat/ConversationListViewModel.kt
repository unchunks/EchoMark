package com.unchunks.echomark.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ChatRepository
) : ViewModel() {

    /** 最終更新の新しい順(DAO 側で updatedAt DESC)。 */
    val conversations: StateFlow<List<Conversation>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rename(conversationId: Long, title: String) {
        viewModelScope.launch { repository.renameConversation(conversationId, title) }
    }

    fun delete(conversationId: Long) {
        viewModelScope.launch { repository.deleteConversation(conversationId) }
    }
}
