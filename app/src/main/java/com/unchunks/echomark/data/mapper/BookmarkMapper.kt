package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkWithTags
import com.unchunks.echomark.domain.bookmark.model.Bookmark

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id, type = type, content = content, contentUri = contentUri,
    title = title, summary = summary, category = category,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt, aiStatus = aiStatus,
    imageUrl = imageUrl, siteName = siteName, isFavorite = isFavorite, isArchived = isArchived
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id, type = type, content = content, contentUri = contentUri,
    title = title, summary = summary, category = category,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt, aiStatus = aiStatus,
    imageUrl = imageUrl, siteName = siteName, isFavorite = isFavorite, isArchived = isArchived
)

fun BookmarkWithTags.toDomain(): Bookmark = bookmark.toDomain().copy(tags = tags.map { it.name })
