package com.unchunks.echomark.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType

// ここを変更するときは、ccom.unchunks.echomark.domain.bookmark.model.Bookmark.kt の変更が不要か確認すること
// contentUri に unique index を張り、同一URLの重複保存を防ぐ(NULLは複数可)
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["contentUri"], unique = true)]
)
data class BookmarkEntity (
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val type: BookmarkType,
    val content: String? = null,
    val contentUri: String? = null,
    val title: String,
    val summary: String? = null,
    val category: String? = null,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val aiStatus: AiStatus = AiStatus.PENDING,
    // v7 で追加。ALTER TABLE で足した列とスキーマを一致させるため、既定値を明示する
    val imageUrl: String? = null,
    val siteName: String? = null,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isArchived: Boolean = false,
    // v10 で追加。保存したファイル(画像・PDF・音声など)の情報
    val filePath: String? = null,
    val mimeType: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null
)
