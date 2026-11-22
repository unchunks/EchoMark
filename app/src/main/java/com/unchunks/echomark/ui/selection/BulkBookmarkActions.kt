package com.unchunks.echomark.ui.selection

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.export.ExportException
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.CollectionRepository
import com.unchunks.echomark.domain.repository.ExportRepository
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/** 複数選択の操作の結果。Snackbar で知らせる(取り消せるものは取り消しの操作つき)。 */
sealed interface BulkMessage {
    /** 知らせるだけ */
    data class Done(val text: String) : BulkMessage

    /** アーカイブした/戻した。[before] は操作の前のブックマーク(取り消しで、元の状態ごとにまとめて戻す) */
    data class Archived(val before: List<Bookmark>, val archived: Boolean) : BulkMessage

    /** 削除した(元に戻せる) */
    data class Deleted(val bookmarks: List<Bookmark>) : BulkMessage

    /** コレクションから外した(元に戻せる) */
    data class RemovedFromCollection(val collectionId: Long, val bookmarks: List<Bookmark>) : BulkMessage

    data class Failed(val text: String) : BulkMessage
}

/**
 * 一覧・コレクションの複数選択で行う操作。1件ずつの操作と同じリポジトリを、まとめて呼ぶ版([BookmarkRepository.setFavoriteAll] など)で呼び、
 * 書き込みは1つのトランザクションにまとまる。再処理は1件ずつ順番に処理する列に積む(件ごとに急がない)。
 * 結果は [BulkMessage] で返す(失敗しても例外は投げない)。ViewModel が Snackbar の知らせにする。
 */
class BulkBookmarkActions @Inject constructor(
    private val repository: BookmarkRepository,
    private val collections: CollectionRepository,
    private val exports: ExportRepository
) {

    suspend fun setFavorite(bookmarks: List<Bookmark>, favorite: Boolean): BulkMessage =
        guarded("お気に入りを変えられませんでした。もう一度お試しください。") {
            repository.setFavoriteAll(bookmarks.map { it.id }, favorite)
            BulkMessage.Done(if (favorite) "${bookmarks.size}件をお気に入りに追加しました" else "${bookmarks.size}件をお気に入りから外しました")
        }

    suspend fun setRead(bookmarks: List<Bookmark>, read: Boolean): BulkMessage =
        guarded("既読・未読を変えられませんでした。もう一度お試しください。") {
            repository.setReadAll(bookmarks.map { it.id }, read)
            BulkMessage.Done(if (read) "${bookmarks.size}件を既読にしました" else "${bookmarks.size}件を未読に戻しました")
        }

    suspend fun setArchived(bookmarks: List<Bookmark>, archived: Boolean): BulkMessage =
        guarded("アーカイブを変えられませんでした。もう一度お試しください。") {
            repository.setArchivedAll(bookmarks.map { it.id }, archived)
            BulkMessage.Archived(bookmarks, archived)
        }

    /** [setArchived] の取り消し。操作の前の状態(アーカイブ済みか)ごとにまとめて戻す */
    suspend fun undoArchived(before: List<Bookmark>) {
        try {
            before.groupBy { it.isArchived }.forEach { (archived, group) -> repository.setArchivedAll(group.map { it.id }, archived) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "アーカイブの取り消しに失敗")
        }
    }

    suspend fun addTag(bookmarks: List<Bookmark>, tagName: String): BulkMessage =
        guarded("タグを付けられませんでした。もう一度お試しください。") {
            val name = tagName.trim()
            if (name.isEmpty()) return@guarded BulkMessage.Failed("タグの名前を入力してください")
            repository.addTagAll(bookmarks.map { it.id }, name)
            BulkMessage.Done("${bookmarks.size}件に「$name」を付けました")
        }

    suspend fun removeTag(bookmarks: List<Bookmark>, tagName: String): BulkMessage =
        guarded("タグを外せませんでした。もう一度お試しください。") {
            // そのタグが付いているものだけ(付いていないものに、外した記録を残さない)
            val targets = bookmarks.filter { bookmark -> tagName in bookmark.tags }
            repository.removeTagAll(targets.map { it.id }, tagName)
            BulkMessage.Done("${targets.size}件から「$tagName」を外しました")
        }

    suspend fun setCategory(bookmarks: List<Bookmark>, category: String): BulkMessage =
        guarded("カテゴリを変えられませんでした。もう一度お試しください。") {
            repository.setCategoryAll(bookmarks.map { it.id }, category)
            BulkMessage.Done("${bookmarks.size}件のカテゴリを「${category.trim()}」にしました")
        }

    suspend fun addToCollection(bookmarks: List<Bookmark>, collectionId: Long, collectionName: String): BulkMessage =
        guarded("コレクションに追加できませんでした。もう一度お試しください。") {
            val added = collections.addAll(collectionId, bookmarks.map { it.id })
            if (added == 0) {
                BulkMessage.Failed("コレクションに追加できませんでした。削除された可能性があります")
            } else {
                BulkMessage.Done("${added}件を「$collectionName」に追加しました")
            }
        }

    /** 新しいコレクションを作って、そこへ入れる。名前の誤り(空・長すぎる・同じ名前)は [CollectionResult] で返し、入れない */
    suspend fun createCollectionAndAdd(bookmarks: List<Bookmark>, name: String): Pair<CollectionResult, BulkMessage?> =
        try {
            when (val result = collections.create(name)) {
                is CollectionResult.Saved -> {
                    val added = collections.addAll(result.id, bookmarks.map { it.id })
                    result to BulkMessage.Done("新しいコレクション「${name.trim()}」に${added}件を入れました")
                }
                else -> result to null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "コレクションの作成に失敗")
            CollectionResult.NotFound to BulkMessage.Failed("コレクションを作れませんでした。もう一度お試しください。")
        }

    suspend fun removeFromCollection(bookmarks: List<Bookmark>, collectionId: Long): BulkMessage =
        guarded("コレクションから外せませんでした。もう一度お試しください。") {
            collections.removeAll(collectionId, bookmarks.map { it.id })
            BulkMessage.RemovedFromCollection(collectionId, bookmarks)
        }

    suspend fun undoRemovedFromCollection(collectionId: Long, bookmarks: List<Bookmark>) {
        try {
            collections.addAll(collectionId, bookmarks.map { it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "コレクションから外した操作の取り消しに失敗")
        }
    }

    suspend fun reprocess(bookmarks: List<Bookmark>): BulkMessage =
        guarded("再処理を始められませんでした。もう一度お試しください。") {
            val queued = repository.reprocessAll(bookmarks.map { it.id })
            BulkMessage.Done(
                if (queued.started) {
                    "${queued.count}件を再処理の順番待ちに入れました。1件ずつ順番に処理します"
                } else {
                    "${queued.count}件を処理待ちにしました。処理を始められる状態になったら順番に処理します"
                }
            )
        }

    suspend fun export(bookmarks: List<Bookmark>, format: ExportFormat, uri: String): BulkMessage =
        guarded("書き出せませんでした。") {
            val result = exports.exportBookmarks(bookmarks.map { it.id }, EXPORT_TITLE, format, uri)
            BulkMessage.Done(exportedText(result.exported, result.skipped))
        }

    suspend fun delete(bookmarks: List<Bookmark>): BulkMessage =
        guarded("削除できませんでした。もう一度お試しください。") {
            repository.deleteBookmarks(bookmarks)
            BulkMessage.Deleted(bookmarks)
        }

    suspend fun undoDeleted(bookmarks: List<Bookmark>) {
        try {
            repository.restoreBookmarks(bookmarks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "削除の取り消しに失敗")
        }
    }

    private suspend inline fun guarded(failure: String, block: () -> BulkMessage): BulkMessage =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExportException) {
            BulkMessage.Failed(e.message ?: failure)
        } catch (e: Exception) {
            Timber.w(e, "複数選択の操作に失敗")
            BulkMessage.Failed(failure)
        }

    companion object {
        /** 書き出した文書の見出し・保存先の初期のファイル名に使う */
        const val EXPORT_TITLE = "選んだブックマーク"

        /** 書き出し後の知らせ。形式に入れられず飛ばした分があれば添える */
        fun exportedText(exported: Int, skipped: Int): String =
            if (skipped > 0) {
                "${exported}件を書き出しました(リンクの無い${skipped}件は、この形式には入りません)"
            } else {
                "${exported}件を書き出しました"
            }
    }
}
