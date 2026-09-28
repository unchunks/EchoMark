package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.LearningItemDao
import com.unchunks.echomark.data.local.entity.LearningItemEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.LearningItem
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val learningItemDao: LearningItemDao,
    private val chatMessageDao: ChatMessageDao,
    private val vectorSearch: VectorSearchDataSource,
    private val embeddingProvider: EmbeddingProvider,
    private val llmProviderResolver: LlmProviderResolver,
    private val bookmarkRepository: BookmarkRepository,
    private val dispatcherProvider: DispatcherProvider
) : ChatRepository {

    override suspend fun createLearningItem(): Long =
        withContext(dispatcherProvider.io) {
            val now = System.currentTimeMillis()
            learningItemDao.insert(
                LearningItemEntity(title = "新しいチャット", createdAt = now, updatedAt = now)
            )
        }

    override fun observeLearningItems(): Flow<List<LearningItem>> =
        learningItemDao.getAll().map { list -> list.map { it.toDomain() } }

    override fun observeMessages(learningItemId: Long): Flow<List<ChatMessage>> =
        chatMessageDao.observeMessages(learningItemId).map { list -> list.map { it.toDomain() } }

    override suspend fun sendMessage(learningItemId: Long, userMessage: String): Unit =
        withContext(dispatcherProvider.io) {
            // 1. ユーザーの発言を保存
            chatMessageDao.insert(
                ChatMessageEntity(
                    learningItemId = learningItemId,
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
                    learningItemId = learningItemId,
                    role = ChatRole.ASSISTANT,
                    content = answer,
                    referencedBookmarkIds = relatedBookmarkIds.joinToString(","),
                    createdAt = System.currentTimeMillis()
                )
            )
        }
}
