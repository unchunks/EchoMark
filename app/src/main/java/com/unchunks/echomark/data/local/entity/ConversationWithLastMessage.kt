package com.unchunks.echomark.data.local.entity

import androidx.room.Embedded
import com.unchunks.echomark.domain.model.ChatRole

/** 会話一覧用。会話と、その最後のメッセージ(本文とロール。メッセージが無ければ null)と、質問の対象のブックマーク名。 */
data class ConversationWithLastMessage(
    @Embedded val conversation: ConversationEntity,
    val lastMessage: String?,
    val lastMessageRole: ChatRole?,
    /** 「このブックマークについて質問」の対象のタイトル。通常の会話・対象が削除済みなら null。 */
    val aboutBookmarkTitle: String?
)
