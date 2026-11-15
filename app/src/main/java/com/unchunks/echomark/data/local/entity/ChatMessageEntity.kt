package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unchunks.components.llm.ChatRole

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val conversationId: Long,
    val role: ChatRole,
    val content: String,
    // カンマ区切りでIDを保存
    val referencedBookmarkIds: String? = null,
    val createdAt: Long,
    /**
     * v29 で追加。回答の文脈に入れた Web のページ(JSON の配列。WebSourcesJson)。Web 検索を使わなかった回答・質問は null。
     * 検索サービスの結果そのもの(タイトル・概要)は入れず、ページを読んで得たものだけを入れる。
     */
    val webSources: String? = null
)
