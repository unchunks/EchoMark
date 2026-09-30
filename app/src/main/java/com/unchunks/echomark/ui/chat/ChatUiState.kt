package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.model.ChatMessage

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** 引用チップ表示用の bookmarkId -> タイトル。削除済みブックマークは含まれない。 */
    val citationTitles: Map<Long, String> = emptyMap(),
    val isSending: Boolean = false,
    val errorMessage: String? = null
)
