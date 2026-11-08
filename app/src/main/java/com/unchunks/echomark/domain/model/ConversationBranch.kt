package com.unchunks.echomark.domain.model

/**
 * 分かれた先の会話。
 * @property branchedAt 元の会話と共通の最後の発言の日時(元の会話のどの発言から分かれたかを示す)
 */
data class ConversationBranch(val id: Long, val title: String, val branchedAt: Long)

/**
 * 会話の検索に当たった1件。
 * @property matchedMessage 検索語を含む発言。タイトルだけに当たったなら null
 */
data class ConversationMatch(val conversationId: Long, val matchedMessage: String?)
