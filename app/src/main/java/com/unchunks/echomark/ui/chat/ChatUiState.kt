package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.model.ChatMessage

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSending: Boolean = false
)
