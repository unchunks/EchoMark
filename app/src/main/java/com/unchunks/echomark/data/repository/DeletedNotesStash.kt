package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.local.entity.BookmarkNoteEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 削除したブックマークのノートを、「元に戻す」に備えて一時的に預かる。
 * ノートはブックマークを削除すると連鎖して消えるが、削除の取り消し([BookmarkRepositoryImpl.restoreBookmark])では
 * ノートも戻したい。復元に使う Bookmark にはノートが入っていないので、削除の時点でここに預ける。
 * メモリの中だけに持つ(取り消せるのは画面を開いている間だけで、アプリが終われば戻せないのは今の「元に戻す」と同じ)。
 * 古いものから [MAX_ENTRIES] 件を超えた分は捨てる。
 */
@Singleton
class DeletedNotesStash @Inject constructor() {

    private val entries = LinkedHashMap<Long, List<BookmarkNoteEntity>>()

    @Synchronized
    fun put(bookmarkId: Long, notes: List<BookmarkNoteEntity>) {
        if (notes.isEmpty()) return
        entries.remove(bookmarkId)
        entries[bookmarkId] = notes
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    /** 預かったノートを取り出す(取り出したら空にする)。無ければ空 */
    @Synchronized
    fun take(bookmarkId: Long): List<BookmarkNoteEntity> = entries.remove(bookmarkId).orEmpty()

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
