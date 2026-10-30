package com.unchunks.echomark.data.ai

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.chat.ChatMemoryPolicy
import com.unchunks.echomark.domain.repository.AiTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会話の古い発言を、会話ごとの要約(会話の記憶)に畳んでいく。要約は保存するため、会話を開き直しても作り直さない。
 * 回答が終わってから、待たずにアプリのスコープで動かす(画面を離れても続く。送信中の表示を延ばさない)。
 * 失敗しても回答には影響しないため、記録するだけにして次の回答の後にやり直す。
 * クラウド API は文脈に余裕があるため要約を作らない([com.unchunks.echomark.domain.provider.LlmProvider.summarizeConversation])。
 */
@Singleton
class ChatMemoryCompressor @Inject constructor(
    private val conversationDao: ConversationDao,
    private val chatMessageDao: ChatMessageDao,
    private val llmProviderResolver: LlmProviderResolver,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "会話の要約に失敗") }
    )

    /** 要約を作っている会話。同じ会話で重ねない */
    private val running: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * まだ要約していない発言がたまっていれば要約を始める(完了は待たない)。
     * @param tight 文脈に余裕が無いか。余裕が無ければ早めに要約する
     */
    fun compressIfNeeded(conversationId: Long, tight: Boolean) {
        if (!running.add(conversationId)) return
        scope.launch {
            try {
                compress(conversationId, tight)
            } finally {
                running.remove(conversationId)
            }
        }
    }

    /** [compressIfNeeded] の本体。要約を保存するまで待つ。失敗は記録するだけで投げない。 */
    internal suspend fun compress(conversationId: Long, tight: Boolean) {
        try {
            val conversation = conversationDao.getById(conversationId) ?: return
            val coveredUntil = conversation.summaryCoveredUntil
            val uncovered = chatMessageDao
                .getMessagesAfter(conversationId, coveredUntil ?: Long.MIN_VALUE, ChatMemoryPolicy.FETCH_LIMIT)
                .map { it.toDomain() }
            val batch = ChatMemoryPolicy.nextBatch(uncovered, tight)
            if (batch.isEmpty()) return
            val previous = conversation.summary.takeIf { coveredUntil != null }
            val summary = llmProviderResolver.resolve(AiTask.CHAT).summarizeConversation(previous, batch) ?: return
            conversationDao.updateSummary(conversationId, summary, batch.last().createdAt, coveredUntil)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "会話の要約を作れなかった。次の回答の後にやり直す: id=%d", conversationId)
        }
    }
}
