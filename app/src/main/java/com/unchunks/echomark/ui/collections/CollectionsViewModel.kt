package com.unchunks.echomark.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.bookmark.model.DeletedCollection
import com.unchunks.echomark.domain.repository.CollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class CollectionsUiState(
    val isLoading: Boolean = true,
    val collections: List<BookmarkCollection> = emptyList(),
    val errorMessage: String? = null
)

/** コレクションの一覧で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface CollectionsMessage {
    /** 削除した(元に戻せる) */
    data class Deleted(val deleted: DeletedCollection) : CollectionsMessage

    /** 削除を元に戻せなかった(同じ名前のコレクションを作ったあとなど) */
    data object RestoreFailed : CollectionsMessage

    data class Failed(val message: String) : CollectionsMessage
}

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repository: CollectionRepository
) : ViewModel() {

    private val messageChannel = Channel<CollectionsMessage>(Channel.BUFFERED)
    val messages: Flow<CollectionsMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<CollectionsUiState> = repository.observeCollections()
        .map { CollectionsUiState(isLoading = false, collections = it) }
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "コレクションの一覧の読み込みに失敗")
            emit(CollectionsUiState(isLoading = false, errorMessage = "コレクションを読み込めませんでした。"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionsUiState())

    /** 作る。結果は [onResult] に返す(名前が重複・不正なときは、ダイアログを閉じずに理由を出す) */
    fun create(name: String, description: String, onResult: (CollectionResult) -> Unit) {
        viewModelScope.launch { onResult(repository.create(name, description)) }
    }

    /** 名前と説明を直す。結果は [onResult] に返す */
    fun update(id: Long, name: String, description: String, onResult: (CollectionResult) -> Unit) {
        viewModelScope.launch { onResult(repository.update(id, name, description)) }
    }

    /** 削除する。Snackbar の「元に戻す」で [restore] できるよう知らせる */
    fun delete(collection: BookmarkCollection) {
        viewModelScope.launch {
            val deleted = repository.delete(collection.id)
            if (deleted != null) messageChannel.send(CollectionsMessage.Deleted(deleted))
        }
    }

    fun restore(deleted: DeletedCollection) {
        viewModelScope.launch {
            if (!repository.restore(deleted)) messageChannel.send(CollectionsMessage.RestoreFailed)
        }
    }
}
