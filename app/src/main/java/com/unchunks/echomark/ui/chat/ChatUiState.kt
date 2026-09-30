package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.model.ChatMessage

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** 引用チップ表示用の bookmarkId -> タイトル。削除済みブックマークは含まれない。 */
    val citationTitles: Map<Long, String> = emptyMap(),
    val isSending: Boolean = false,
    /**
     * 生成中の回答(先頭からの全文)。生成中でなければ null。
     * 送信直後で最初の文字がまだ届いていない間は空文字。完了すると [messages] 側に保存済みの回答が現れる。
     */
    val streamingText: String? = null,
    val errorMessage: String? = null
)
