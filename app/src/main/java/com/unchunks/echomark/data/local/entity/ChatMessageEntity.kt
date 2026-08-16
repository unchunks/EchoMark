package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unchunks.echomark.domain.model.ChatRole

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = LearningItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["learningItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("learningItemId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val learningItemId: Long,
    val role: ChatRole,
    val content: String,
    // カンマ区切りでIDを保存
    val referencedBookmarkIds: String? = null,
    val createdAt: Long
)
