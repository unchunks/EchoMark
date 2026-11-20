package com.unchunks.echomark.ui.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.repository.CategoryRepository
import com.unchunks.echomark.ui.selection.BulkBookmarkActions
import com.unchunks.echomark.ui.selection.BulkMessage
import com.unchunks.echomark.ui.selection.SelectionHolder
import com.unchunks.echomark.domain.export.ExportException
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.export.ExportResult
import com.unchunks.echomark.domain.repository.CollectionRepository
import com.unchunks.echomark.domain.repository.ExportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class CollectionDetailUiState(
    val isLoading: Boolean = true,
    /** null かつ isLoading=false ならコレクションなし(削除済み) */
    val collection: BookmarkCollection? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val errorMessage: String? = null,
    /** 複数選択のモードか(長押し・メニューの「選択」) */
    val isSelecting: Boolean = false,
    /** 選んでいるブックマークの ID(今の一覧に出ているものだけ) */
    val selectedIds: Set<Long> = emptySet(),
    /** 複数選択の「コレクションに追加」に出すコレクション(すべて) */
    val collections: List<BookmarkCollection> = emptyList(),
    /** 複数選択の「カテゴリを設定」に出すカテゴリ */
    val categoryChoices: List<CategoryEntry> = emptyList()
) {
    /** 複数選択で選んでいるブックマーク(一覧の順) */
    val selectedBookmarks: List<Bookmark> get() = bookmarks.filter { it.id in selectedIds }
}

/** コレクションの画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface CollectionDetailMessage {
    /** コレクションから外した(元に戻せる) */
    data class Removed(val bookmark: Bookmark) : CollectionDetailMessage

    /** 書き出した */
    data class Exported(val result: ExportResult, val format: ExportFormat) : CollectionDetailMessage

    data class Failed(val message: String) : CollectionDetailMessage

    /** 複数選択の操作の結果 */
    data class Bulk(val message: BulkMessage) : CollectionDetailMessage
}

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CollectionRepository,
    private val exportRepository: ExportRepository,
    private val categoryRepository: CategoryRepository,
    private val bulk: BulkBookmarkActions
) : ViewModel() {

    /** 複数選択(長押し・メニューの「選択」)の状態。回転やプロセスの再作成のあとも保つ */
    private val selection = SelectionHolder(savedStateHandle)

    // ルート "collections/{collectionId}" の引数
    private val collectionId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_COLLECTION_ID))

    private val messageChannel = Channel<CollectionDetailMessage>(Channel.BUFFERED)
    val messages: Flow<CollectionDetailMessage> = messageChannel.receiveAsFlow()

    /** 複数選択の選択肢に出す、カテゴリとコレクション。補助的な表示なので、読めなければ空として扱う */
    private val categoryChoicesFlow: Flow<List<CategoryEntry>> = categoryRepository.observeCategories()
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }
    private val collectionChoicesFlow: Flow<List<BookmarkCollection>> = repository.observeCollections()
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }

    val uiState: StateFlow<CollectionDetailUiState> =
        combine(
            repository.observeCollection(collectionId),
            repository.observeBookmarks(collectionId),
            selection.state,
            collectionChoicesFlow,
            categoryChoicesFlow
        ) { collection, bookmarks, selectionState, collections, categories ->
            CollectionDetailUiState(
                isLoading = false,
                collection = collection,
                bookmarks = bookmarks,
                isSelecting = selectionState.active,
                // 外したなどで今の一覧から無くなったものは、選んでいる数・操作の対象に入れない
                selectedIds = selectionState.ids.intersect(bookmarks.mapTo(HashSet()) { it.id }),
                collections = collections,
                categoryChoices = categories
            )
        }
            .catch { e ->
                if (e is CancellationException) throw e
                Timber.w(e, "コレクションの読み込みに失敗")
                emit(CollectionDetailUiState(isLoading = false, errorMessage = "コレクションを読み込めませんでした。"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionDetailUiState())

    /** コレクションから外す(ブックマーク自体は消えない)。Snackbar の「元に戻す」で [add] し直せるよう知らせる */
    fun remove(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.remove(collectionId, bookmark.id)
            messageChannel.send(CollectionDetailMessage.Removed(bookmark))
        }
    }

    fun add(bookmark: Bookmark) {
        viewModelScope.launch { repository.add(collectionId, bookmark.id) }
    }

    /** コレクションの中身を [format] で [uri] に書き出す */
    fun export(format: ExportFormat, uri: String) {
        viewModelScope.launch {
            try {
                messageChannel.send(CollectionDetailMessage.Exported(exportRepository.exportCollection(collectionId, format, uri), format))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportException) {
                messageChannel.send(CollectionDetailMessage.Failed(e.message ?: "書き出せませんでした。"))
            } catch (e: Exception) {
                Timber.w(e, "コレクションの書き出しに失敗")
                messageChannel.send(CollectionDetailMessage.Failed("書き出せませんでした。"))
            }
        }
    }

    // ---- 複数選択 ----

    /** 選択モードに入る。[id] を渡すと、それを選んだ状態で始める(長押し) */
    fun onEnterSelection(id: Long? = null) = selection.enter(id)

    fun onToggleSelection(id: Long) = selection.toggle(id)

    /** 今の一覧に出ているものすべてを選ぶ */
    fun onSelectAll() = selection.selectAll(uiState.value.bookmarks.map { it.id })

    fun onClearSelection() = selection.clear()

    fun onExitSelection() = selection.exit()

    fun bulkSetFavorite(favorite: Boolean) = runBulk { bulk.setFavorite(it, favorite) }

    fun bulkSetRead(read: Boolean) = runBulk { bulk.setRead(it, read) }

    fun bulkSetArchived(archived: Boolean) = runBulk { bulk.setArchived(it, archived) }

    fun bulkAddTag(tagName: String) = runBulk { bulk.addTag(it, tagName) }

    fun bulkRemoveTag(tagName: String) = runBulk { bulk.removeTag(it, tagName) }

    fun bulkSetCategory(category: String) = runBulk { bulk.setCategory(it, category) }

    fun bulkAddToCollection(collection: BookmarkCollection) = runBulk { bulk.addToCollection(it, collection.id, collection.name) }

    /** このコレクションから、選んだものを外す(ブックマーク自体は消えない) */
    fun bulkRemoveFromCollection() = runBulk { bulk.removeFromCollection(it, collectionId) }

    /** 再処理。1件ずつ順番に処理する列に積む(件ごとに急がない) */
    fun bulkReprocess() = runBulk { bulk.reprocess(it) }

    fun bulkExport(format: ExportFormat, uri: String) = runBulk { bulk.export(it, format, uri) }

    /** 選んだものを削除する(コレクションから外すだけではなく、ブックマークごと)。「元に戻す」で [undoBulkDelete] */
    fun bulkDelete() = runBulk { bulk.delete(it) }

    /** 新しいコレクションを作って、選んだものを入れる。名前の誤りは [onResult] で返す(入れない) */
    fun bulkCreateCollectionAndAdd(name: String, onResult: (CollectionResult) -> Unit) {
        val targets = uiState.value.selectedBookmarks
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val (result, message) = bulk.createCollectionAndAdd(targets, name)
            onResult(result)
            if (message != null) finishBulk(message)
        }
    }

    fun undoBulkArchive(before: List<Bookmark>) {
        viewModelScope.launch { bulk.undoArchived(before) }
    }

    fun undoBulkDelete(bookmarks: List<Bookmark>) {
        viewModelScope.launch { bulk.undoDeleted(bookmarks) }
    }

    fun undoBulkRemoveFromCollection(collectionId: Long, bookmarks: List<Bookmark>) {
        viewModelScope.launch { bulk.undoRemovedFromCollection(collectionId, bookmarks) }
    }

    private fun runBulk(action: suspend (List<Bookmark>) -> BulkMessage) {
        val targets = uiState.value.selectedBookmarks
        if (targets.isEmpty()) return
        viewModelScope.launch { finishBulk(action(targets)) }
    }

    /** 操作を終えた: 失敗したときは選んだままにして(もう一度試せる)、成功したら選択モードを終える */
    private suspend fun finishBulk(message: BulkMessage) {
        if (message !is BulkMessage.Failed) selection.exit()
        messageChannel.send(CollectionDetailMessage.Bulk(message))
    }

    companion object {
        const val ARG_COLLECTION_ID = "collectionId"
    }
}
