package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.LearningItemEntity
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.LearningItem


fun LearningItemEntity.toDomain() = LearningItem(
    id = id, title = title, isTitleManuallySet = isTitleManuallySet,
    summary = summary, learningObjective = learningObjective,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
    nextReviewAt = nextReviewAt, reviewStage = reviewStage, lastReviewedAt = lastReviewedAt
)

fun ChatMessageEntity.toDomain() = ChatMessage(
    id = id, learningItemId = learningItemId, role = role, content = content,
    referencedBookmarkIds = referencedBookmarkIds
        ?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
    createdAt = createdAt
)
