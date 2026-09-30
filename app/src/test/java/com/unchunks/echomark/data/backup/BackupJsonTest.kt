package com.unchunks.echomark.data.backup

import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.ChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonTest {

    private val sample = BackupData(
        exportedAt = 1_700_000_000_000L,
        bookmarks = listOf(
            BookmarkEntity(
                id = 1, type = BookmarkType.URL, content = "本文\n\"引用\"つき", contentUri = "https://example.com/a",
                title = "記事A", summary = "要約", category = "技術", createdAt = 100, lastAccessedAt = 200,
                aiStatus = AiStatus.DONE, imageUrl = "https://example.com/og.png", siteName = "Example",
                isFavorite = true, isArchived = false
            ),
            BookmarkEntity(
                id = 2, type = BookmarkType.TEXT, content = null, contentUri = null, title = "メモ",
                createdAt = 300, lastAccessedAt = 300, aiStatus = AiStatus.FAILED, isArchived = true
            )
        ),
        tags = listOf(TagEntity(10, "kotlin"), TagEntity(11, "android")),
        bookmarkTags = listOf(BookmarkTagCrossRef(1, 10), BookmarkTagCrossRef(1, 11)),
        conversations = listOf(
            ConversationEntity(id = 5, title = "質問", isTitleManuallySet = true, summary = null, createdAt = 400, updatedAt = 500)
        ),
        messages = listOf(
            ChatMessageEntity(id = 7, conversationId = 5, role = ChatRole.USER, content = "Aは?", createdAt = 401),
            ChatMessageEntity(
                id = 8, conversationId = 5, role = ChatRole.ASSISTANT, content = "Aは…",
                referencedBookmarkIds = "1,2", createdAt = 402
            )
        )
    )

    @Test
    fun 書き出して読み込むと全列が元に戻る() {
        val decoded = BackupJson.decode(BackupJson.encode(sample))
        assertEquals(sample, decoded.data)
        assertEquals(0, decoded.invalidRecords)
    }

    @Test
    fun 形式名とバージョン番号を含む() {
        val json = JSONObject(BackupJson.encode(sample))
        assertEquals("echomark-backup", json.getString("format"))
        assertEquals(BackupJson.CURRENT_VERSION, json.getInt("version"))
        // 引用 ID は数値の配列で持つ
        val refs = json.getJSONArray("messages").getJSONObject(1).getJSONArray("referencedBookmarkIds")
        assertEquals(listOf(1L, 2L), (0 until refs.length()).map { refs.getLong(it) })
    }

    @Test
    fun APIキーやモデルに関する項目は含まない() {
        val text = BackupJson.encode(sample).lowercase()
        assertFalse(text.contains("apikey"))
        assertFalse(text.contains("api_key"))
        assertFalse(text.contains("model"))
    }

    @Test
    fun 空のデータも読み書きできる() {
        val empty = BackupData(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(empty, BackupJson.decode(BackupJson.encode(empty)).data)
    }

    @Test
    fun JSONでないファイルはエラー() {
        val e = assertThrows(BackupFormatException::class.java) { BackupJson.decode("not json") }
        assertTrue(e.message!!.contains("JSON"))
    }

    @Test
    fun 別の形式のJSONはエラー() {
        assertThrows(BackupFormatException::class.java) { BackupJson.decode("""{"bookmarks":[]}""") }
    }

    @Test
    fun 新しいバージョンのバックアップはエラー() {
        val e = assertThrows(BackupFormatException::class.java) {
            BackupJson.decode("""{"format":"echomark-backup","version":99}""")
        }
        assertTrue(e.message!!.contains("アプリを更新"))
    }

    @Test
    fun 壊れた要素だけ読み飛ばして数える() {
        val text = """
            {"format":"echomark-backup","version":1,"exportedAt":1,
             "bookmarks":[
               {"id":1,"type":"URL","contentUri":"https://a","title":"ok","createdAt":10},
               {"id":2,"type":"VIDEO","title":"未知の種類","createdAt":10},
               {"id":3,"type":"TEXT","createdAt":10},
               {"id":4,"type":"TEXT","title":"未知の状態","createdAt":10,"aiStatus":"SOMETHING"}
             ],
             "tags":[{"id":1,"name":""}],
             "messages":[{"id":1,"conversationId":1,"role":"SYSTEM","content":"x","createdAt":1}]}
        """.trimIndent()
        val decoded = BackupJson.decode(text)

        assertEquals(listOf(1L, 4L), decoded.data.bookmarks.map { it.id })
        assertEquals(4, decoded.invalidRecords)
        // 省略された列は既定値で補う
        val first = decoded.data.bookmarks[0]
        assertEquals(10L, first.lastAccessedAt)
        assertNull(first.content)
        assertEquals(AiStatus.WAITING_MODEL, first.aiStatus)
        assertEquals(AiStatus.WAITING_MODEL, decoded.data.bookmarks[1].aiStatus)
    }

    @Test
    fun 引用IDの変換() {
        assertEquals(listOf(1L, 22L), parseReferencedIds("1, 22,x"))
        assertEquals(emptyList<Long>(), parseReferencedIds(null))
        assertEquals("3,4", formatReferencedIds(listOf(3, 4)))
        assertNull(formatReferencedIds(emptyList()))
    }
}
