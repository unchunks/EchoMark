package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.domain.bookmark.model.NoteText
import com.unchunks.echomark.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** ノートの Fake。メモリ上に持つ。[existingBookmarkIds] に無いブックマークには付けられない。 */
class FakeNoteRepository(var existingBookmarkIds: Set<Long>? = null) : NoteRepository {
    val notes = MutableStateFlow<List<BookmarkNote>>(emptyList())
    private var nextId = 1L
    var now = 1_000L

    override fun observeNotes(bookmarkId: Long): Flow<List<BookmarkNote>> =
        notes.map { list -> list.filter { it.bookmarkId == bookmarkId }.sortedWith(compareBy({ it.createdAt }, { it.id })) }

    override suspend fun addNote(bookmarkId: Long, text: String, quote: String?): Long? {
        val cleaned = NoteText.cleanText(text) ?: return null
        if (existingBookmarkIds?.contains(bookmarkId) == false) return null
        val id = nextId++
        notes.value += BookmarkNote(id, bookmarkId, cleaned, NoteText.cleanQuote(quote), now, now)
        return id
    }

    override suspend fun updateNote(id: Long, text: String, quote: String?): Boolean {
        val cleaned = NoteText.cleanText(text) ?: return false
        val old = notes.value.firstOrNull { it.id == id } ?: return false
        notes.value = notes.value.map {
            if (it.id == old.id) it.copy(text = cleaned, quote = NoteText.cleanQuote(quote), updatedAt = ++now) else it
        }
        return true
    }

    override suspend fun deleteNote(id: Long) {
        notes.value = notes.value.filterNot { it.id == id }
    }

    override suspend fun restoreNote(note: BookmarkNote) {
        if (existingBookmarkIds?.contains(note.bookmarkId) == false) return
        notes.value += note
    }
}
