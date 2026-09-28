package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation


fun ConversationEntity.toDomain() = Conversation(
    id = id, title = title, isTitleManuallySet = isTitleManuallySet,
    summary = summary, createdAt = createdAt, updatedAt = updatedAt
)

fun ChatMessageEntity.toDomain() = ChatMessage(
    id = id, conversationId = conversationId, role = role, content = content,
    referencedBookmarkIds = referencedBookmarkIds
        ?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
    createdAt = createdAt
)
