package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.LearningItem
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun createLearningItem(): Long
    fun observeLearningItems(): Flow<List<LearningItem>>
    fun observeMessages(learningItemId: Long): Flow<List<ChatMessage>>
    suspend fun sendMessage(learningItemId: Long, userMessage: String)
    suspend fun markReviewed(learningItemId: Long)
    suspend fun getDueLearningItems(now: Long): List<LearningItem>
}
