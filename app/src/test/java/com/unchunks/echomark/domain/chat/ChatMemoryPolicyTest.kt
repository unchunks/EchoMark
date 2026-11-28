package com.unchunks.echomark.domain.chat

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.ChatContextUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryPolicyTest {

    private fun messages(count: Int) = (1..count).map {
        ChatMessage(id = it.toLong(), conversationId = 1, role = ChatRole.USER, content = "発言$it", createdAt = it * 10L)
    }

    @Test
    fun 直近の発言の外に4件たまるまでは要約しない() {
        assertTrue(ChatMemoryPolicy.nextBatch(messages(RagSupport.HISTORY_LIMIT + 3), tight = false).isEmpty())
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            ChatMemoryPolicy.nextBatch(messages(RagSupport.HISTORY_LIMIT + 4), tight = false).map { it.id }
        )
    }

    @Test
    fun 文脈に余裕が無ければ直近の1往復だけ残して早めに要約する() {
        assertTrue(ChatMemoryPolicy.nextBatch(messages(3), tight = true).isEmpty())
        assertEquals(listOf(1L, 2L), ChatMemoryPolicy.nextBatch(messages(4), tight = true).map { it.id })
    }

    @Test
    fun 一度に要約する発言は上限までで_古いものから進める() {
        val batch = ChatMemoryPolicy.nextBatch(messages(ChatMemoryPolicy.FETCH_LIMIT), tight = false)

        assertEquals((1L..8L).toList(), batch.map { it.id })
    }

    @Test
    fun 要約に含めた発言を履歴から外す() {
        assertEquals(listOf(3L, 4L), ChatMemoryPolicy.notCovered(messages(4), coveredUntil = 20).map { it.id })
        assertEquals(4, ChatMemoryPolicy.notCovered(messages(4), coveredUntil = null).size)
    }

    @Test
    fun 文脈の75パーセントを超えたか入力を減らしたら余裕が無いとみなす() {
        assertFalse(ChatContextUsage(usedTokens = 3071, maxTokens = 4096, promptShrunk = false).isTight)
        assertTrue(ChatContextUsage(usedTokens = 3072, maxTokens = 4096, promptShrunk = false).isTight)
        assertTrue(ChatContextUsage(usedTokens = 100, maxTokens = 4096, promptShrunk = true).isTight)
        // 数えられなくても、入力を減らしていなければ余裕があるとみなす
        assertFalse(ChatContextUsage(usedTokens = null, maxTokens = 4096, promptShrunk = false).isTight)
    }
}
