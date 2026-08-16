package com.unchunks.echomark.domain.provider

interface LlmProvider {
    suspend fun summarize(text: String): String
    suspend fun generateTags(text: String): List<String>
    suspend fun categorize(text: String): String
    suspend fun chat(userMessage: String, context: List<String>): String
}
