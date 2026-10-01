package com.unchunks.echomark.domain.model

/** 会話一覧の1行分。会話と、最後のメッセージ(まだ無ければ null)と、質問の対象のブックマーク名。 */
data class ConversationPreview(
    val conversation: Conversation,
    val lastMessage: String? = null,
    val lastMessageRole: ChatRole? = null,
    /** 「このブックマークについて質問」の対象のタイトル。通常の会話・対象が削除済みなら null。 */
    val aboutBookmarkTitle: String? = null
)
