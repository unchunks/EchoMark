package com.unchunks.echomark.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class BookmarkDetailUiState(
    val isLoading: Boolean = true,
    /** null かつ isLoading=false なら該当ブックマークなし(削除済みなど) */
    val bookmark: Bookmark? = null,
    val related: List<Bookmark> = emptyList(),
    /** タグ入力の候補にする既存のタグ名 */
    val allTagNames: List<String> = emptyList()
)

/** 詳細画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface BookmarkDetailMessage {
    /** アーカイブした/戻した(元に戻せる) */
    data class ArchiveChanged(val archived: Boolean) : BookmarkDetailMessage

    /** タイトル・本文を編集した(AI の再処理を提案する) */
    data object Edited : BookmarkDetailMessage

    /** AI の再処理を始めた */
    data object ReprocessStarted : BookmarkDetailMessage

    /** 操作に失敗した */
    data class Failed(val message: String) : BookmarkDetailMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookmarkDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BookmarkRepository,
    private val recentlyDeleted: RecentlyDeletedBookmarks
) : ViewModel() {

    private val bookmarkId: Long = checkNotNull(savedStateHandle.get<Long>("bookmarkId"))

    private val bookmarkFlow = repository.observeBookmark(bookmarkId)

    private val messageChannel = Channel<BookmarkDetailMessage>(Channel.BUFFERED)
    val messages: Flow<BookmarkDetailMessage> = messageChannel.receiveAsFlow()

    /** AI 処理の状態が変わるたび(=埋め込みが更新され得るとき)に関連ブックマークを取り直す */
    private val relatedFlow = bookmarkFlow
        .map { it?.aiStatus }
        .distinctUntilChanged()
        .mapLatest {
            try {
                repository.getRelatedBookmarks(bookmarkId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 関連は補助的な表示なので、取れなくても詳細は出す
                Timber.w(e, "関連ブックマークを取得できませんでした")
                emptyList()
            }
        }
        .onStart { emit(emptyList()) }

    private val tagNamesFlow = repository.observeAllTags()
        .map { tags -> tags.map { it.name } }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }
        .onStart { emit(emptyList()) }

    val uiState: StateFlow<BookmarkDetailUiState> =
        combine(bookmarkFlow, relatedFlow, tagNamesFlow) { bookmark, related, tagNames ->
            BookmarkDetailUiState(isLoading = false, bookmark = bookmark, related = related, allTagNames = tagNames)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarkDetailUiState())

    init {
        // 開いたことを記録する(再発見の判定などに使う)
        viewModelScope.launch { repository.markAccessed(bookmarkId) }
    }

    fun addTag(name: String) {
        val trimmed = name.trim().removePrefix("#").trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.addTag(bookmarkId, trimmed) }
    }

    fun removeTag(name: String) {
        viewModelScope.launch { repository.removeTag(bookmarkId, name) }
    }

    fun toggleFavorite() {
        val bookmark = uiState.value.bookmark ?: return
        viewModelScope.launch { repository.setFavorite(bookmarkId, !bookmark.isFavorite) }
    }

    /** アーカイブする/戻す。取り消しは同じ関数を逆の値で呼ぶ */
    fun setArchived(archived: Boolean, notify: Boolean = true) {
        viewModelScope.launch {
            repository.setArchived(bookmarkId, archived)
            if (notify) messageChannel.send(BookmarkDetailMessage.ArchiveChanged(archived))
        }
    }

    /** タイトル・本文(メモ)を保存する。タイトルが空なら保存しない */
    fun saveEdit(title: String, content: String) {
        val newTitle = title.trim()
        if (newTitle.isEmpty()) return
        viewModelScope.launch {
            repository.updateTitleAndContent(bookmarkId, newTitle, content.trim().ifEmpty { null })
            messageChannel.send(BookmarkDetailMessage.Edited)
        }
    }

    /** 要約・タグ・カテゴリ・埋め込みの AI 処理をやり直す */
    fun reprocess() {
        viewModelScope.launch {
            repository.reprocess(bookmarkId)
            messageChannel.send(BookmarkDetailMessage.ReprocessStarted)
        }
    }

    /** 削除して、完了後に onDeleted を呼ぶ(画面を閉じる用)。戻った一覧で「元に戻す」を出せるよう知らせる */
    fun delete(onDeleted: () -> Unit) {
        val bookmark = uiState.value.bookmark ?: return
        viewModelScope.launch {
            try {
                repository.deleteBookmark(bookmark)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ブックマークの削除に失敗")
                messageChannel.send(BookmarkDetailMessage.Failed("削除できませんでした。もう一度お試しください。"))
                return@launch
            }
            recentlyDeleted.notifyDeleted(bookmark)
            onDeleted()
        }
    }
}
