package com.unchunks.echomark.ui.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
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
    val errorMessage: String? = null
)

/** コレクションの画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface CollectionDetailMessage {
    /** コレクションから外した(元に戻せる) */
    data class Removed(val bookmark: Bookmark) : CollectionDetailMessage

    /** 書き出した */
    data class Exported(val result: ExportResult, val format: ExportFormat) : CollectionDetailMessage

    data class Failed(val message: String) : CollectionDetailMessage
}

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CollectionRepository,
    private val exportRepository: ExportRepository
) : ViewModel() {

    // ルート "collections/{collectionId}" の引数
    private val collectionId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_COLLECTION_ID))

    private val messageChannel = Channel<CollectionDetailMessage>(Channel.BUFFERED)
    val messages: Flow<CollectionDetailMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<CollectionDetailUiState> =
        combine(repository.observeCollection(collectionId), repository.observeBookmarks(collectionId)) { collection, bookmarks ->
            CollectionDetailUiState(isLoading = false, collection = collection, bookmarks = bookmarks)
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

    companion object {
        const val ARG_COLLECTION_ID = "collectionId"
    }
}
