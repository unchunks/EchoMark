package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val chatMessageDao: ChatMessageDao,
    private val vectorSearch: VectorSearchDataSource,
    private val embeddingProvider: EmbeddingProvider,
    private val llmProviderResolver: LlmProviderResolver,
    private val bookmarkRepository: BookmarkRepository,
    private val dispatcherProvider: DispatcherProvider
) : ChatRepository {

    override suspend fun createConversation(): Long =
        withContext(dispatcherProvider.io) {
            val now = System.currentTimeMillis()
            conversationDao.insert(
                ConversationEntity(title = "新しいチャット", createdAt = now, updatedAt = now)
            )
        }

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.getAll().map { list -> list.map { it.toDomain() } }

    override fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        chatMessageDao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun sendMessage(conversationId: Long, userMessage: String): Unit =
        withContext(dispatcherProvider.io) {
            // 1. ユーザーの発言を保存
            chatMessageDao.insert(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.USER,
                    content = userMessage,
                    createdAt = System.currentTimeMillis()
                )
            )

            // 2. 質問文をembedding化
            val queryVector = embeddingProvider.embedQuery(userMessage)

            // 3. ObjectBoxで類似度上位5件を検索
            val relatedBookmarkIds = vectorSearch.nearestNeighbors(queryVector, 5).map { it.bookmarkId }
            val relatedBookmarks = bookmarkRepository.getBookmarksByIds(relatedBookmarkIds)
            val context = relatedBookmarks.mapNotNull { it.summary ?: it.content }

            // 4. LLMに問い合わせ
            val answer = llmProviderResolver.resolve().chat(userMessage, context)

            // 5. 回答を保存
            chatMessageDao.insert(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.ASSISTANT,
                    content = answer,
                    referencedBookmarkIds = relatedBookmarkIds.joinToString(","),
                    createdAt = System.currentTimeMillis()
                )
            )
        }
}
