package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.BookmarkNoteDao
import com.unchunks.echomark.data.local.entity.BookmarkNoteEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.domain.bookmark.model.NoteText
import com.unchunks.echomark.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject

class NoteRepositoryImpl @Inject constructor(
    private val noteDao: BookmarkNoteDao,
    private val bookmarkDao: BookmarkDao,
    private val dispatcherProvider: DispatcherProvider
) : NoteRepository {

    override fun observeNotes(bookmarkId: Long): Flow<List<BookmarkNote>> =
        noteDao.observeByBookmark(bookmarkId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    override suspend fun addNote(bookmarkId: Long, text: String, quote: String?): Long? =
        withContext(dispatcherProvider.io) {
            val cleaned = NoteText.cleanText(text) ?: return@withContext null
            // 削除済みのブックマークには付けない(外部キーの制約で失敗する前に確かめる)
            if (!bookmarkDao.exists(bookmarkId)) return@withContext null
            val now = System.currentTimeMillis()
            noteDao.insert(
                BookmarkNoteEntity(
                    bookmarkId = bookmarkId, text = cleaned, quote = NoteText.cleanQuote(quote), createdAt = now, updatedAt = now
                )
            )
        }

    override suspend fun updateNote(id: Long, text: String, quote: String?): Boolean =
        withContext(dispatcherProvider.io) {
            val cleaned = NoteText.cleanText(text) ?: return@withContext false
            if (noteDao.getById(id) == null) return@withContext false
            noteDao.update(id, cleaned, NoteText.cleanQuote(quote), System.currentTimeMillis())
            true
        }

    override suspend fun deleteNote(id: Long) = withContext(dispatcherProvider.io) {
        noteDao.delete(id)
    }

    override suspend fun restoreNote(note: BookmarkNote) {
        withContext(dispatcherProvider.io) {
            if (!bookmarkDao.exists(note.bookmarkId)) return@withContext
            noteDao.insert(note.toEntity())
        }
    }
}

fun BookmarkNoteEntity.toDomain() = BookmarkNote(
    id = id, bookmarkId = bookmarkId, text = text, quote = quote, createdAt = createdAt, updatedAt = updatedAt
)

fun BookmarkNote.toEntity() = BookmarkNoteEntity(
    id = id, bookmarkId = bookmarkId, text = text, quote = quote, createdAt = createdAt, updatedAt = updatedAt
)
