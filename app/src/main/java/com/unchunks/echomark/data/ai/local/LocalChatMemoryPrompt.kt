package com.unchunks.echomark.data.ai.local

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.domain.model.ChatMessage

/**
 * 会話の古い発言を要約するプロンプトを組み立てる。Android に依存しない純粋な処理で、単体テストできる。
 * 文脈の上限を超えないよう、発言は全体の文字数([maxChars])を発言の数で割った長さまでに切り詰める。
 */
internal object LocalChatMemoryPrompt {
    /** プロンプト全体の文字数の上限。遅い端末の読み込みの時間を抑えるよう、チャットのプロンプトと同じくらいにする */
    const val MAX_PROMPT_CHARS = 1_800

    private const val MAX_MESSAGE_CHARS = 300
    private const val ELLIPSIS = "…"

    /**
     * @param previousSummary これまでの要約。無ければ null
     * @param messages 要約に加える発言(古い順)
     */
    fun build(previousSummary: String?, messages: List<ChatMessage>, maxChars: Int = MAX_PROMPT_CHARS): String {
        val instructions = AiPrompts.conversationMemoryInstructions(LocalChatPrompt.MAX_MEMORY_CHARS)
        val previous = previousSummary?.trim()?.takeIf { it.isNotEmpty() }
        val fixedChars = instructions.length + (previous?.length ?: 0) + HEADERS_CHARS
        val perMessage = ((maxChars - fixedChars) / messages.size.coerceAtLeast(1)).coerceIn(MIN_MESSAGE_CHARS, MAX_MESSAGE_CHARS)
        return buildList {
            add(instructions)
            add("")
            if (previous != null) {
                add("これまでの要約:")
                add(previous)
                add("")
            }
            add("新しい発言:")
            messages.forEach { message ->
                val speaker = if (message.role == ChatRole.USER) "ユーザー" else "アシスタント"
                add("$speaker: ${truncate(message.content, perMessage)}")
            }
        }.joinToString("\n")
    }

    private fun truncate(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - ELLIPSIS.length) + ELLIPSIS

    /** 見出しと、発言ごとの話し手の名前の分の文字数の見積もり */
    private const val HEADERS_CHARS = 100

    /** 発言1件に残す最小の文字数(短くしすぎて内容が分からなくならないように) */
    private const val MIN_MESSAGE_CHARS = 40
}
