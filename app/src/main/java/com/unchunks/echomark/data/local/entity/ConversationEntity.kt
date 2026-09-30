package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,
    val isTitleManuallySet: Boolean = false,
    val summary: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
