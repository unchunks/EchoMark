package com.unchunks.echomark.data.ai.local

import com.unchunks.components.llm.ChatRole
import com.unchunks.components.llm.ondevice.LocalChatTurn
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.domain.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatPromptTest {

    private fun message(role: ChatRole, content: String) =
        ChatMessage(conversationId = 1, role = role, content = content, createdAt = 0)

    @Test
    fun 短い入力は指示_文脈_履歴_質問の順に並べる() {
        val prompt = LocalChatPrompt.build(
            userMessage = "Kotlin の記事は?",
            context = listOf("[1] 記事: 要約"),
            history = listOf(message(ChatRole.USER, "こんにちは"), message(ChatRole.ASSISTANT, "どうぞ"))
        )

        val expected = listOf(
            AiPrompts.chatInstructions(listOf("[1] 記事: 要約"), compact = true),
            "",
            "これまでの会話:",
            "ユーザー: こんにちは",
            "アシスタント: どうぞ",
            "",
            "質問: Kotlin の記事は?"
        ).joinToString("\n")
        assertEquals(expected, prompt)
    }

    @Test
    fun 長文を貼り付けても全体の文字数の予算に収める() {
        val longMessage = "あ".repeat(20_000)

        val prompt = LocalChatPrompt.build(longMessage, listOf("[1] 記事: 要約"), emptyList())

        assertTrue(prompt.length <= LocalChatPrompt.MAX_PROMPT_CHARS)
        assertTrue(prompt.contains("質問: " + "あ".repeat(100)))
        // 質問の後ろが切れていることが分かる
        assertTrue(prompt.endsWith("…"))
    }

    @Test
    fun 履歴は新しいものを優先して予算内に収める() {
        val history = (1..6).map { message(if (it % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, "発言$it " + "い".repeat(500)) }
        val context = (1..3).map { "[$it] 記事$it: " + "う".repeat(300) }

        val prompt = LocalChatPrompt.build("質問です", context, history)

        assertTrue(prompt.length <= LocalChatPrompt.MAX_PROMPT_CHARS)
        // 文脈(回答の根拠)は履歴より優先する
        context.forEach { assertTrue(it.take(20), prompt.contains(it)) }
        assertTrue(prompt.contains("発言6"))
        assertFalse(prompt.contains("発言1"))
        assertTrue(prompt.endsWith("質問: 質問です"))
    }

    @Test
    fun 文脈が多すぎるときは後ろのものから削る() {
        val context = (1..5).map { "[$it] 記事$it: " + "え".repeat(1_000) }

        val prompt = LocalChatPrompt.build("質問です", context, emptyList())

        assertTrue(prompt.length <= LocalChatPrompt.MAX_PROMPT_CHARS)
        assertTrue(prompt.contains("[1] 記事1"))
        assertFalse(prompt.contains("[5] 記事5"))
        assertTrue(prompt.endsWith("質問: 質問です"))
    }

    @Test
    fun 指示と記憶はシステムの指示に_履歴は発言ごとに分ける() {
        val input = LocalChatPrompt.input(
            userMessage = " 続きは? ",
            context = listOf("[1] 記事: 要約"),
            history = listOf(message(ChatRole.USER, "こんにちは"), message(ChatRole.ASSISTANT, "どうぞ")),
            memory = "旅行の計画を話した"
        )

        assertEquals(
            AiPrompts.chatInstructions(listOf("[1] 記事: 要約"), compact = true) + "\n\nさらに前の会話の要約:\n旅行の計画を話した",
            input.systemInstruction
        )
        assertEquals(listOf(LocalChatTurn(true, "こんにちは"), LocalChatTurn(false, "どうぞ")), input.history)
        assertEquals("続きは?", input.message)
        // まとめたプロンプトにも同じものが同じ順で入る
        assertEquals(
            listOf(input.systemInstruction, "", "これまでの会話:", "ユーザー: こんにちは", "アシスタント: どうぞ", "", "質問: 続きは?")
                .joinToString("\n"),
            input.plainPrompt
        )
    }

    @Test
    fun 記憶は文脈に押し出されず_全体は予算に収まる() {
        val context = (1..5).map { "[$it] 記事$it: " + "う".repeat(600) }

        val memory = "え".repeat(LocalChatPrompt.MAX_MEMORY_CHARS * 2)
        val input = LocalChatPrompt.input("質問です", context, emptyList(), memory = memory)

        assertTrue(input.systemInstruction.contains("さらに前の会話の要約:\n" + "え".repeat(100)))
        // 長い記憶は上限で切る
        assertTrue(input.systemInstruction.endsWith("…"))
        assertFalse(input.systemInstruction.contains("え".repeat(LocalChatPrompt.MAX_MEMORY_CHARS)))
        assertTrue(input.plainPrompt.length <= LocalChatPrompt.MAX_PROMPT_CHARS)
    }

    @Test
    fun 入力を減らしてやり直すときは記憶も短くする() {
        val memory = "え".repeat(LocalChatPrompt.MAX_MEMORY_CHARS)

        val input = LocalChatPrompt.input("質問です", listOf("[1] 記事: 要約"), emptyList(), maxChars = 300, memory = memory)

        assertTrue(input.plainPrompt.length.toString(), input.plainPrompt.length <= 300)
        assertTrue(input.systemInstruction.contains("えええ"))
    }

    @Test
    fun 履歴はユーザーから始めて交互に並べ_回答で終える() {
        val input = LocalChatPrompt.input(
            userMessage = "質問",
            context = emptyList(),
            history = listOf(
                message(ChatRole.ASSISTANT, "前の回答"),
                message(ChatRole.USER, "失敗した質問"),
                message(ChatRole.USER, "質問1"),
                message(ChatRole.ASSISTANT, "回答1"),
                message(ChatRole.USER, "回答の無い質問")
            )
        )

        assertEquals(listOf(LocalChatTurn(true, "質問1"), LocalChatTurn(false, "回答1")), input.history)
    }
}
