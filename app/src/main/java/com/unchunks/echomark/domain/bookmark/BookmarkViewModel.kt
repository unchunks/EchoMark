package com.unchunks.echomark.domain.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

    private val bookmarksFlow: Flow<List<Bookmark>> =
        combine(searchQueryFlow, selectedTagIdFlow) { query, tagId -> query to tagId }
            .flatMapLatest { (query, tagId) ->
                when {
                    tagId != null -> repository.observeBookmarksByTag(tagId)
                    query.isNotBlank() -> repository.searchBookmarks(query)
                    else -> repository.observeBookmarks()
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

    private val _relatedBookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val relatedBookmarks: StateFlow<List<Bookmark>> = _relatedBookmarks

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun onTagSelected(tagId: Long?) {
        selectedTagIdFlow.value = tagId
    }

    fun findRelated(bookmarkId: Long) {
        viewModelScope.launch {
            _relatedBookmarks.value = repository.getRelatedBookmarks(bookmarkId)
        }
    }

    fun clearRelated() {
        _relatedBookmarks.value = emptyList()
    }

    fun addTestBookmark(title: String) {
        viewModelScope.launch {
            repository.saveBookmark(
                Bookmark(
                    type = BookmarkType.TEXT,
                    content = title,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    lastAccessedAt = System.currentTimeMillis()
                )
            )
        }
    }


}
