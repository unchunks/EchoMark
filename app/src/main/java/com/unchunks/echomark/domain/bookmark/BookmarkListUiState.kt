package com.unchunks.echomark.domain.bookmark

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.Tag

data class BookmarkListUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val isLoading: Boolean = false,
    val allTags: List<Tag> = emptyList(),
    val selectedTagId: Long? = null,
    val searchQuery: String = ""
)
