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
    val updatedAt: Long,
    /**
     * 「このブックマークについて質問」で始めた会話の対象ブックマーク。通常の会話は null。
     * ブックマークの削除を取り消したときに紐付けが戻るよう、外部キーにはしない(削除済みなら通常の会話として扱う)。
     */
    val aboutBookmarkId: Long? = null
)
