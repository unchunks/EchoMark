package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
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

    override suspend fun createConversation(title: String?, aboutBookmarkId: Long?): Long =
        withContext(dispatcherProvider.io) {
            val now = System.currentTimeMillis()
            val fixedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            conversationDao.insert(
                ConversationEntity(
                    title = fixedTitle ?: DEFAULT_TITLE,
                    // 呼び出し側が決めたタイトルは、最初の発言からの自動タイトルで上書きしない
                    isTitleManuallySet = fixedTitle != null,
                    createdAt = now,
                    updatedAt = now,
                    aboutBookmarkId = aboutBookmarkId
                )
            )
        }

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.getAll().map { list -> list.map { it.toDomain() } }

    override fun observeConversationPreviews(): Flow<List<ConversationPreview>> =
        conversationDao.observeAllWithLastMessage().map { list -> list.map { it.toDomain() } }

    override fun observeConversation(conversationId: Long): Flow<Conversation?> =
        conversationDao.observeById(conversationId).map { it?.toDomain() }

    override fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        chatMessageDao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun sendMessage(conversationId: Long, userMessage: String): Unit =
        withContext(dispatcherProvider.io) {
            val prepared = prepare(conversationId, userMessage)

            // 4. LLMに問い合わせ(モデル未取得なら ModelNotAvailableException をそのまま伝播)
            val answer = llmProviderResolver.resolve(AiTask.CHAT).chat(userMessage, prepared.context, prepared.history)

            // 5. 回答を保存(実際に文脈として使ったブックマークIDだけを記録)
            saveAssistantMessage(conversationId, answer, prepared.referencedIds)
        }

    override fun sendMessageStream(
        conversationId: Long,
        userMessage: String,
        pinnedBookmarkId: Long?,
        isRetry: Boolean
    ): Flow<ChatStreamEvent> = flow {
        val prepared = try {
            prepare(conversationId, userMessage, pinnedBookmarkId, isRetry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ChatStreamEvent.Failed(e))
            return@flow
        }
        emit(ChatStreamEvent.Started(prepared.referencedIds))

        val provider = try {
            llmProviderResolver.resolve(AiTask.CHAT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ChatStreamEvent.Failed(e))
            return@flow
        }

        val answer = StringBuilder()
        var failure: Throwable? = null
        try {
            provider.chatStream(userMessage, prepared.context, prepared.history)
                // 生成側の失敗だけを受け取る(collect 側の例外は素通し)
                .catch { failure = it }
                .collect { chunk ->
                    answer.append(chunk)
                    emit(ChatStreamEvent.Delta(answer.toString()))
                }
        } catch (e: CancellationException) {
            // 停止ボタンなどによる中断。途中までの回答があれば「(停止)」付きで残す
            val partial = answer.toString().trimEnd()
            if (partial.isNotEmpty()) {
                withContext(NonCancellable) {
                    saveAssistantMessage(conversationId, partial + STOPPED_SUFFIX, prepared.referencedIds)
                }
            }
            throw e
        }

        // 失敗時は途中までの出力を破棄する(拒否などで不完全な回答を残さない)
        failure?.let {
            emit(ChatStreamEvent.Failed(it))
            return@flow
        }
        val text = answer.toString().trim()
        if (text.isEmpty()) {
            emit(ChatStreamEvent.Failed(LlmException.Unexpected("応答が空でした")))
            return@flow
        }
        emit(ChatStreamEvent.Completed(saveAssistantMessage(conversationId, text, prepared.referencedIds)))
    }.flowOn(dispatcherProvider.io)

    /** 送信前の準備: 履歴の取得、ユーザー発言の保存、文脈(関連ブックマーク)の検索。 */
    private suspend fun prepare(
        conversationId: Long,
        userMessage: String,
        pinnedBookmarkId: Long? = null,
        isRetry: Boolean = false
    ): PreparedChat {
        // 0. 今回の発言を保存する前に、直近の会話履歴を取得しておく(今回分を含めないため)。
        //    再試行で、失敗した同じ発言が最後に残っているなら、それを履歴から外して使い回す(二重に保存しない)
        val recent = chatMessageDao.getRecentMessages(conversationId, RagSupport.HISTORY_LIMIT + 1)
        val last = recent.lastOrNull()
        val reuseLastUserMessage = isRetry && last?.role == ChatRole.USER && last.content == userMessage
        val history = (if (reuseLastUserMessage) recent.dropLast(1) else recent)
            .takeLast(RagSupport.HISTORY_LIMIT)
            .map { it.toDomain() }

        // 1. ユーザーの発言を保存
        val userTime = System.currentTimeMillis()
        if (!reuseLastUserMessage) {
            chatMessageDao.insert(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.USER,
                    content = userMessage,
                    createdAt = userTime
                )
            )
        }
        conversationDao.touch(conversationId, userTime)

        // 2〜3. 関連ブックマークを検索(削除済み等で取得できなかったものは除き、近い順を保つ)。
        //    質問の対象が固定されていれば、それを必ず先頭に含める
        val pinned = pinnedBookmarkId?.let { bookmarkRepository.getBookmarksByIds(listOf(it)).firstOrNull() }
        val searched = findRelevantBookmarks(RagSupport.searchQueryFor(userMessage, pinned?.title))
        val usedBookmarks = RagSupport.pinFirst(pinned, searched, idOf = { it.id })
        val context = usedBookmarks.mapIndexed { i, b ->
            if (b.id == pinned?.id) {
                RagSupport.formatPinnedContextEntry(i + 1, b.title, b.summary, b.content)
            } else {
                RagSupport.formatContextEntry(i + 1, b.title, b.summary ?: b.content)
            }
        }
        return PreparedChat(history, context, usedBookmarks.map { it.id })
    }

    private suspend fun findRelevantBookmarks(userMessage: String): List<Bookmark> {
        // 2. 質問文をembedding化。埋め込みモデルが無い環境ではキーワード検索で代替する
        val queryVector = try {
            embeddingProvider.embedQuery(userMessage)
        } catch (e: EmbeddingUnavailableException) {
            Timber.i("埋め込みモデルが無いため、チャットの文脈はキーワード検索で探す")
            return bookmarkRepository.search(userMessage, null).take(RagSupport.SEARCH_LIMIT)
        }

        // 3. ObjectBoxで近傍検索し、類似度しきい値を下回る(無関係な)結果を除外
        // score は COSINE 距離。RagSupport 側で類似度へ変換して判定する
        val hits = vectorSearch.nearestNeighbors(queryVector, RagSupport.SEARCH_LIMIT)
            .map { it.bookmarkId to it.score }
        val relevantIds = RagSupport.selectRelevantIds(hits)
        val byId = bookmarkRepository.getBookmarksByIds(relevantIds).associateBy { it.id }
        return relevantIds.mapNotNull { byId[it] }
    }

    /** アシスタントの回答を保存し、会話の更新日時・自動タイトルを更新する。保存したメッセージ ID を返す。 */
    private suspend fun saveAssistantMessage(
        conversationId: Long,
        content: String,
        referencedIds: List<Long>
    ): Long {
        val answerTime = System.currentTimeMillis()
        val id = chatMessageDao.insert(
            ChatMessageEntity(
                conversationId = conversationId,
                role = ChatRole.ASSISTANT,
                content = content,
                referencedBookmarkIds = referencedIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                createdAt = answerTime
            )
        )
        conversationDao.touch(conversationId, answerTime)

        // 6. 手動リネームされていなければ、最初のユーザー発言から自動タイトルを付ける
        chatMessageDao.getFirstByRole(conversationId, ChatRole.USER)?.let { first ->
            val title = RagSupport.titleFrom(first.content)
            if (title.isNotEmpty()) conversationDao.updateAutoTitle(conversationId, title, answerTime)
        }
        return id
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

    override suspend fun restoreConversation(conversation: Conversation, messages: List<ChatMessage>): Unit =
        withContext(dispatcherProvider.io) {
            conversationDao.insert(conversation.toEntity())
            if (messages.isNotEmpty()) chatMessageDao.insertAll(messages.map { it.toEntity() })
        }

    override suspend fun getBookmarks(ids: List<Long>): Map<Long, Bookmark> =
        if (ids.isEmpty()) emptyMap()
        else bookmarkRepository.getBookmarksByIds(ids).associateBy { it.id }

    override suspend fun getRecentBookmarks(limit: Int): List<Bookmark> =
        bookmarkRepository.observeBookmarks(BookmarkFilter.ACTIVE, BookmarkSortOrder.NEWEST)
            .first()
            .take(limit)

    private class PreparedChat(
        val history: List<ChatMessage>,
        val context: List<String>,
        val referencedIds: List<Long>
    )

    companion object {
        /** 生成を途中で止めた回答の末尾に付ける印。 */
        const val STOPPED_SUFFIX = "\n\n(停止)"

        /** 最初の発言が来るまでの会話タイトル。 */
        const val DEFAULT_TITLE = "新しいチャット"
    }
}
