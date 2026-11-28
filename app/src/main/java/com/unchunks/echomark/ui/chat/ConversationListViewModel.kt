package com.unchunks.echomark.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.splitSearchTerms
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ConversationMatch
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationListUiState(
    val isLoading: Boolean = true,
    /** 固定した会話を先に、それぞれ最終更新の新しい順。検索中は検索に当たった会話だけ。 */
    val conversations: List<ConversationPreview> = emptyList(),
    val aiSetup: AiSetupState = AiSetupState.READY,
    /** 検索語。 */
    val query: String = "",
    /** 会話が1つでもあるか(検索に当たらなくても検索欄は出す)。 */
    val hasConversations: Boolean = false,
    /** 検索語を含む発言の抜粋(conversationId -> 抜粋)。タイトルだけに当たった会話は含まない。 */
    val matchedExcerpts: Map<Long, String> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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

    private val queryFlow = MutableStateFlow("")

    /** 検索の結果。検索していなければ null。打っている間に毎回探さない */
    private val matchesFlow: Flow<List<ConversationMatch>?> = queryFlow
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .mapLatest { query -> if (query.isBlank()) null else repository.searchConversations(query) }
        .onStart { emit(null) }

    val uiState: StateFlow<ConversationListUiState> = combine(
        repository.observeConversationPreviews(),
        aiSetupRepository.setupState,
        queryFlow,
        matchesFlow
    ) { conversations, setup, query, matches ->
        val matchedIds = matches?.mapTo(HashSet()) { it.conversationId }
        val firstTerm = splitSearchTerms(query).firstOrNull().orEmpty()
        ConversationListUiState(
            isLoading = false,
            conversations = if (matchedIds == null) conversations else conversations.filter { it.conversation.id in matchedIds },
            aiSetup = setup,
            query = query,
            hasConversations = conversations.isNotEmpty(),
            matchedExcerpts = matches.orEmpty().mapNotNull { match ->
                match.matchedMessage?.let { match.conversationId to conversationMatchExcerpt(it, firstTerm) }
            }.toMap()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState())

    fun onQueryChange(query: String) {
        queryFlow.value = query
    }

    fun setPinned(conversationId: Long, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(conversationId, pinned) }
    }

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

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
