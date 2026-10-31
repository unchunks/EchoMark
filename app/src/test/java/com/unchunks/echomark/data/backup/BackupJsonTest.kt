package com.unchunks.echomark.data.backup

import com.unchunks.echomark.data.local.entity.CategoryEntity
import com.unchunks.echomark.data.local.entity.TagAliasEntity
import com.unchunks.echomark.data.local.entity.RemovedTagEntity
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.model.TagSource
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
                isFavorite = true, isArchived = false, contentFetchedAt = 150, readAt = 200
            ),
            BookmarkEntity(
                id = 2, type = BookmarkType.TEXT, content = null, contentUri = null, title = "メモ",
                createdAt = 300, lastAccessedAt = 300, aiStatus = AiStatus.FAILED, isArchived = true
            ),
            BookmarkEntity(
                id = 3, type = BookmarkType.IMAGE, content = "読み取った文字", title = "写真",
                createdAt = 310, lastAccessedAt = 310, aiStatus = AiStatus.DONE,
                filePath = "attachments/0f8e-11.jpg", mimeType = "image/jpeg", fileName = "IMG_0001.jpg", fileSize = 1234L
            )
        ),
        tags = listOf(TagEntity(10, "kotlin", isUserCreated = true), TagEntity(11, "android")),
        bookmarkTags = listOf(BookmarkTagCrossRef(1, 10, TagSource.USER), BookmarkTagCrossRef(1, 11, TagSource.AI)),
        conversations = listOf(
            ConversationEntity(
                id = 5, title = "質問", isTitleManuallySet = true, summary = "これまでの要約", createdAt = 400, updatedAt = 500,
                summaryCoveredUntil = 401
            ),
            ConversationEntity(id = 6, title = "記事Aについて", isTitleManuallySet = true, createdAt = 600, updatedAt = 700, aboutBookmarkId = 1)
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
    fun 読んだ日時を書き出し_未読は書かない() {
        val text = BackupJson.encode(sample)
        val bookmarks = JSONObject(text).getJSONArray("bookmarks")
        assertEquals(200L, bookmarks.getJSONObject(0).getLong("readAt"))
        assertFalse(bookmarks.getJSONObject(1).has("readAt"))
    }

    @Test
    fun 読んだ日時の無い古い版のバックアップは_保存したあとに開いたものだけ既読として読む() {
        val decoded = BackupJson.decode(
            """
            {"format":"echomark-backup","version":11,"exportedAt":1,"bookmarks":[
              {"id":1,"type":"TEXT","title":"開いた","createdAt":100,"lastAccessedAt":500,"aiStatus":"DONE"},
              {"id":2,"type":"TEXT","title":"開いていない","createdAt":200,"lastAccessedAt":200,"aiStatus":"DONE"},
              {"id":3,"type":"TEXT","title":"最後に開いた日時なし","createdAt":300,"aiStatus":"DONE"}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf(500L, null, null), decoded.data.bookmarks.map { it.readAt })
    }

    @Test
    fun 古い版のバックアップの会話は要約がまだ無いものとして読む() {
        val decoded = BackupJson.decode(
            """
            {"format":"echomark-backup","version":5,"exportedAt":1,
             "conversations":[{"id":3,"title":"会話","isTitleManuallySet":false,"createdAt":20,"updatedAt":30}]}
            """.trimIndent()
        )

        assertEquals(null, decoded.data.conversations.single().summaryCoveredUntil)
    }

    @Test
    fun 処理の段_日時_失敗の理由は書き出さず_読み込むと空になる() {
        val inProgress = sample.bookmarks[1].copy(
            aiStatus = AiStatus.FAILED, processingStage = ProcessingStage.ANALYZE, queuedAt = 1, processingStartedAt = 2,
            stageStartedAt = 3, processingFinishedAt = 4, failureReason = ProcessingFailureReason.TIMEOUT
        )

        val text = BackupJson.encode(sample.copy(bookmarks = listOf(inProgress)))
        val decoded = BackupJson.decode(text).data.bookmarks.single()

        listOf("processingStage", "queuedAt", "stageStartedAt", "failureReason", "TIMEOUT").forEach {
            assertFalse(it, text.contains(it))
        }
        // 状態そのものは書き出す
        assertEquals(sample.bookmarks[1].copy(aiStatus = AiStatus.FAILED), decoded)
    }

    @Test
    fun ページのオフラインコピーはバックアップに含めない() {
        val withSnapshot = sample.bookmarks[0].copy(snapshotPath = "attachments/x.mhtml", snapshotPdfPath = "attachments/x.pdf", snapshotAt = 123L)

        val text = BackupJson.encode(sample.copy(bookmarks = listOf(withSnapshot)))
        val decoded = BackupJson.decode(text).data.bookmarks.single()

        // ファイルは端末の中にしか無く、バックアップには入れない。読み込んだ端末には、オフラインコピーの記録は無い
        listOf("snapshot", "x.mhtml", "x.pdf").forEach { assertFalse(it, text.contains(it)) }
        assertEquals(null, decoded.snapshotPath)
        assertEquals(null, decoded.snapshotPdfPath)
        assertEquals(null, decoded.snapshotAt)
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
    fun 添付ファイルの置き場所以外を指すパスは読み込まない() {
        val text = """
            {"format":"echomark-backup","version":4,"exportedAt":1,
             "bookmarks":[
               {"id":1,"type":"PDF","title":"a","createdAt":1,"filePath":"../databases/echomark.db","mimeType":"application/pdf"},
               {"id":2,"type":"PDF","title":"b","createdAt":1,"filePath":"attachments/ok.pdf","fileName":"b.pdf","fileSize":10}
             ]}
        """.trimIndent()
        val bookmarks = BackupJson.decode(text).data.bookmarks

        assertNull(bookmarks[0].filePath)
        assertEquals("application/pdf", bookmarks[0].mimeType)
        assertEquals("attachments/ok.pdf", bookmarks[1].filePath)
        assertEquals("b.pdf", bookmarks[1].fileName)
        assertEquals(10L, bookmarks[1].fileSize)
    }

    @Test
    fun ファイル情報の無い古い版はファイルなしとして読む() {
        val text = """
            {"format":"echomark-backup","version":3,"exportedAt":1,
             "bookmarks":[{"id":1,"type":"TEXT","title":"a","createdAt":1}]}
        """.trimIndent()
        val bookmark = BackupJson.decode(text).data.bookmarks.single()

        assertNull(bookmark.filePath)
        assertNull(bookmark.mimeType)
        assertNull(bookmark.fileName)
        assertNull(bookmark.fileSize)
    }

    @Test
    fun 本文の取得日時の無い古い版は_本文のあるURLだけ取得済みとして読む() {
        val text = """
            {"format":"echomark-backup","version":4,"exportedAt":1,
             "bookmarks":[
               {"id":1,"type":"URL","contentUri":"https://example.com/a","content":"本文","title":"a","createdAt":10},
               {"id":2,"type":"URL","contentUri":"https://example.com/b","title":"b","createdAt":20},
               {"id":3,"type":"TEXT","content":"メモ","title":"c","createdAt":30}]}
        """.trimIndent()
        val bookmarks = BackupJson.decode(text).data.bookmarks

        assertEquals(listOf(10L, null, null), bookmarks.map { it.contentFetchedAt })
    }

    @Test
    fun 新しいバージョンのバックアップはエラー() {
        val e = assertThrows(BackupFormatException::class.java) {
            BackupJson.decode("""{"format":"echomark-backup","version":99}""")
        }
        assertTrue(e.message!!.contains("アプリを更新"))
    }

    @Test
    fun 誰が付けたかの無い古い版のタグはユーザーのものとして読む() {
        val text = """
            {"format":"echomark-backup","version":2,"exportedAt":1,
             "tags":[{"id":1,"name":"kotlin"}],
             "bookmarkTags":[{"bookmarkId":1,"tagId":1},{"bookmarkId":2,"tagId":1,"source":"UNKNOWN"}]}
        """.trimIndent()
        val decoded = BackupJson.decode(text)

        assertEquals(listOf(TagEntity(1, "kotlin", isUserCreated = true)), decoded.data.tags)
        assertEquals(
            listOf(BookmarkTagCrossRef(1, 1, TagSource.USER), BookmarkTagCrossRef(2, 1, TagSource.USER)),
            decoded.data.bookmarkTags
        )
        assertEquals(0, decoded.invalidRecords)
    }

    @Test
    fun 壊れた要素だけ読み飛ばして数える() {
        val text = """
            {"format":"echomark-backup","version":1,"exportedAt":1,
             "bookmarks":[
               {"id":1,"type":"URL","contentUri":"https://a","title":"ok","createdAt":10},
               {"id":2,"type":"HOLOGRAM","title":"未知の種類","createdAt":10},
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
    fun URLとOG画像はhttpとhttpsだけを受け付ける() {
        val text = """
            {"format":"echomark-backup","version":1,"exportedAt":1,
             "bookmarks":[
               {"id":1,"type":"URL","contentUri":"HTTPS://example.com/a","title":"ok","createdAt":10,
                "imageUrl":"http://example.com/og.png"},
               {"id":2,"type":"URL","contentUri":"intent://scan/#Intent;scheme=zxing;end","title":"intent","createdAt":10},
               {"id":3,"type":"URL","contentUri":"file:///data/data/com.unchunks.echomark/databases/echomark.db","title":"file","createdAt":10},
               {"id":4,"type":"URL","contentUri":"javascript:alert(1)","title":"js","createdAt":10},
               {"id":5,"type":"URL","contentUri":"content://com.example.provider/secret","title":"content","createdAt":10},
               {"id":6,"type":"URL","contentUri":"https://example.com/b","title":"画像だけ不正","createdAt":10,
                "imageUrl":"content://com.example.provider/image"},
               {"id":7,"type":"URL","contentUri":"https://example.com/c","title":"画像がfile","createdAt":10,
                "imageUrl":"file:///sdcard/a.png"}
             ]}
        """.trimIndent()
        val decoded = BackupJson.decode(text)

        // URL として開けないもの・端末内を指すものは読み飛ばす
        assertEquals(listOf(1L, 6L, 7L), decoded.data.bookmarks.map { it.id })
        assertEquals(4, decoded.invalidRecords)
        assertEquals("http://example.com/og.png", decoded.data.bookmarks[0].imageUrl)
        // OG 画像だけが不正なら、画像を捨ててブックマークは残す
        assertNull(decoded.data.bookmarks[1].imageUrl)
        assertNull(decoded.data.bookmarks[2].imageUrl)
    }

    @Test
    fun URLのないURL型は読み飛ばす() {
        val text = """
            {"format":"echomark-backup","version":1,"exportedAt":1,
             "bookmarks":[{"id":1,"type":"URL","title":"URLなし","createdAt":10}]}
        """.trimIndent()

        val decoded = BackupJson.decode(text)

        assertTrue(decoded.data.bookmarks.isEmpty())
        assertEquals(1, decoded.invalidRecords)
    }

    @Test
    fun 会話の固定と分けた元を書き出して読める() {
        val branched = ConversationEntity(
            id = 8, title = "分岐", createdAt = 1, updatedAt = 2, isPinned = true, branchedFromId = 5, branchedAt = 401
        )

        val decoded = BackupJson.decode(BackupJson.encode(sample.copy(conversations = listOf(branched))))

        assertEquals(branched, decoded.data.conversations.single())
    }

    @Test
    fun version10以前の会話は固定していない_分けていない会話として読む() {
        val json = """
            {"format":"echomark-backup","version":10,"exportedAt":1,
             "conversations":[{"id":3,"title":"会話","isTitleManuallySet":false,"createdAt":20,"updatedAt":30}]}
        """.trimIndent()

        val conversation = BackupJson.decode(json).data.conversations.single()

        assertFalse(conversation.isPinned)
        assertEquals(null, conversation.branchedFromId)
        assertEquals(null, conversation.branchedAt)
    }

    @Test
    fun 通常の会話はaboutBookmarkIdを書き出さない() {
        val conversations = JSONObject(BackupJson.encode(sample)).getJSONArray("conversations")
        assertFalse(conversations.getJSONObject(0).has("aboutBookmarkId"))
        assertEquals(1L, conversations.getJSONObject(1).getLong("aboutBookmarkId"))
    }

    @Test
    fun version1の形式も読み込め_会話は通常の会話になる() {
        val text = """
            {"format":"echomark-backup","version":1,"exportedAt":1,
             "bookmarks":[{"id":1,"type":"URL","contentUri":"https://a","title":"記事","createdAt":10}],
             "tags":[],"bookmarkTags":[],
             "conversations":[{"id":3,"title":"会話","isTitleManuallySet":false,"createdAt":20,"updatedAt":30}],
             "messages":[{"id":1,"conversationId":3,"role":"USER","content":"q","referencedBookmarkIds":[],"createdAt":21}]}
        """.trimIndent()
        val decoded = BackupJson.decode(text)

        assertEquals(0, decoded.invalidRecords)
        assertEquals(
            listOf(ConversationEntity(id = 3, title = "会話", createdAt = 20, updatedAt = 30, aboutBookmarkId = null)),
            decoded.data.conversations
        )
        assertEquals(1, decoded.data.messages.size)
    }

    @Test
    fun 引用IDの変換() {
        assertEquals(listOf(1L, 22L), parseReferencedIds("1, 22,x"))
        assertEquals(emptyList<Long>(), parseReferencedIds(null))
        assertEquals("3,4", formatReferencedIds(listOf(3, 4)))
        assertNull(formatReferencedIds(emptyList()))
    }
    @Test
    fun 手で直した要約の印は書き出して読み込める() {
        val edited = sample.bookmarks[0].copy(summary = "直した要約", summaryEdited = true)
        val data = sample.copy(bookmarks = listOf(edited, sample.bookmarks[1]))

        val json = JSONObject(BackupJson.encode(data))
        val bookmarks = json.getJSONArray("bookmarks")

        assertTrue(bookmarks.getJSONObject(0).getBoolean("summaryEdited"))
        // 手で直していないものには書かない(古い版と同じ形のまま)
        assertFalse(bookmarks.getJSONObject(1).has("summaryEdited"))
        assertEquals(data, BackupJson.decode(json.toString()).data)
    }

    @Test
    fun 古い版のバックアップの要約は手で直していないものとして読む() {
        val decoded = BackupJson.decode(
            """
            {"format":"echomark-backup","version":6,"exportedAt":1,
             "bookmarks":[{"id":1,"type":"TEXT","title":"メモ","summary":"要約","createdAt":10,"aiStatus":"DONE"}]}
            """.trimIndent()
        )

        assertFalse(decoded.data.bookmarks.single().summaryEdited)
        assertEquals("要約", decoded.data.bookmarks.single().summary)
    }


    @Test
    fun タグの親は書き出して読み込め_親が無ければ省略する() {
        val data = sample.copy(
            tags = listOf(
                TagEntity(10, "プログラミング", isUserCreated = true),
                TagEntity(11, "Android", isUserCreated = true, parentId = 10)
            )
        )

        val text = BackupJson.encode(data)
        val decoded = BackupJson.decode(text).data.tags

        assertEquals(data.tags, decoded)
        assertEquals(1, Regex("\"parentId\"").findAll(text.substringAfter("\"tags\":").substringBefore("\"bookmarkTags\"")).count())
    }

    @Test
    fun 階層を持たない古い版のバックアップではタグは最上位として読む() {
        val old = """{"format":"echomark-backup","version":7,"exportedAt":1,"bookmarks":[],
            "tags":[{"id":1,"name":"kotlin","isUserCreated":true}],"bookmarkTags":[],"conversations":[],"messages":[]}"""

        val tags = BackupJson.decode(old).data.tags

        assertEquals(listOf(TagEntity(1, "kotlin", isUserCreated = true, parentId = null)), tags)
    }


    @Test
    fun タグの別名と外したタグの記録を書き出して読み込める() {
        val data = sample.copy(
            tagAliases = listOf(TagAliasEntity(aliasKey = "ネコ".lowercase(), alias = "ネコ", tagId = 10)),
            removedTags = listOf(RemovedTagEntity(bookmarkId = 1, tagKey = "android", tagName = "Android", removedAt = 700))
        )

        val decoded = BackupJson.decode(BackupJson.encode(data)).data

        assertEquals(data.tagAliases, decoded.tagAliases)
        assertEquals(data.removedTags, decoded.removedTags)
    }

    @Test
    fun 別名のキーは別名から作り直し_壊れた要素は読み飛ばす() {
        val text = """{"format":"echomark-backup","version":9,"exportedAt":1,"bookmarks":[],"tags":[],"bookmarkTags":[],
            "conversations":[],"messages":[],
            "tagAliases":[{"alias":"　ＡＮＤＲＯＩＤ ","tagId":3},{"alias":"","tagId":3},{"alias":"x"}],
            "removedTags":[{"bookmarkId":1,"tagName":"  Ｋｏｔｌｉｎ","removedAt":7},{"bookmarkId":1}]}"""

        val decoded = BackupJson.decode(text)

        assertEquals(listOf(TagAliasEntity("android", "ANDROID", 3)), decoded.data.tagAliases)
        assertEquals(listOf(RemovedTagEntity(1, "kotlin", "Kotlin", 7)), decoded.data.removedTags)
        assertEquals(3, decoded.invalidRecords)
    }

    @Test
    fun 別名も記録も無い古い版のバックアップは空として読む() {
        val old = """{"format":"echomark-backup","version":8,"exportedAt":1,"bookmarks":[],
            "tags":[],"bookmarkTags":[],"conversations":[],"messages":[]}"""

        val data = BackupJson.decode(old).data

        assertTrue(data.tagAliases.isEmpty())
        assertTrue(data.removedTags.isEmpty())
    }


    @Test
    fun ユーザーのカテゴリとカテゴリを選んだ印を書き出して読み込める() {
        val data = sample.copy(
            bookmarks = sample.bookmarks.map { if (it.id == 1L) it.copy(categoryEdited = true) else it },
            categories = listOf(
                CategoryEntity(name = "旅行", description = "行き先や宿の情報", createdAt = 10),
                CategoryEntity(name = "読書メモ", description = "", createdAt = 20)
            )
        )

        val text = BackupJson.encode(data)
        val decoded = BackupJson.decode(text).data

        assertEquals(data.categories, decoded.categories)
        assertEquals(setOf(1L), decoded.bookmarks.filter { it.categoryEdited }.map { it.id }.toSet())
        // 選んでいないブックマークには印を書かない
        assertEquals(1, Regex("\"categoryEdited\"").findAll(text).count())
    }

    @Test
    fun カテゴリの無い古い版のバックアップは_カテゴリ無しで_AIが付けたものとして読む() {
        val old = """{"format":"echomark-backup","version":9,"exportedAt":1,"bookmarks":[
            {"id":1,"type":"TEXT","title":"t","category":"技術","createdAt":1,"aiStatus":"DONE"}],
            "tags":[],"bookmarkTags":[],"conversations":[],"messages":[]}"""

        val data = BackupJson.decode(old).data

        assertTrue(data.categories.isEmpty())
        assertEquals("技術", data.bookmarks.single().category)
        assertFalse(data.bookmarks.single().categoryEdited)
    }

    @Test
    fun 名前の無いカテゴリは読み飛ばし_名前と説明の前後の空白は除く() {
        val text = """{"format":"echomark-backup","version":10,"exportedAt":1,"bookmarks":[],"tags":[],"bookmarkTags":[],
            "conversations":[],"messages":[],"categories":[{"name":"  旅行 ","description":" 説明 ","createdAt":5},{"name":" "},{"description":"x"}]}"""

        val decoded = BackupJson.decode(text)

        assertEquals(listOf(CategoryEntity(name = "旅行", description = "説明", createdAt = 5)), decoded.data.categories)
        assertEquals(2, decoded.invalidRecords)
    }
}
