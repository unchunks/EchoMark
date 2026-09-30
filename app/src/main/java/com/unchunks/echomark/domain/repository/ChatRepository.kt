package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

/** [ChatRepository.sendMessageStream] の進行状況。 */
sealed interface ChatStreamEvent {
    /** ユーザー発言を保存し、文脈に使うブックマークが決まった。 */
    data class Started(val referencedBookmarkIds: List<Long>) : ChatStreamEvent

    /** 生成途中の回答(先頭からの全文)。 */
    data class Delta(val textSoFar: String) : ChatStreamEvent

    /** 回答を保存した。 */
    data class Completed(val messageId: Long) : ChatStreamEvent

    /**
     * 失敗した。ユーザー発言は保存済み、アシスタントの回答は保存しない(途中までの出力も破棄)。
     * [error] は [com.unchunks.echomark.domain.provider.LlmException] や
     * [com.unchunks.echomark.domain.provider.ModelNotAvailableException] など。
     */
    data class Failed(val error: Throwable) : ChatStreamEvent
}

interface ChatRepository {
    suspend fun createConversation(): Long
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>>

    /**
     * ユーザー発言を保存し、関連ブックマークを根拠に回答を生成して保存する。
     * @throws com.unchunks.echomark.domain.provider.ModelNotAvailableException LLM モデルが未取得のとき
     *   (この場合アシスタントのメッセージは保存しない)
     */
    suspend fun sendMessage(conversationId: Long, userMessage: String)

    /**
     * [sendMessage] のストリーミング版。ユーザー発言は即保存し、回答は完了時に保存する。
     * - 失敗時は [ChatStreamEvent.Failed] を流して終了する(例外は投げない)
     * - 収集側がキャンセルした場合は、途中までの回答があれば末尾に「(停止)」を付けて保存する
     */
    fun sendMessageStream(conversationId: Long, userMessage: String): Flow<ChatStreamEvent>

    /** 会話を手動でリネームする(以降は自動タイトルで上書きしない)。 */
    suspend fun renameConversation(conversationId: Long, title: String)

    /** 会話とそのメッセージを削除する。 */
    suspend fun deleteConversation(conversationId: Long)

    /** 引用チップ表示用に、ブックマークID からタイトルを引く。存在しない(削除済み)IDは含まれない。 */
    suspend fun getBookmarkTitles(ids: List<Long>): Map<Long, String>
}
