package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.domain.repository.SaveResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

fun testBookmark(
    id: Long,
    title: String = "title$id",
    lastAccessedAt: Long = 0L
) = Bookmark(
    id = id,
    type = BookmarkType.TEXT,
    title = title,
    createdAt = 0L,
    lastAccessedAt = lastAccessedAt
)

/** ViewModel テスト用の手書き Fake。使わないメソッドは呼ばれたら失敗させる。 */
class FakeBookmarkRepository : BookmarkRepository {
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val tags = MutableStateFlow<List<Tag>>(emptyList())

    val searchCalls = mutableListOf<Pair<String, Long?>>()
    val observedTagIds = mutableListOf<Long>()
    val deleted = mutableListOf<Bookmark>()
    val restored = mutableListOf<Bookmark>()

    override suspend fun search(query: String, tagId: Long?): List<Bookmark> {
        searchCalls += query to tagId
        return bookmarks.value.filter { it.title.contains(query) }
    }

    override fun observeBookmarks(): Flow<List<Bookmark>> = bookmarks

    override fun observeBookmarksByTag(tagId: Long): Flow<List<Bookmark>> {
        observedTagIds += tagId
        return bookmarks.map { list -> list.filter { tagId.toString() in it.tags } }
    }

    override fun observeAllTags(): Flow<List<Tag>> = tags

    /** DAO のクエリと同じ規則で絞り込み・並べ替えする(タグは tags に tagId の文字列を入れて表す)。 */
    override fun observeBookmarks(
        filter: BookmarkFilter,
        sortOrder: BookmarkSortOrder,
        tagId: Long?
    ): Flow<List<Bookmark>> = bookmarks.map { list ->
        list
            .filter {
                when (filter) {
                    BookmarkFilter.ACTIVE -> !it.isArchived
                    BookmarkFilter.ARCHIVED -> it.isArchived
                    BookmarkFilter.FAVORITES -> it.isFavorite && !it.isArchived
                }
            }
            .filter { tagId == null || tagId.toString() in it.tags }
            .sortedWith(
                when (sortOrder) {
                    BookmarkSortOrder.NEWEST -> compareByDescending<Bookmark> { it.createdAt }
                    BookmarkSortOrder.OLDEST -> compareBy<Bookmark> { it.createdAt }
                    BookmarkSortOrder.RECENTLY_OPENED -> compareByDescending<Bookmark> { it.lastAccessedAt }
                    BookmarkSortOrder.TITLE -> compareBy<Bookmark, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                }.thenByDescending { it.createdAt }.thenByDescending { it.id }
            )
    }

    override suspend fun setFavorite(id: Long, isFavorite: Boolean) =
        updateBookmark(id) { it.copy(isFavorite = isFavorite) }

    override suspend fun setArchived(id: Long, isArchived: Boolean) =
        updateBookmark(id) { it.copy(isArchived = isArchived) }

    override suspend fun updateLinkMetadata(id: Long, imageUrl: String?, siteName: String?) =
        updateBookmark(id) { it.copy(imageUrl = imageUrl, siteName = siteName) }

    private fun updateBookmark(id: Long, transform: (Bookmark) -> Bookmark) {
        bookmarks.value = bookmarks.value.map { if (it.id == id) transform(it) else it }
    }

    override suspend fun deleteBookmark(bookmark: Bookmark) {
        deleted += bookmark
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmark.id }
    }

    override suspend fun restoreBookmark(bookmark: Bookmark) {
        restored += bookmark
        bookmarks.value = bookmarks.value + bookmark
    }

    override suspend fun saveBookmarkWithResult(bookmark: Bookmark): SaveResult {
        val id = bookmarks.value.size + 1L
        bookmarks.value = bookmarks.value + bookmark.copy(id = id)
        return SaveResult(id, isDuplicate = false)
    }

    override suspend fun getStaleBookmarks(threshold: Long, limit: Int): List<Bookmark> =
        bookmarks.value.filter { it.lastAccessedAt <= threshold }.sortedBy { it.lastAccessedAt }.take(limit)

    override suspend fun saveBookmark(bookmark: Bookmark): Long = TODO("not used")
    override suspend fun saveUrlBookmark(url: String, title: String?, memo: String?): SaveResult = TODO("not used")
    override suspend fun saveTags(bookmarkId: Long, tagNames: List<String>) = TODO("not used")
    override suspend fun saveEmbedding(bookmarkId: Long, vector: FloatArray, modelVersion: String) = TODO("not used")
    override suspend fun updateSummary(id: Long, summary: String) = TODO("not used")
    override suspend fun updateCategory(id: Long, category: String) = TODO("not used")
    override suspend fun updateTitleAndContent(id: Long, title: String, content: String?) = TODO("not used")
    override suspend fun updateAiStatus(id: Long, status: AiStatus) = TODO("not used")
    override suspend fun addTag(bookmarkId: Long, tagName: String) = TODO("not used")
    override suspend fun removeTag(bookmarkId: Long, tagName: String) = TODO("not used")
    override suspend fun markAccessed(id: Long) = TODO("not used")
    override suspend fun reprocess(id: Long) = TODO("not used")
    override suspend fun enqueueWaitingModelProcessing() = TODO("not used")
    override suspend fun enqueueFailedAndWaitingProcessing(): Int = TODO("not used")
    override suspend fun getBookmarkById(id: Long): Bookmark? = TODO("not used")
    override suspend fun getBookmarksByIds(ids: List<Long>): List<Bookmark> = TODO("not used")
    override suspend fun getAllBookmarkIds(): List<Long> = TODO("not used")
    override suspend fun getRelatedBookmarks(bookmarkId: Long, limit: Int): List<Bookmark> = TODO("not used")
    override suspend fun getEmbeddingModelVersion(bookmarkId: Long): String? = TODO("not used")
    override fun observeBookmark(id: Long): Flow<Bookmark?> = TODO("not used")
}

open class FakeChatRepository : ChatRepository {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val previews = MutableStateFlow<List<ConversationPreview>>(emptyList())
    val conversation = MutableStateFlow<Conversation?>(null)

    /** getBookmarks / getRecentBookmarks が返すブックマーク(新しい順)。 */
    var bookmarks: List<Bookmark> = emptyList()

    var createdConversationId = 42L
    var createCalls = 0
    val createdTitles = mutableListOf<String?>()
    val sent = mutableListOf<Pair<Long, String>>()

    /** sendMessageStream の呼び出し(固定したブックマーク・再試行かどうかを含む)。 */
    data class SendCall(val conversationId: Long, val text: String, val pinnedBookmarkId: Long?, val isRetry: Boolean)
    val sendCalls = mutableListOf<SendCall>()

    val renamed = mutableListOf<Pair<Long, String>>()
    val deletedIds = mutableListOf<Long>()
    val restored = mutableListOf<Pair<Conversation, List<ChatMessage>>>()

    /** 非 null なら sendMessage がこの例外を投げる。 */
    var sendFailure: Throwable? = null

    override suspend fun createConversation(title: String?): Long {
        createCalls++
        createdTitles += title
        return createdConversationId
    }

    override fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> = messages

    override fun observeConversation(conversationId: Long): Flow<Conversation?> = conversation

    override fun observeConversationPreviews(): Flow<List<ConversationPreview>> = previews

    override suspend fun sendMessage(conversationId: Long, userMessage: String) {
        sent += conversationId to userMessage
        sendFailure?.let { throw it }
    }

    /** 既定: sendFailure があれば Failed、無ければ Started → Delta → Completed を流す。 */
    override fun sendMessageStream(
        conversationId: Long,
        userMessage: String,
        pinnedBookmarkId: Long?,
        isRetry: Boolean
    ): Flow<ChatStreamEvent> = flow {
        sent += conversationId to userMessage
        sendCalls += SendCall(conversationId, userMessage, pinnedBookmarkId, isRetry)
        val failure = sendFailure
        if (failure != null) {
            emit(ChatStreamEvent.Failed(failure))
            return@flow
        }
        emit(ChatStreamEvent.Started(emptyList()))
        emit(ChatStreamEvent.Delta("回答"))
        emit(ChatStreamEvent.Completed(1L))
    }

    override suspend fun getBookmarks(ids: List<Long>): Map<Long, Bookmark> =
        bookmarks.filter { it.id in ids }.associateBy { it.id }

    override suspend fun getRecentBookmarks(limit: Int): List<Bookmark> = bookmarks.take(limit)

    override suspend fun renameConversation(conversationId: Long, title: String) {
        renamed += conversationId to title
    }

    override suspend fun deleteConversation(conversationId: Long) {
        deletedIds += conversationId
        previews.value = previews.value.filterNot { it.conversation.id == conversationId }
    }

    override suspend fun restoreConversation(conversation: Conversation, messages: List<ChatMessage>) {
        restored += conversation to messages
        previews.value = (previews.value + ConversationPreview(conversation))
            .sortedByDescending { it.conversation.updatedAt }
    }

    override fun observeConversations(): Flow<List<Conversation>> = TODO("not used")
}

/** AI の準備状態の Fake。 */
class FakeAiSetupRepository(initial: AiSetupState = AiSetupState.READY) : AiSetupRepository {
    val state = MutableStateFlow(initial)
    override val setupState: Flow<AiSetupState> = state
}
