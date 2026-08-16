package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unchunks.echomark.domain.model.LearningItemStatus

@Entity(tableName = "learning_items")
data class LearningItemEntity(
    @PrimaryKey(autoGenerate = true)
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
