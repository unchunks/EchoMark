package com.unchunks.echomark.domain.model

data class ChatMessage(
    val id: Long = 0,
    val learningItemId: Long,
    val role: ChatRole,
    val content: String,
    val referencedBookmarkIds: List<Long> = emptyList(),
    val createdAt: Long
)
