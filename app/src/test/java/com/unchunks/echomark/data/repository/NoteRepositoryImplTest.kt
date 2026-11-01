package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.NoteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ノート・注釈の保存と、AI の要約・本文との分離、ブックマーク削除との連動(実際の Room)。 */
@RunWith(AndroidJUnit4::class)
class NoteRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: NoteRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = NoteRepositoryImpl(db.bookmarkNoteDao(), db.bookmarkDao(), TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun saveBookmark(title: String = "題", content: String? = "本文", summary: String? = "要約"): Long =
        db.bookmarkDao().insert(
            Bookmark(
                type = BookmarkType.TEXT, title = title, content = content, summary = summary, createdAt = 1, lastAccessedAt = 1
            ).toEntity()
        )

    @Test
    fun ノートを足すと前後の空白を除いて保存され_書いた順に読める() = runBlocking {
        val id = saveBookmark()

        val first = repository.addNote(id, "  最初のノート \n", quote = "  引用 ")
        val second = repository.addNote(id, "二つ目")

        assertNotNull(first)
        assertNotNull(second)
        val notes = repository.observeNotes(id).first()
        assertEquals(listOf("最初のノート", "二つ目"), notes.map { it.text })
        assertEquals("引用", notes[0].quote)
        assertNull(notes[1].quote)
        assertEquals(id, notes[0].bookmarkId)
        assertFalse(notes[0].isEdited)
    }

    @Test
    fun 空白だけのノートとブックマークが無いときは保存しない() = runBlocking {
        val id = saveBookmark()

        assertNull(repository.addNote(id, "  \n  "))
        assertNull(repository.addNote(9_999L, "付け先が無い"))

        assertTrue(repository.observeNotes(id).first().isEmpty())
    }

    @Test
    fun 長すぎる文章と引用は決めた長さで切る() = runBlocking {
        val id = saveBookmark()

        repository.addNote(id, "あ".repeat(NoteText.MAX_TEXT + 10), quote = "い".repeat(NoteText.MAX_QUOTE + 10))

        val note = repository.observeNotes(id).first().single()
        assertEquals(NoteText.MAX_TEXT, note.text.length)
        assertEquals(NoteText.MAX_QUOTE, note.quote!!.length)
    }

    @Test
    fun 直すと文章と引用と更新日時が変わり_作成日時はそのまま() = runBlocking {
        val id = saveBookmark()
        val noteId = repository.addNote(id, "前", quote = "引用")!!
        val before = repository.observeNotes(id).first().single()
        Thread.sleep(5)

        assertTrue(repository.updateNote(noteId, " 後 ", null))

        val after = repository.observeNotes(id).first().single()
        assertEquals("後", after.text)
        assertNull(after.quote)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt > before.createdAt)
        assertTrue(after.isEdited)
    }

    @Test
    fun 空にする更新とノートが無いときの更新は失敗する() = runBlocking {
        val id = saveBookmark()
        val noteId = repository.addNote(id, "前")!!

        assertFalse(repository.updateNote(noteId, "   ", null))
        assertFalse(repository.updateNote(9_999L, "後", null))

        assertEquals("前", repository.observeNotes(id).first().single().text)
    }

    @Test
    fun 削除したノートは同じIDで戻せる() = runBlocking {
        val id = saveBookmark()
        val noteId = repository.addNote(id, "消す", quote = "引用")!!
        val note = repository.observeNotes(id).first().single()

        repository.deleteNote(noteId)
        assertTrue(repository.observeNotes(id).first().isEmpty())
        repository.restoreNote(note)

        assertEquals(listOf(note), repository.observeNotes(id).first())
    }

    @Test
    fun ブックマークが無ければノートは戻さない() = runBlocking {
        val id = saveBookmark()
        repository.addNote(id, "ノート")
        val note = repository.observeNotes(id).first().single()
        db.bookmarkDao().delete(db.bookmarkDao().getById(id)!!)

        repository.restoreNote(note)

        assertTrue(db.bookmarkNoteDao().getByBookmark(id).isEmpty())
    }

    @Test
    fun ブックマークを削除するとノートも消える() = runBlocking {
        val id = saveBookmark()
        repository.addNote(id, "ノート")

        db.bookmarkDao().delete(db.bookmarkDao().getById(id)!!)

        assertEquals(0, db.bookmarkNoteDao().countByBookmark(id))
    }

    @Test
    fun ノートは要約と本文とは別で_AIの結果を書き換えてもノートは変わらない() = runBlocking {
        val id = saveBookmark(content = "本文", summary = "AIの要約")
        repository.addNote(id, "自分のノート")

        // AI の再処理で要約を書き換え、結果をクリアする
        db.bookmarkDao().setSummary(id, "新しい要約", edited = false)
        db.bookmarkDao().resetAiResult(id, now = 10)

        val bookmark = db.bookmarkDao().getById(id)!!
        assertEquals("本文", bookmark.content)
        assertEquals(listOf("自分のノート"), repository.observeNotes(id).first().map { it.text })
        // ノートの文章は要約・本文の列に混ざらない
        assertFalse(bookmark.summary.orEmpty().contains("自分のノート"))
        assertFalse(bookmark.content.orEmpty().contains("自分のノート"))
    }

    @Test
    fun ノートの文章はキーワード検索で見つかる() = runBlocking {
        val withNote = saveBookmark(title = "ある記事", content = "関係ない本文", summary = null)
        saveBookmark(title = "別の記事", content = "別の本文", summary = null)
        repository.addNote(withNote, "あとで確かめたい固有の言葉")
        val quoted = saveBookmark(title = "引用の記事", content = "本文", summary = null)
        repository.addNote(quoted, "感想", quote = "引用にだけある珍しい語")

        val byText = db.bookmarkDao().searchByKeyword("固有の言葉").map { it.bookmark.id }
        val byQuote = db.bookmarkDao().searchByKeyword("珍しい語").map { it.bookmark.id }

        assertEquals(listOf(withNote), byText)
        assertEquals(listOf(quoted), byQuote)
    }
}
