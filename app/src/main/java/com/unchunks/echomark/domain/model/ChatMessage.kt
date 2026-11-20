package com.unchunks.echomark.domain.model

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.websearch.WebSource

/**
 * @property referencedBookmarkIds 回答の文脈に入れた資料の ID(n 番目が引用番号 n)
 * @property webSources 回答の文脈に入れた Web のページ(Web 検索をオンにした質問の回答だけ。引用番号は資料の続き)
 */
data class ChatMessage(
    val id: Long = 0,
    val conversationId: Long,
    val role: ChatRole,
    val content: String,
    val referencedBookmarkIds: List<Long> = emptyList(),
    val createdAt: Long,
    val webSources: List<WebSource> = emptyList()
)
