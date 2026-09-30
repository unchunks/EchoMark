package com.unchunks.echomark.data.local.entity

import androidx.room.TypeConverter
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.ChatRole

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

    @TypeConverter
    fun fromChatRole(role: ChatRole): String = role.name
    @TypeConverter
    fun toChatRole(value: String): ChatRole = ChatRole.valueOf(value)
}
