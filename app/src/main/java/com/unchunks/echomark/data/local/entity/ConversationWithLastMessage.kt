package com.unchunks.echomark.data.local.entity

import androidx.room.Embedded
import com.unchunks.echomark.domain.model.ChatRole

/** 会話一覧用。会話と、その最後のメッセージ(本文とロール)。メッセージが無ければ null。 */
data class ConversationWithLastMessage(
    @Embedded val conversation: ConversationEntity,
    val lastMessage: String?,
    val lastMessageRole: ChatRole?
)
