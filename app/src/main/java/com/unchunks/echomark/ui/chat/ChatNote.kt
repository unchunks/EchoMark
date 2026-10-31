package com.unchunks.echomark.ui.chat

import com.unchunks.components.composeui.ChatMarkdown
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.model.ChatMessage

/** 回答から作るメモの内容。 */
data class ChatNote(val title: String, val content: String)

/** 回答をメモとして残すときの形を作る。Android 依存なしの純粋関数。 */
object ChatNotes {
    private const val SOURCES_HEADER = "引用元:"

    /**
     * 引用番号は会話の中でしか意味が無いため本文から除き、引用した保存の名前を末尾に書き出す。
     * @param question この回答の質問。タイトルに使う。無ければ回答の冒頭を使う
     * @param sources 回答が引用した保存(番号の順)
     */
    fun from(question: String?, answer: ChatMessage, sources: List<Bookmark>): ChatNote {
        val body = ChatMarkdown.toPlainText(RagSupport.withoutCitations(answer).content).trim()
        val sourceLines = sources.map { source -> "- ${source.title}" + source.contentUri?.let { " ($it)" }.orEmpty() }
        val content = listOfNotNull(
            body,
            (listOf(SOURCES_HEADER) + sourceLines).joinToString("\n").takeIf { sourceLines.isNotEmpty() }
        ).joinToString("\n\n")
        return ChatNote(RagSupport.titleFrom(question?.takeIf { it.isNotBlank() } ?: body), content)
    }
}
