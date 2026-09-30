package com.unchunks.echomark.domain.model

/** 会話一覧の1行分。会話と、最後のメッセージ(まだ無ければ null)。 */
data class ConversationPreview(
    val conversation: Conversation,
    val lastMessage: String? = null,
    val lastMessageRole: ChatRole? = null
)
