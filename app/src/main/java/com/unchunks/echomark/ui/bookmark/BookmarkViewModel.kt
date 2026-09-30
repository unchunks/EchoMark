package com.unchunks.echomark.ui.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.SaveResult
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookmarkViewModel @Inject constructor(
    private val repository: BookmarkRepository
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val selectedTagIdFlow = MutableStateFlow<Long?>(null)

    /** 入力が空なら即時、入力中は 300ms のデバウンスを掛けて検索実行を間引く */
    @OptIn(FlowPreview::class)
    private val debouncedQueryFlow: Flow<String> = searchQueryFlow
        .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    private val bookmarksFlow: Flow<List<Bookmark>> =
        combine(debouncedQueryFlow, selectedTagIdFlow) { query, tagId -> query to tagId }
            .flatMapLatest { (query, tagId) ->
                when {
                    query.isBlank() && tagId != null -> repository.observeBookmarksByTag(tagId)
                    query.isBlank() -> repository.observeBookmarks()
                    // ハイブリッド検索。DB が変わったとき(削除・AI処理完了など)も再検索する
                    else -> repository.observeBookmarks().mapLatest { repository.search(query, tagId) }
                }
            }

    val uiState: StateFlow<BookmarkListUiState> = combine(
        bookmarksFlow,
        repository.observeAllTags(),
        searchQueryFlow,
        selectedTagIdFlow
    ) { bookmarks, tags, query, tagId ->
        BookmarkListUiState(
            bookmarks = bookmarks,
            allTags = tags,
            searchQuery = query,
            selectedTagId = tagId)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BookmarkListUiState(isLoading = true))

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun onTagSelected(tagId: Long?) {
        selectedTagIdFlow.value = tagId
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.deleteBookmark(bookmark) }
    }

    /** スワイプ削除の取り消し。同じ ID・タグで復元し、埋め込みを再生成する */
    fun restoreBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.restoreBookmark(bookmark) }
    }

    /** テキストのブックマークを保存する。本文は任意。 */
    fun saveTextBookmark(title: String, content: String?, onSaved: (SaveResult) -> Unit = {}) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val result = repository.saveBookmarkWithResult(
                Bookmark(
                    type = BookmarkType.TEXT,
                    content = content?.takeIf { it.isNotBlank() },
                    title = title.trim(),
                    createdAt = now,
                    lastAccessedAt = now
                )
            )
            onSaved(result)
        }
    }

    /** URLのブックマークを保存する。同一URLが保存済みなら既存を再利用する(SaveResult.isDuplicate)。 */
    fun saveUrlBookmark(url: String, title: String?, memo: String?, onSaved: (SaveResult) -> Unit = {}) {
        if (url.isBlank()) return
        viewModelScope.launch {
            onSaved(repository.saveUrlBookmark(url, title, memo))
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
