package com.unchunks.echomark.domain.model

import com.unchunks.components.llm.ChatRole

data class ChatMessage(
    val id: Long = 0,
    val conversationId: Long,
    val role: ChatRole,
    val content: String,
    val referencedBookmarkIds: List<Long> = emptyList(),
    val createdAt: Long
)
