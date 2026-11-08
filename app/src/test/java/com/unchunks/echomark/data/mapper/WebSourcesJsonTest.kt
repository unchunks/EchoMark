package com.unchunks.echomark.data.mapper

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.websearch.WebSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourcesJsonTest {

    private val sources = listOf(
        WebSource(3, "https://example.com/a", "記事の題", "example.com", "抜粋の\"文\""),
        WebSource(4, "https://docs.example.org/b", "docs.example.org", "docs.example.org", "")
    )

    @Test
    fun 書いて読むと同じものに戻る() {
        assertEquals(sources, WebSourcesJson.decode(WebSourcesJson.encode(sources)))
    }

    @Test
    fun 空なら列に何も入れない() {
        assertNull(WebSourcesJson.encode(emptyList()))
        assertTrue(WebSourcesJson.decode(null).isEmpty())
        assertTrue(WebSourcesJson.decode("").isEmpty())
    }

    @Test
    fun 壊れたもの_形のおかしいものは除く() {
        assertTrue(WebSourcesJson.decode("not json").isEmpty())
        val decoded = WebSourcesJson.decode(
            """[{"n":1,"url":"javascript:alert(1)","title":"x"},{"url":"https://example.com","title":"番号なし"},
               |{"n":2,"url":"https://example.com/ok"},{"n":2,"url":"https://example.com/dup"},"文字"]""".trimMargin()
        )
        assertEquals(1, decoded.size)
        assertEquals(2, decoded[0].number)
        // タイトル・ドメインが無ければ URL のドメインにする
        assertEquals("example.com", decoded[0].title)
        assertEquals("example.com", decoded[0].domain)
    }

    @Test
    fun 回答のメッセージの列と行き来する() {
        val message = ChatMessage(
            id = 1, conversationId = 2, role = ChatRole.ASSISTANT, content = "答え[3]", createdAt = 10, webSources = sources
        )

        val entity = message.toEntity()

        assertTrue(entity.webSources!!.contains("https://example.com/a"))
        assertEquals(message, entity.toDomain())
        assertTrue(ChatMessageEntity(conversationId = 2, role = ChatRole.USER, content = "q", createdAt = 1).toDomain().webSources.isEmpty())
    }
}
