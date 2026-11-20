package com.unchunks.echomark.data.ai.local

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.domain.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatMemoryPromptTest {

    private fun message(role: ChatRole, content: String) =
        ChatMessage(conversationId = 1, role = role, content = content, createdAt = 0)

    @Test
    fun これまでの要約と新しい発言を順に並べる() {
        val prompt = LocalChatMemoryPrompt.build(
            previousSummary = "旅行の話をした",
            messages = listOf(message(ChatRole.USER, "予算は?"), message(ChatRole.ASSISTANT, "5万円です"))
        )

        assertEquals(
            listOf(
                AiPrompts.conversationMemoryInstructions(LocalChatPrompt.MAX_MEMORY_CHARS),
                "",
                "これまでの要約:",
                "旅行の話をした",
                "",
                "新しい発言:",
                "ユーザー: 予算は?",
                "アシスタント: 5万円です"
            ).joinToString("\n"),
            prompt
        )
    }

    @Test
    fun 要約がまだ無ければ新しい発言だけを渡す() {
        val prompt = LocalChatMemoryPrompt.build(null, listOf(message(ChatRole.USER, "こんにちは")))

        assertFalse(prompt.contains("これまでの要約:"))
    }

    @Test
    fun 長い発言は全体の文字数の予算に収まるよう切り詰める() {
        val messages = (1..8).map { message(ChatRole.USER, "あ".repeat(5_000)) }

        val prompt = LocalChatMemoryPrompt.build("い".repeat(300), messages)
        val shrunk = LocalChatMemoryPrompt.build("い".repeat(300), messages, maxChars = LocalChatMemoryPrompt.MAX_PROMPT_CHARS * 60 / 100)

        assertTrue(prompt.length.toString(), prompt.length <= LocalChatMemoryPrompt.MAX_PROMPT_CHARS)
        assertTrue("やり直すときは短くなる", shrunk.length < prompt.length)
        assertTrue(prompt.contains("…"))
    }
}
