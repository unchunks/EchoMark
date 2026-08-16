package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkWithTags
import com.unchunks.echomark.domain.bookmark.model.Bookmark

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id, type = type, content = content, contentUri = contentUri,
    title = title, summary = summary, category = category,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id, type = type, content = content, contentUri = contentUri,
    title = title, summary = summary, category = category,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt
)

fun BookmarkWithTags.toDomain(): Bookmark = Bookmark(
    id = bookmark.id, type = bookmark.type, content = bookmark.content,
    contentUri = bookmark.contentUri, title = bookmark.title, summary = bookmark.summary,
    category = bookmark.category, createdAt = bookmark.createdAt,
    lastAccessedAt = bookmark.lastAccessedAt,
    tags = tags.map { it.name }
)
