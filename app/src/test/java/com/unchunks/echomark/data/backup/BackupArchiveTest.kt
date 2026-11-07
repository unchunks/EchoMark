package com.unchunks.echomark.data.backup

import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkNoteEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.SummaryHistoryEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.ChatTemplate
import com.unchunks.echomark.domain.model.TagSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ファイルも含めたバックアップ(zip)の書き出しと、安全な展開。 */
class BackupArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val data = BackupData(
        exportedAt = 1,
        bookmarks = listOf(
            BookmarkEntity(
                id = 1, type = BookmarkType.IMAGE, title = "写真", createdAt = 1, lastAccessedAt = 1, aiStatus = AiStatus.DONE,
                filePath = "attachments/p1.jpg", snapshotPath = "attachments/s1.mhtml"
            )
        ),
        tags = emptyList(), bookmarkTags = emptyList(), conversations = emptyList(), messages = emptyList()
    )

    private fun file(name: String, content: String) = temp.newFile(name).apply { writeText(content) }

    private fun extract(bytes: ByteArray, maxFileBytes: Long = 1_000, maxTotalBytes: Long = 10_000): BackupArchive.Staged =
        BackupArchive.extract(ByteArrayInputStream(bytes), temp.newFolder(), MAX_BACKUP_BYTES, maxFileBytes, maxTotalBytes)

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val json = BackupJson.encode(data)

    /** zip の中の名前 [from] を、同じ長さの [to] に書き換える(ローカルヘッダーと中央ディレクトリの両方) */
    private fun renamed(bytes: ByteArray, from: String, to: String): ByteArray {
        val source = from.toByteArray()
        val target = to.toByteArray()
        require(source.size == target.size)
        val out = bytes.copyOf()
        var index = 0
        while (index <= out.size - source.size) {
            if (source.indices.all { out[index + it] == source[it] }) {
                target.copyInto(out, index)
                index += source.size
            } else {
                index++
            }
        }
        return out
    }

    @Test
    fun 書き出したzipを展開すると_JSONとファイルが元に戻る() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(data, mapOf("attachments/p1.jpg" to file("p1.jpg", "画像"), "attachments/s1.mhtml" to file("s1.mhtml", "ページ")), out)
        val bytes = out.toByteArray()

        assertTrue(BackupArchive.isZip(bytes.copyOf(4)))
        val staged = extract(bytes)
        assertEquals(data, BackupJson.decode(staged.jsonText).data)
        assertEquals(setOf("attachments/p1.jpg", "attachments/s1.mhtml"), staged.files.keys)
        assertEquals("画像", staged.files.getValue("attachments/p1.jpg").readText())
        assertEquals("ページ", staged.files.getValue("attachments/s1.mhtml").readText())
    }

    @Test
    fun JSONのバックアップはzipと見分ける() {
        assertFalse(BackupArchive.isZip(json.toByteArray().copyOf(4)))
        assertFalse(BackupArchive.isZip(ByteArray(0)))
    }

    @Test
    fun 決まった形でない名前のファイルが入っていたら何も取り込まない() {
        for (name in listOf("../evil.sh", "attachments/../../x.jpg", "attachments/sub/p.jpg", "notes.txt", "attachments/")) {
            assertThrows(name, BackupFormatException::class.java) { extract(zipOf(BackupArchive.JSON_ENTRY to json, name to "x")) }
        }
    }

    @Test
    fun 重なり_中身の無いzip_大きすぎるものは断る() {
        // ZipOutputStream は同じ名前を書けないため、同じ長さの別の名前で書いてから名前を書き換える
        assertThrows(BackupFormatException::class.java) {
            extract(renamed(zipOf(BackupArchive.JSON_ENTRY to json, "backup.jsoX" to json), "backup.jsoX", BackupArchive.JSON_ENTRY))
        }
        assertThrows(BackupFormatException::class.java) {
            extract(
                renamed(
                    zipOf(BackupArchive.JSON_ENTRY to json, "attachments/a.jpg" to "1", "attachments/b.jpg" to "2"),
                    "attachments/b.jpg", "attachments/a.jpg"
                )
            )
        }
        assertThrows(BackupFormatException::class.java) { extract(zipOf("attachments/a.jpg" to "1")) }
        // 1つのファイルの上限
        assertThrows(BackupFormatException::class.java) {
            extract(zipOf(BackupArchive.JSON_ENTRY to json, "attachments/a.jpg" to "x".repeat(20)), maxFileBytes = 10)
        }
        // 合計の上限(空き容量)
        assertThrows(BackupFormatException::class.java) {
            extract(
                zipOf(BackupArchive.JSON_ENTRY to json, "attachments/a.jpg" to "x".repeat(8), "attachments/b.jpg" to "x".repeat(8)),
                maxTotalBytes = 10
            )
        }
        // 壊れた zip
        assertThrows(BackupFormatException::class.java) { extract(zipOf(BackupArchive.JSON_ENTRY to json).copyOf(30)) }
    }

    @Test
    fun 中身がそろっていればバックアップの問題は無い() {
        val decoded = DecodedBackup(sound, invalidRecords = 0)
        assertEquals(emptyList<String>(), BackupValidation.problemsOf(decoded))
    }

    private val sound = BackupData(
        exportedAt = 1,
        bookmarks = listOf(BookmarkEntity(id = 1, type = BookmarkType.TEXT, title = "メモ", createdAt = 1, lastAccessedAt = 1)),
        tags = listOf(TagEntity(2, "a")),
        bookmarkTags = listOf(BookmarkTagCrossRef(1, 2, TagSource.USER)),
        conversations = emptyList(),
        messages = emptyList(),
        notes = listOf(BookmarkNoteEntity(bookmarkId = 1, text = "ノート", createdAt = 1, updatedAt = 1)),
        summaryHistory = listOf(SummaryHistoryEntity(bookmarkId = 1, summary = "要約", source = "AI", createdAt = 1))
    )

    @Test
    fun 読めない要素_重なったID_ファイルに無いものへの参照があれば問題にする() {
        assertEquals(1, BackupValidation.problemsOf(DecodedBackup(sound, invalidRecords = 2)).size)

        val broken = sound.copy(
            bookmarks = sound.bookmarks + sound.bookmarks,
            notes = sound.notes + BookmarkNoteEntity(bookmarkId = 99, text = "迷子", createdAt = 1, updatedAt = 1),
            messages = listOf(ChatMessageEntity(id = 1, conversationId = 5, role = ChatRole.USER, content = "?", createdAt = 1)),
            summaryHistory = listOf(SummaryHistoryEntity(bookmarkId = 98, summary = "迷子", source = "AI", createdAt = 1)),
            linkCheckMutedBookmarkIds = listOf(97)
        )
        val problems = BackupValidation.problemsOf(DecodedBackup(broken, invalidRecords = 0))
        assertEquals(problems.toString(), 5, problems.size)
        assertTrue(BackupValidation.message(problems).contains("何も読み込みませんでした"))
    }

    @Test
    fun 読み込んだテンプレートは同じものを足さず_IDがぶつかったら付け直す() {
        val current = listOf(ChatTemplate("a", "要点", "要点をまとめて"))
        val imported = listOf(
            ChatTemplate("x", "要点", "要点をまとめて"),
            ChatTemplate("a", "比較", "比べて"),
            ChatTemplate("b", "質問", "質問を作って")
        )

        val merged = mergeChatTemplates(current, imported) { "new" }

        assertEquals(listOf("a", "new", "b"), merged.map { it.id })
        assertEquals(listOf("要点", "比較", "質問"), merged.map { it.title })
        // 2回読み込んでも増えない
        assertEquals(merged, mergeChatTemplates(merged, imported) { "new2" })
    }
}
