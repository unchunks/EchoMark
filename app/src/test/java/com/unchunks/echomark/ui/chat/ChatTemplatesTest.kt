package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.model.ChatScopeChoices
import com.unchunks.echomark.domain.model.ChatTemplate
import com.unchunks.echomark.domain.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** テンプレートの追加の決まりと、参照する範囲の説明。 */
class ChatTemplatesTest {

    @Test
    fun 新しいテンプレートを先頭に足し_長い文は名前を縮める() {
        val existing = listOf(ChatTemplate("1", "前", "前の文"))
        val long = "あ".repeat(40)

        val result = ChatTemplates.add(existing, "  $long  ", "2")

        assertEquals(listOf("2", "1"), result.map { it.id })
        assertEquals(long, result[0].text)
        assertEquals("あ".repeat(24) + "…", result[0].title)
    }

    @Test
    fun 空の文と同じ文は足さない() {
        val existing = listOf(ChatTemplate("1", "前", "前の文"))

        assertEquals(existing, ChatTemplates.add(existing, "   ", "2"))
        assertEquals(existing, ChatTemplates.add(existing, " 前の文 ", "2"))
    }

    @Test
    fun 上限に達したら足さない() {
        val full = (1..ChatTemplates.MAX_USER_TEMPLATES).map { ChatTemplate("$it", "t$it", "文$it") }

        assertEquals(full, ChatTemplates.add(full, "新しい文", "new"))
    }

    @Test
    fun 最初から入っているテンプレートのidは重ならない() {
        assertEquals(ChatTemplates.builtIn.size, ChatTemplates.builtIn.map { it.id }.distinct().size)
        assertTrue(ChatTemplates.builtIn.all { it.text.isNotBlank() })
    }

    private val choices = ChatScopeChoices(tags = listOf(Tag(1L, "技術"), Tag(2L, "読書"), Tag(3L, "旅行")))

    @Test
    fun 絞っていない範囲に説明は無い() {
        assertNull(choices.describe(ChatScopeChoices.ALL))
    }

    @Test
    fun 範囲の説明は名前を並べ_多ければ残りの数にする() {
        assertEquals("お気に入り・技術", choices.describe(BookmarkQuery(filter = BookmarkFilter.FAVORITES, tagIds = setOf(1L))))
        assertEquals(
            "技術・読書 ほか2件",
            choices.describe(BookmarkQuery(tagIds = setOf(1L, 2L, 3L), categories = setOf("料理")))
        )
    }
}
