package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun createConversation(): Long
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>>
    suspend fun sendMessage(conversationId: Long, userMessage: String)
}
