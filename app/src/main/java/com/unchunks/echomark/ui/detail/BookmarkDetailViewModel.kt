package com.unchunks.echomark.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookmarkDetailUiState(
    val isLoading: Boolean = true,
    /** null かつ isLoading=false なら該当ブックマークなし(削除済みなど) */
    val bookmark: Bookmark? = null,
    val related: List<Bookmark> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookmarkDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BookmarkRepository
) : ViewModel() {

    private val bookmarkId: Long = checkNotNull(savedStateHandle.get<Long>("bookmarkId"))

    private val bookmarkFlow = repository.observeBookmark(bookmarkId)

    /** AI 処理の状態が変わるたび(=埋め込みが更新され得るとき)に関連ブックマークを取り直す */
    private val relatedFlow = bookmarkFlow
        .map { it?.aiStatus }
        .distinctUntilChanged()
        .mapLatest { repository.getRelatedBookmarks(bookmarkId) }
        .onStart { emit(emptyList()) }

    val uiState: StateFlow<BookmarkDetailUiState> = combine(bookmarkFlow, relatedFlow) { bookmark, related ->
        BookmarkDetailUiState(isLoading = false, bookmark = bookmark, related = related)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarkDetailUiState())

    init {
        // 開いたことを記録する(再発見の判定などに使う)
        viewModelScope.launch { repository.markAccessed(bookmarkId) }
    }

    fun addTag(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addTag(bookmarkId, name) }
    }

    fun removeTag(name: String) {
        viewModelScope.launch { repository.removeTag(bookmarkId, name) }
    }

    /** 要約・タグ・カテゴリ・埋め込みの AI 処理をやり直す */
    fun reprocess() {
        viewModelScope.launch { repository.reprocess(bookmarkId) }
    }

    /** 削除して、完了後に onDeleted を呼ぶ(画面を閉じる用) */
    fun delete(onDeleted: () -> Unit) {
        val bookmark = uiState.value.bookmark ?: return
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
            onDeleted()
        }
    }
}
