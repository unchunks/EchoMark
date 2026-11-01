package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import kotlinx.coroutines.flow.Flow

/**
 * ブックマークのノートと注釈(#95)。AI の要約・本文とは別に保存し、AI の再処理では変わらない。
 * ブックマークを削除するとノートも消える(「元に戻す」では戻る)。
 */
interface NoteRepository {

    /** ブックマークのノート(書いた順) */
    fun observeNotes(bookmarkId: Long): Flow<List<BookmarkNote>>

    /**
     * ノートを足す。[text] が空白だけ、またはブックマークが無い(削除済み)なら何もせず null。
     * [quote] は引用した文章(無ければ null)。足したノートの ID を返す。
     */
    suspend fun addNote(bookmarkId: Long, text: String, quote: String? = null): Long?

    /** ノートを直す。[text] が空白だけ、またはノートが無ければ false。直した日時を更新する。 */
    suspend fun updateNote(id: Long, text: String, quote: String?): Boolean

    suspend fun deleteNote(id: Long)

    /** 削除したノートを同じ ID・日時で戻す(「元に戻す」用)。ブックマークが無ければ何もしない。 */
    suspend fun restoreNote(note: BookmarkNote)
}
