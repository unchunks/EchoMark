package com.unchunks.echomark.ui.chat

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.testing.testBookmark
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回答から作るメモの形。 */
class ChatNotesTest {

    private fun answer(content: String) =
        ChatMessage(conversationId = 1, role = ChatRole.ASSISTANT, content = content, createdAt = 0)

    @Test
    fun 引用番号を除き_引用した保存を末尾に書き出す() {
        val saved = testBookmark(1, title = "記事").copy(contentUri = "https://example.com/a")
        val memo = testBookmark(2, title = "メモ")

        val note = ChatNotes.from("要点は？", answer("**要点**です [1]。続きです [1, 2]。"), listOf(saved, memo))

        assertEquals("要点は？", note.title)
        assertEquals("要点です。続きです。\n\n引用元:\n- 記事 (https://example.com/a)\n- メモ", note.content)
    }

    @Test
    fun 引用が無ければ本文だけにする() {
        assertEquals("回答です", ChatNotes.from("質問", answer("回答です"), emptyList()).content)
    }

    @Test
    fun 質問が無ければ回答の冒頭をタイトルにする() {
        val note = ChatNotes.from(null, answer("あ".repeat(40)), emptyList())

        assertEquals("あ".repeat(30) + "…", note.title)
    }

    @Test
    fun 検索語の周りを切り出した抜粋を作る() {
        val message = "あ".repeat(50) + "コルーチン" + "い".repeat(10)

        assertEquals("…" + "あ".repeat(16) + "コルーチン" + "い".repeat(10), conversationMatchExcerpt(message, "コルーチン"))
        assertEquals("短い発言", conversationMatchExcerpt("短い発言", "発言"))
        assertEquals("語が無い発言", conversationMatchExcerpt("語が無い発言", "別の語"))
    }
}
