package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 要約の履歴の1版(#21)。AI が作った要約と、ユーザーが手で直した要約を、作った順に残す。
 * 現在の要約は bookmarks.summary(履歴は、そこへ戻せる過去の版を持つ)。端末の中だけに持ち、バックアップには含めない。
 * 作った元は文字列で持つ([source] は SummarySource の名前。読めない値は読み出しで AI として扱う)。
 */
@Entity(
    tableName = "summary_history",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookmarkId")]
)
data class SummaryHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val bookmarkId: Long,
    val summary: String,
    val source: String,
    /** 作った AI(「Claude (Anthropic) claude-haiku-5-5」など)。手で直した版や、分からない版は null */
    val generator: String? = null,
    /** 作ったときの言語と書き方(「日本語・文章」など)。分からない版は null */
    val style: String? = null,
    val createdAt: Long
)
