package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkWithTags
import com.unchunks.echomark.data.local.entity.SummaryHistoryEntity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.ProcessingInfo
import com.unchunks.echomark.domain.model.SummarySource
import com.unchunks.echomark.domain.model.SummaryVersion
import com.unchunks.echomark.domain.model.TagSource

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id, type = type, content = content, contentUri = contentUri,
    title = title, summary = summary, summaryEdited = summaryEdited, category = category, categoryEdited = categoryEdited,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt, aiStatus = aiStatus,
    imageUrl = imageUrl, siteName = siteName, isFavorite = isFavorite, isArchived = isArchived,
    filePath = filePath, mimeType = mimeType, fileName = fileName, fileSize = fileSize,
    contentFetchedAt = contentFetchedAt,
    processing = ProcessingInfo(
        stage = processingStage, queuedAt = queuedAt, startedAt = processingStartedAt,
        stageStartedAt = stageStartedAt, finishedAt = processingFinishedAt, failureReason = failureReason
    )
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id, type = type, content = content, contentUri = contentUri,
    title = title, summary = summary, summaryEdited = summaryEdited, category = category, categoryEdited = categoryEdited,
    createdAt = createdAt, lastAccessedAt = lastAccessedAt, aiStatus = aiStatus,
    imageUrl = imageUrl, siteName = siteName, isFavorite = isFavorite, isArchived = isArchived,
    filePath = filePath, mimeType = mimeType, fileName = fileName, fileSize = fileSize,
    contentFetchedAt = contentFetchedAt,
    processingStage = processing.stage, queuedAt = processing.queuedAt, processingStartedAt = processing.startedAt,
    stageStartedAt = processing.stageStartedAt, processingFinishedAt = processing.finishedAt,
    failureReason = processing.failureReason
)

fun BookmarkWithTags.toDomain(): Bookmark {
    val aiTagIds = tagRefs.filter { it.source == TagSource.AI }.map { it.tagId }.toSet()
    return bookmark.toDomain().copy(
        tags = tags.map { it.name },
        aiTags = tags.filter { it.id in aiTagIds }.map { it.name }.toSet()
    )
}

/** 読めない種類(新しい版で増えたもの)は AI の版として読む。 */
fun SummaryHistoryEntity.toDomain(): SummaryVersion = SummaryVersion(
    id = id, bookmarkId = bookmarkId, summary = summary,
    source = SummarySource.entries.firstOrNull { it.name == source } ?: SummarySource.AI,
    generator = generator, style = style, createdAt = createdAt
)
