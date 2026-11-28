package com.unchunks.echomark.ui.chat

import com.unchunks.components.composeui.ChatMarkdown
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.bookmark.model.EvidenceFinder
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.model.ChatMessage

/** 回答を読み上げ用の文にする。Android 依存なしの純粋関数。 */
object AnswerSpeech {
    /** 1回に読み上げに渡す文字数の上限(TextToSpeech の上限より十分小さくし、途中で止めやすくする) */
    const val MAX_PART_CHARS = 1_000

    /**
     * 読み上げる文を、順に渡す単位に分ける。引用番号は読まず、コードは読み上げても分からないため飛ばす。
     * 記号や「(停止)」の印を読まないよう、Markdown の記法も外す。
     */
    fun partsOf(answer: String): List<String> {
        val plain = RagSupport.withoutCitations(
            ChatMessage(conversationId = 0, role = ChatRole.ASSISTANT, content = answer, createdAt = 0)
        ).content
        val lines = ChatMarkdown.parse(plain).mapNotNull { block ->
            when (block) {
                is ChatMarkdown.Block.Heading -> block.spans.joinToString("") { it.text }
                is ChatMarkdown.Block.Paragraph -> block.spans.joinToString("") { it.text }
                is ChatMarkdown.Block.ListItem -> block.spans.joinToString("") { it.text }
                is ChatMarkdown.Block.Code, ChatMarkdown.Block.Divider -> null
            }
        }
        val sentences = lines.flatMap(EvidenceFinder::splitSentences)
            .filter { it != STOPPED_MARK }
            .flatMap { it.chunked(MAX_PART_CHARS) }
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > MAX_PART_CHARS) {
                parts += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(sentence)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private const val STOPPED_MARK = "(停止)"
}
