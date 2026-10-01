package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.ConversationWithLastMessage
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview


fun ConversationEntity.toDomain() = Conversation(
    id = id, title = title, isTitleManuallySet = isTitleManuallySet,
    summary = summary, createdAt = createdAt, updatedAt = updatedAt, aboutBookmarkId = aboutBookmarkId
)

fun ChatMessageEntity.toDomain() = ChatMessage(
    id = id, conversationId = conversationId, role = role, content = content,
    referencedBookmarkIds = referencedBookmarkIds
        ?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
    createdAt = createdAt
)

fun ConversationWithLastMessage.toDomain() = ConversationPreview(
    conversation = conversation.toDomain(),
    lastMessage = lastMessage,
    lastMessageRole = lastMessageRole,
    aboutBookmarkTitle = aboutBookmarkTitle
)

// 削除の取り消しで、同じ ID のまま書き戻すために使う
fun Conversation.toEntity() = ConversationEntity(
    id = id, title = title, isTitleManuallySet = isTitleManuallySet,
    summary = summary, createdAt = createdAt, updatedAt = updatedAt, aboutBookmarkId = aboutBookmarkId
)

fun ChatMessage.toEntity() = ChatMessageEntity(
    id = id, conversationId = conversationId, role = role, content = content,
    referencedBookmarkIds = referencedBookmarkIds.takeIf { it.isNotEmpty() }?.joinToString(","),
    createdAt = createdAt
)
