package com.unchunks.echomark.domain.insights

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 分析のテストは、月・日の区切りがずれないよう日本時間で固定する。 */
internal val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")

internal fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
    LocalDateTime.of(year, month, day, hour, 0).atZone(TOKYO).toInstant().toEpochMilli()

internal fun date(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

internal fun bookmark(
    id: Long,
    createdAt: Long = 0L,
    tags: List<String> = emptyList(),
    category: String? = null,
    type: BookmarkType = BookmarkType.URL,
    readAt: Long? = null,
    lastAccessedAt: Long = createdAt,
    summary: String? = null,
    isFavorite: Boolean = false,
    isArchived: Boolean = false
) = Bookmark(
    id = id,
    type = type,
    title = "題$id",
    createdAt = createdAt,
    lastAccessedAt = lastAccessedAt,
    tags = tags,
    category = category,
    readAt = readAt,
    summary = summary,
    isFavorite = isFavorite,
    isArchived = isArchived
)
