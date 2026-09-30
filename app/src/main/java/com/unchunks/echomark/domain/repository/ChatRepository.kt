package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

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

    /** 会話を手動でリネームする(以降は自動タイトルで上書きしない)。 */
    suspend fun renameConversation(conversationId: Long, title: String)

    /** 会話とそのメッセージを削除する。 */
    suspend fun deleteConversation(conversationId: Long)

    /** 引用チップ表示用に、ブックマークID からタイトルを引く。存在しない(削除済み)IDは含まれない。 */
    suspend fun getBookmarkTitles(ids: List<Long>): Map<Long, String>
}
