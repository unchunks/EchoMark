package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.LearningItemDao
import com.unchunks.echomark.data.local.entity.LearningItemEntity
import com.unchunks.echomark.data.local.objectbox.EmbeddingEntity
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.objectbox.EmbeddingEntity_
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.LearningItem
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.scheduler.ReviewIntervals
import io.objectbox.Box
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val learningItemDao: LearningItemDao,
    private val chatMessageDao: ChatMessageDao,
    private val embeddingBox: Box<EmbeddingEntity>,
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
            val query = embeddingBox.query(
                EmbeddingEntity_.vector.nearestNeighbors(queryVector, 5)
            ).build()
            val results = query.findWithScores()
            query.close()

            val relatedBookmarkIds = results.map { it.get().bookmarkId }
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

            // 6. まだ復習スケジュールが無ければ、ここで初期化する
            val item = learningItemDao.getById(learningItemId)
            if (item != null && item.nextReviewAt == null) {
                val now = System.currentTimeMillis()
                val next = ReviewIntervals.nextReviewAt(now, reviewStage = 0)
                learningItemDao.updateReviewSchedule(
                    id = learningItemId, nextReviewAt = next, reviewStage = 0,
                    lastReviewedAt = null, updatedAt = now
                )
            }
        }

    override suspend fun markReviewed(learningItemId: Long) =
        withContext(dispatcherProvider.io) {
            val item = learningItemDao.getById(learningItemId) ?: return@withContext
            val now = System.currentTimeMillis()
            val newStage = item.reviewStage + 1
            val next = ReviewIntervals.nextReviewAt(now, newStage)
            learningItemDao.updateReviewSchedule(
                id = learningItemId, nextReviewAt = next, reviewStage = newStage,
                lastReviewedAt = now, updatedAt = now
            )
        }

    override suspend fun getDueLearningItems(now: Long): List<LearningItem> =
        withContext(dispatcherProvider.io) {
            learningItemDao.getDueItems(now).map { it.toDomain() }
        }
}