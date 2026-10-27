package com.unchunks.echomark.data.local.entity

import androidx.room.TypeConverter
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage

// enumとDB間での変換
class Converters {
    @TypeConverter
    fun fromBookmarkType(type: BookmarkType): String = type.name
    @TypeConverter
    fun toBookmarkType(value: String): BookmarkType = BookmarkType.valueOf(value)

    @TypeConverter
    fun fromAiStatus(status: AiStatus): String = status.name
    @TypeConverter
    fun toAiStatus(value: String): AiStatus = AiStatus.valueOf(value)

    // 処理の段・失敗の理由は表示の補助なので、読めない値(新しい版で増えたもの)は段なし・不明として読む
    @TypeConverter
    fun fromProcessingStage(stage: ProcessingStage?): String? = stage?.name
    @TypeConverter
    fun toProcessingStage(value: String?): ProcessingStage? =
        value?.let { name -> ProcessingStage.entries.firstOrNull { it.name == name } }

    @TypeConverter
    fun fromFailureReason(reason: ProcessingFailureReason?): String? = reason?.name
    @TypeConverter
    fun toFailureReason(value: String?): ProcessingFailureReason? =
        value?.let { name -> ProcessingFailureReason.entries.firstOrNull { it.name == name } ?: ProcessingFailureReason.UNKNOWN }

    @TypeConverter
    fun fromChatRole(role: ChatRole): String = role.name
    @TypeConverter
    fun toChatRole(value: String): ChatRole = ChatRole.valueOf(value)
}
