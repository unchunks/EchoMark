package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
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
    /**
     * 会話を作る。[title] を渡すとそのタイトルで固定し、最初の発言からの自動タイトルで上書きしない
     * (「このブックマークについて質問」でブックマーク名を使うときなど)。
     * @param aboutBookmarkId 「このブックマークについて質問」の対象。会話に保存し、開き直したときも固定する
     */
    suspend fun createConversation(title: String? = null, aboutBookmarkId: Long? = null): Long
    fun observeConversations(): Flow<List<Conversation>>

    /** 会話一覧用。最終更新の新しい順に、最後のメッセージを添えて流す。 */
    fun observeConversationPreviews(): Flow<List<ConversationPreview>>

    /** 1つの会話(タイトル表示用)。削除されると null。 */
    fun observeConversation(conversationId: Long): Flow<Conversation?>
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
     *
     * @param pinnedBookmarkId 必ず文脈に含めるブックマーク(「このブックマークについて質問」)。検索結果より先頭に置き、重複は除く
     * @param isRetry 失敗した質問の再試行。会話の最後が同じ内容のユーザー発言なら、それを使い回して二重に保存しない
     */
    fun sendMessageStream(
        conversationId: Long,
        userMessage: String,
        pinnedBookmarkId: Long? = null,
        isRetry: Boolean = false
    ): Flow<ChatStreamEvent>

    /** 会話を手動でリネームする(以降は自動タイトルで上書きしない)。 */
    suspend fun renameConversation(conversationId: Long, title: String)

    /** 会話とそのメッセージを削除する。 */
    suspend fun deleteConversation(conversationId: Long)

    /** 削除の取り消し。削除前の会話とメッセージを同じ ID のまま書き戻す。 */
    suspend fun restoreConversation(conversation: Conversation, messages: List<ChatMessage>)

    /**
     * 引用カードや「質問中のブックマーク」の表示用に、ID からブックマークを引く。
     * 存在しない(削除済み)IDは含まれない。
     */
    suspend fun getBookmarks(ids: List<Long>): Map<Long, Bookmark>

    /** 質問の例を作るための、最近保存したブックマーク(アーカイブ済みを除く、新しい順)。 */
    suspend fun getRecentBookmarks(limit: Int): List<Bookmark>
}
