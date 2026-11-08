package com.unchunks.echomark.domain.chat

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** クラウド API に渡す量と、履歴の引用番号の扱い。 */
class ChatContextLimitsTest {

    @Test
    fun 文脈の長いモデルほど履歴を長く残し_上限で止める() {
        assertEquals(24, ChatContextLimits.forCloud(60_000).historyMessages)
        assertEquals(ChatContextLimits.MAX_CLOUD_HISTORY_MESSAGES, ChatContextLimits.forCloud(150_000).historyMessages)
    }

    @Test
    fun 文脈長が分からないモデルでも端末内より少なくしない() {
        val limits = ChatContextLimits.forCloud(1_000)

        assertEquals(ChatContextLimits.COMPACT.historyMessages, limits.historyMessages)
        assertTrue(limits.maxSources > ChatContextLimits.COMPACT.maxSources)
    }

    @Test
    fun 履歴の回答から引用番号を除く() {
        val answer = ChatMessage(conversationId = 1, role = ChatRole.ASSISTANT, content = "要点です [1]。続きです [2, 3]。[4]", createdAt = 0)

        assertEquals("要点です。続きです。", RagSupport.withoutCitations(answer).content)
    }

    @Test
    fun ユーザーの発言の角括弧は残す() {
        val question = ChatMessage(conversationId = 1, role = ChatRole.USER, content = "配列の [1] は何番目？", createdAt = 0)

        assertEquals("配列の [1] は何番目？", RagSupport.withoutCitations(question).content)
    }
}
