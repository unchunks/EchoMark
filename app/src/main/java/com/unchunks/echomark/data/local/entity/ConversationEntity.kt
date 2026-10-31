package com.unchunks.echomark.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,
    val isTitleManuallySet: Boolean = false,
    /** 古い発言を要約した、会話の記憶(端末内 AI の文脈に入れる)。[summaryCoveredUntil] までの発言をまとめている */
    val summary: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * 「このブックマークについて質問」で始めた会話の対象ブックマーク。通常の会話は null。
     * ブックマークの削除を取り消したときに紐付けが戻るよう、外部キーにはしない(削除済みなら通常の会話として扱う)。
     */
    val aboutBookmarkId: Long? = null,
    /**
     * [summary] に含めた最後の発言の日時(createdAt)。null なら要約はまだ無い。
     * バックアップの読み込みで発言の ID は振り直されるため、ID ではなく日時で持つ。
     */
    val summaryCoveredUntil: Long? = null,
    // v19 で追加。一覧の先頭に固定する(#48)
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,
    /**
     * v19 で追加。この会話を分けた元の会話。分けていなければ null(#44)。
     * 元の会話の削除を取り消したときに紐付けが戻るよう、外部キーにはしない(元が無ければ通常の会話として扱う)。
     */
    val branchedFromId: Long? = null,
    /** v19 で追加。元の会話と共通の最後の発言の日時。バックアップの読み込みで発言の ID は振り直されるため、ID ではなく日時で持つ */
    val branchedAt: Long? = null
)
