package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.Tag
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    // Create / Save
    suspend fun saveBookmark(bookmark: Bookmark): Long
    suspend fun saveTags(bookmarkId: Long, tagNames: List<String>)
    suspend fun saveEmbedding(bookmarkId: Long, vector: FloatArray, modelVersion: String)

    // Update
    suspend fun updateSummary(id: Long, summary: String)
    suspend fun updateCategory(id: Long, category: String)

    // Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    // Read
    suspend fun getBookmarkById(id: Long): Bookmark?
    suspend fun getBookmarksByIds(ids: List<Long>): List<Bookmark>
    suspend fun getAllBookmarkIds(): List<Long>
    suspend fun getRelatedBookmarks(bookmarkId: Long, limit: Int = 5): List<Bookmark>
    suspend fun getEmbeddingModelVersion(bookmarkId: Long): String?

    // Observe / Search
    fun observeBookmarks(): Flow<List<Bookmark>>
    fun observeBookmarksByTag(tagId: Long): Flow<List<Bookmark>>
    fun observeAllTags(): Flow<List<Tag>>
    fun searchBookmarks(query: String): Flow<List<Bookmark>>
}
