package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkWithTags
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.TagSource

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id, type = type, content = content, contentUri = contentUri,
    title = title, summary = summary, category = category,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt, aiStatus = aiStatus,
    imageUrl = imageUrl, siteName = siteName, isFavorite = isFavorite, isArchived = isArchived,
    filePath = filePath, mimeType = mimeType, fileName = fileName, fileSize = fileSize
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id, type = type, content = content, contentUri = contentUri,
    title = title, summary = summary, category = category,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt, aiStatus = aiStatus,
    imageUrl = imageUrl, siteName = siteName, isFavorite = isFavorite, isArchived = isArchived,
    filePath = filePath, mimeType = mimeType, fileName = fileName, fileSize = fileSize
)

fun BookmarkWithTags.toDomain(): Bookmark {
    val aiTagIds = tagRefs.filter { it.source == TagSource.AI }.map { it.tagId }.toSet()
    return bookmark.toDomain().copy(
        tags = tags.map { it.name },
        aiTags = tags.filter { it.id in aiTagIds }.map { it.name }.toSet()
    )
}
