package com.unchunks.echomark.domain.bookmark.model

// ここを変更するときは、com.unchunks.echomark.data.local.entity.BookmarkEntity.kt の変更が不要か確認すること
data class Bookmark(
    val id: Long = 0,
    val type: BookmarkType,
    val content: String? = null,
    val contentUri: String? = null,
    val title: String,
    val summary: String? = null,
    val category: String? = null,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val tags: List<String> = emptyList()
)
