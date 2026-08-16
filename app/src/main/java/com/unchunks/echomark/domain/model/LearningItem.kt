package com.unchunks.echomark.domain.model

data class LearningItem(
    val id: Long = 0,
    val title: String,
    val isTitleManuallySet: Boolean = false,
    val summary: String? = null,
    val learningObjective: String? = null,
    val status: LearningItemStatus = LearningItemStatus.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
    val nextReviewAt: Long? = null,
    val reviewStage: Int = 0,
    val lastReviewedAt: Long? = null
)
