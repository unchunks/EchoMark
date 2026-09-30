package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.chat.RagSupport
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
            // 0. 今回の発言を保存する前に、直近の会話履歴を取得しておく(今回分を含めないため)
            val history = chatMessageDao.getRecentMessages(conversationId, RagSupport.HISTORY_LIMIT)
                .map { it.toDomain() }

            // 1. ユーザーの発言を保存
            val userTime = System.currentTimeMillis()
            chatMessageDao.insert(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.USER,
                    content = userMessage,
                    createdAt = userTime
                )
            )
            conversationDao.touch(conversationId, userTime)

            // 2. 質問文をembedding化
            val queryVector = embeddingProvider.embedQuery(userMessage)

            // 3. ObjectBoxで近傍検索し、類似度しきい値を下回る(無関係な)結果を除外
            // score は COSINE 距離。RagSupport 側で類似度へ変換して判定する
            val hits = vectorSearch.nearestNeighbors(queryVector, RagSupport.SEARCH_LIMIT)
                .map { it.bookmarkId to it.score }
            val relevantIds = RagSupport.selectRelevantIds(hits)
            val byId = bookmarkRepository.getBookmarksByIds(relevantIds).associateBy { it.id }
            // 削除済み等で取得できなかったものは除き、近い順を保つ
            val usedBookmarks = relevantIds.mapNotNull { byId[it] }
            val context = usedBookmarks.mapIndexed { i, b ->
                RagSupport.formatContextEntry(i + 1, b.title, b.summary ?: b.content)
            }

            // 4. LLMに問い合わせ(モデル未取得なら ModelNotAvailableException をそのまま伝播)
            val answer = llmProviderResolver.resolve().chat(userMessage, context, history)

            // 5. 回答を保存(実際に文脈として使ったブックマークIDだけを記録)
            val answerTime = System.currentTimeMillis()
            chatMessageDao.insert(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.ASSISTANT,
                    content = answer,
                    referencedBookmarkIds = usedBookmarks.map { it.id }
                        .takeIf { it.isNotEmpty() }?.joinToString(","),
                    createdAt = answerTime
                )
            )
            conversationDao.touch(conversationId, answerTime)

            // 6. 手動リネームされていなければ、最初のユーザー発言から自動タイトルを付ける
            chatMessageDao.getFirstByRole(conversationId, ChatRole.USER)?.let { first ->
                val title = RagSupport.titleFrom(first.content)
                if (title.isNotEmpty()) conversationDao.updateAutoTitle(conversationId, title, answerTime)
            }
        }

    override suspend fun renameConversation(conversationId: Long, title: String): Unit =
        withContext(dispatcherProvider.io) {
            val trimmed = title.trim()
            if (trimmed.isNotEmpty()) conversationDao.rename(conversationId, trimmed)
        }

    override suspend fun deleteConversation(conversationId: Long): Unit =
        withContext(dispatcherProvider.io) {
            conversationDao.deleteById(conversationId)
        }

    override suspend fun getBookmarkTitles(ids: List<Long>): Map<Long, String> =
        if (ids.isEmpty()) emptyMap()
        else bookmarkRepository.getBookmarksByIds(ids).associate { it.id to it.title }
}
