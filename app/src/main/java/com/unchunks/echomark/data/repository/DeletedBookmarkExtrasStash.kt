package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.local.entity.BookmarkNoteEntity
import com.unchunks.echomark.data.local.entity.CollectionItemEntity
import com.unchunks.echomark.data.local.entity.ReviewCardEntity
import javax.inject.Inject
import javax.inject.Singleton

/** 削除したブックマークに付いていた、ブックマーク自体とは別に持つ利用者のデータ(ノート・コレクションへの所属・復習のカード)。 */
data class DeletedBookmarkExtras(
    val notes: List<BookmarkNoteEntity> = emptyList(),
    val collectionItems: List<CollectionItemEntity> = emptyList(),
    val reviewCard: ReviewCardEntity? = null
) {
    val isEmpty: Boolean get() = notes.isEmpty() && collectionItems.isEmpty() && reviewCard == null
}

/**
 * 削除したブックマークのノート・コレクションへの所属・復習のカードを、「元に戻す」に備えて一時的に預かる。
 * これらはブックマークを削除すると連鎖して消えるが、削除の取り消し([BookmarkRepositoryImpl.restoreBookmark])では
 * 戻したい。復元に使う Bookmark には入っていないので、削除の時点でここに預ける。
 * メモリの中だけに持つ(取り消せるのは画面を開いている間だけで、アプリが終われば戻せないのは今の「元に戻す」と同じ)。
 * 古いものから [MAX_ENTRIES] 件を超えた分は捨てる。
 */
@Singleton
class DeletedBookmarkExtrasStash @Inject constructor() {

    private val entries = LinkedHashMap<Long, DeletedBookmarkExtras>()

    @Synchronized
    fun put(bookmarkId: Long, extras: DeletedBookmarkExtras) {
        if (extras.isEmpty) return
        entries.remove(bookmarkId)
        entries[bookmarkId] = extras
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    /** 預かったものを取り出す(取り出したら空にする)。無ければ空 */
    @Synchronized
    fun take(bookmarkId: Long): DeletedBookmarkExtras = entries.remove(bookmarkId) ?: DeletedBookmarkExtras()

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
